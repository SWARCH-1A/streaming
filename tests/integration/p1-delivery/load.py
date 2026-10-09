"""SPEC-13 open-loop load against the own TLS fixture. Failures are never excluded from acceptance.

Full: 60 s warm-up + 600 s, 5 sources/100 real Firefox players/10 API req/s/20 Chat sends/s.
--diagnostic uses 5 players and 30 measured seconds, explicitly NOT P1 acceptance.
"""
from __future__ import annotations
import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime,timezone
import hashlib
import json
import math
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import threading
import time
import uuid
import websocket
import verify as v
from seed import seed
from metrics import Metrics
from network import PlayerNetwork


def private_json(path,value):
    with os.fdopen(os.open(path,os.O_WRONLY|os.O_CREAT|os.O_TRUNC,0o600),"w") as out:json.dump(value,out,indent=2)

def percentile(values,fraction=.95):
    return sorted(values)[max(0,math.ceil(len(values)*fraction)-1)] if values else None

def firefox_profile():
    binary=shutil.which("certutil")
    if not binary and Path("/opt/homebrew/opt/nss/bin/certutil").exists():binary="/opt/homebrew/opt/nss/bin/certutil"
    if not binary:raise RuntimeError("NSS certutil required: libnss3-tools (Linux) or nss (Homebrew)")
    profile=v.manage.STATE/"firefox-load-profile";profile.mkdir(mode=0o700,exist_ok=True)
    if not (profile/"cert9.db").exists():v.manage.run([binary,"-N","--empty-password","-d","sql:"+str(profile)])
    v.manage.run([binary,"-A","-n","STREAMING P1 local CA","-t","C,,","-i",str(v.CA),"-d","sql:"+str(profile)])

def run(diagnostic):
    duration=30 if diagnostic else 600
    players=5 if diagnostic else 100
    # `up` starts containers before Core is ready, including its migrations.
    v.eventually(lambda:v.Client().call("/api/taxonomy"),lambda value:bool(value),timeout=90)
    fixture=seed()
    catalog=v.Client().call("/api/taxonomy")
    if len(catalog["categories"])!=7 or len(catalog["tags"])!=8:raise RuntimeError("Load requires the exact 7-category/8-tag seed; use a clean disposable profile")
    if not diagnostic and v.sql("SELECT count(*) FROM channels.channels")!="100":
        raise RuntimeError("Full load requires exactly 100 channels; reset own disposable data before seed")
    firefox_profile()
    # Encode before measurement; the five independent real RTMPS publishers only remux during load.
    media_fixture=v.manage.STATE/"load-source.mp4"
    v.manage.run(["ffmpeg","-hide_banner","-loglevel","error","-y","-f","lavfi","-i","testsrc2=size=1280x720:rate=30",
        "-f","lavfi","-i","sine=frequency=1000:sample_rate=48000","-t","30","-c:v","libx264","-threads","2",
        "-preset","ultrafast","-g","30","-b:v","2200k","-maxrate","2300k","-bufsize","4600k","-pix_fmt","yuv420p",
        "-c:a","aac","-b:a","96k",str(media_fixture)],timeout=90)
    media_fixture.chmod(0o600)
    source_info=json.loads(v.manage.run(["ffprobe","-v","error","-show_streams","-of","json",str(media_fixture)]))
    video=next(x for x in source_info["streams"] if x["codec_type"]=="video")
    audio=next(x for x in source_info["streams"] if x["codec_type"]=="audio")
    if (video["codec_name"],video["width"],video["height"],video["avg_frame_rate"],audio["codec_name"])!=("h264",1280,720,"30/1","aac"):
        raise RuntimeError("Load media fixture does not match the required profile")
    source_bitrate=int(video["bit_rate"])+int(audio["bit_rate"])
    if source_bitrate>2_500_000:raise RuntimeError("Load media bitrate exceeds the required profile")
    clients=[]; csrf=[]
    for i in range(100):
        client=v.Client(timeout=2)
        csrf.append(client.call("/api/identity/csrf"));clients.append(client)
    owners=[]; configs=[]; sessions=[]; sources=[]; sockets=[]; threads=[]; log=None; driver=None
    stop=threading.Event();lock=threading.Lock();resources=None;network=None
    stats={"mode":"diagnostic-NOT-acceptance" if diagnostic else "full-P1", "durationSeconds":duration,"warmupSeconds":60,
           "players":players,"sources":5,"sourceMode":"pre-encoded 720p30 H264/AAC; five independent real-time RTMPS publishers",
           "sourceAverageBitrateBitsPerSecond":source_bitrate,
           "api":{},"failures":[],"apiScheduleLate":0,"chatScheduleLate":0,
           "chatSent":0,"chatAck":0,"chatDelivery":0,"chatAckMs":[],"chatDeliveryMs":[],"chatDuplicates":0,"chatSequenceGaps":0}
    sent={};acked=set();delivered=set();last_sequence={}
    def failure(operation,error):
        with lock:stats["failures"].append({"operation":operation,"error":str(error)[:160],"observedAtMonotonic":time.monotonic()})
    def receiver(index,ws):
        while not stop.is_set():
            try:frame=json.loads(ws.recv())
            except websocket.WebSocketTimeoutException:continue
            except Exception:
                if not stop.is_set():failure("chat-receive","transport closed")
                return
            now=time.monotonic()
            with lock:
                if frame["type"]=="error":stats["failures"].append({"operation":"chat","error":frame["code"],"retryAfterMs":frame.get("retryAfterMs"),"observedAtMonotonic":now})
                elif frame["type"]=="message.accepted":
                    key=frame["clientMessageId"]
                    if key in sent:
                        if key in acked:stats["chatDuplicates"]+=1
                        else:
                            acked.add(key);stats["chatAck"]+=1;stats["chatAckMs"].append((now-sent[key])*1000)
                elif index<5 and frame["type"]=="message.created":
                    message=frame["message"];key=message["text"]
                    if key in sent:
                        if key in delivered:stats["chatDuplicates"]+=1
                        else:
                            delivered.add(key);stats["chatDelivery"]+=1;stats["chatDeliveryMs"].append((now-sent[key])*1000)
                            sid=message["sessionId"];seq=message["sequence"]
                            if seq!=last_sequence[sid]+1:stats["chatSequenceGaps"]+=1
                            last_sequence[sid]=seq
    try:
        log_path=v.manage.STATE/"load-media.log"
        log=open(log_path,"w");os.chmod(log_path,0o600)
        for i in range(5):
            owner=v.Client();owner.login(f"p1_load_{i:03d}",fixture["password"]);owners.append(owner)
            channel=owner.call(f"/api/channels/by-handle/p1_load_{i:03d}")
            path="/api/channels/"+channel["channel"]["channelId"]+"/streams"
            try:
                config=owner.call(path)
                key=owner.call("/api/streams/"+config["streamId"]+"/ingest-keys/rotate","POST")
                config["streamKey"]=key["streamKey"]
            except AssertionError as error:
                if "got 404" not in str(error):raise
                config=owner.call(path,"POST",{"title":("Coincidencia P1 " if i%2==0 else "Otra emisión ")+str(i),
                    "categoryId":catalog["categories"][i%7]["id"],"tagIds":[catalog["tags"][i%8]["id"]]},
                    {"Idempotency-Key":str(uuid.uuid4())},201)
            configs.append(config);sources.append(v.publish(config,log,media_fixture))
        for config in configs:
            live=v.eventually(lambda:v.Client().call("/api/streams/"+config["streamId"]),lambda s:s["availability"]=="PLAYABLE")
            sessions.append(live["sessionId"])
        for i in range(25):
            person=v.Client();person.login(f"p1_load_{i:03d}",fixture["password"])
            ws=v.connect(person,sessions[i%5]);ready=v.frame(ws,"chat.ready");ws.settimeout(1)
            if i<5:last_sequence[sessions[i]]=ready["lastSequence"]
            sockets.append(ws)
            # recv() also services Ping/Pong. Keep connections alive while all
            # 100 browser contexts are prepared, before warm-up sends begin.
            thread=threading.Thread(target=receiver,args=(i,ws),daemon=True);thread.start();threads.append(thread)
        queries=json.loads((v.ROOT/"contracts/generated/graphql-queries.json").read_text())
        # One documented endpoint operation per slot. CSRF is prefetched outside the measured API rate.
        operations=["login","discovery","discovery","channel-search","taxonomy","login","channel","profile","stream","discovery"]
        def request(slot):
            op=operations[slot%10];client=clients[slot%100];began=time.monotonic();ok=False
            try:
                if op=="login":
                    n=(slot//10*2+(1 if slot%10==5 else 0))%100;client=clients[n];token=csrf[n]
                    value=client.call("/api/identity/sessions","POST",{"login":f"p1_load_{n:03d}","password":fixture["password"]},{token["headerName"]:token["token"]})
                elif op in ("discovery","channel-search"):
                    query=queries[0 if op=="discovery" else 1]["query"]
                    value=client.call("/api/discovery/graphql","POST",{"query":query,"variables":{"limit":20}})
                    if value.get("errors"):raise AssertionError("GraphQL errors in valid operation")
                elif op=="taxonomy":value=client.call("/api/taxonomy")
                elif op=="channel":value=client.call("/api/channels/by-handle/p1_load_000")
                elif op=="profile":value=client.call("/api/profile/users/"+profile_id)
                else:value=client.call("/api/streams/"+configs[slot%5]["streamId"])
                ok=True
            except Exception as error:failure(op,type(error).__name__+": "+str(error).splitlines()[0])
            finally:
                elapsed=(time.monotonic()-began)*1000
                with lock:
                    metric=stats["api"].setdefault(op,{"attempts":0,"success":0,"ms":[]})
                    metric["attempts"]+=1
                    if ok:metric["success"]+=1;metric["ms"].append(elapsed)
        # User ID read once, outside measurement; no extra /me call in the 10 req/s workload.
        profile_id=owners[0].call("/api/profile/me")["userId"]
        for name in ("load-start.json","load-players-ready.json","load-players-report.json"):
            (v.manage.STATE/name).unlink(missing_ok=True)
        network=PlayerNetwork(v.ORIGIN,players).__enter__()
        private_json(v.manage.STATE/"load-players.json",{"origin":v.ORIGIN,"players":players,"sessions":sessions,"proxyPorts":network.ports})
        driver_log_path=v.manage.STATE/"load-player-driver.log"
        with open(driver_log_path,"w") as driver_log:
            os.chmod(driver_log_path,0o600)
            driver=subprocess.Popen(["node",str(Path(__file__).with_name("players.mjs"))],cwd=v.ROOT,
                env={**v.ENV,"NODE_EXTRA_CA_CERTS":str(v.CA)},stdout=driver_log,stderr=driver_log)
            v.eventually(lambda:(v.manage.STATE/"load-players-ready.json").exists() or driver.poll() is not None,lambda x:x,timeout=180)
            if driver.poll() is not None:raise RuntimeError("Browser TLS/profile setup failed; inspect private driver log")
            if not all(p["connections"] and p["downTlsBytes"] for p in network.snapshot()["players"]):
                raise RuntimeError("Every browser player must use its own CONNECT tunnel during preflight")
            print("Players prepared with explicit NSS CA; 60 s warm-up",flush=True)
            # All five sources, players, API mix and Chat rate participate in warm-up.
            resources=Metrics()
            warm=time.monotonic()+2;warm_end=warm+60;start=warm_end+1;end=start+duration
            network.begin(start,end)
            stats["warmupDrainSeconds"]=1
            warm_utc=int(time.time()*1000+2000)
            private_json(v.manage.STATE/"load-start.json",{"warmupStartUtcMs":warm_utc,"startUtcMs":warm_utc+61000,"durationSeconds":duration})
            def sender(index,ws,begin,finish):
                slot=0
                while (due:=begin+index*.05+slot*1.25)<finish:
                    if stop.wait(max(0,due-time.monotonic())):return
                    now=time.monotonic();key=str(uuid.uuid4())
                    with lock:
                        if now-due>.1:stats["chatScheduleLate"]+=1
                        sent[key]=now;stats["chatSent"]+=1
                    try:ws.send(json.dumps({"type":"message.send","clientMessageId":key,"text":key}))
                    except Exception:failure("chat-send","transport")
                    slot+=1
            warm_threads=[]
            for i,ws in enumerate(sockets):
                thread=threading.Thread(target=sender,args=(i,ws,warm,warm_end),daemon=True);thread.start();warm_threads.append(thread)
            with ThreadPoolExecutor(max_workers=32) as pool:
                futures=[]
                for slot in range(600):
                    time.sleep(max(0,warm+slot/10-time.monotonic()));futures.append(pool.submit(request,slot))
                for future in futures:future.result()
                for thread in warm_threads:thread.join(timeout=2)
                time.sleep(max(0,start-time.monotonic()))
                # Startup transients stay visible in a separate non-measured report. Require a healthy tail.
                stats["warmup"]={"failures":list(stats["failures"]),"chatSent":stats["chatSent"],
                                 "chatAck":stats["chatAck"],"chatDelivery":stats["chatDelivery"]}
                if any(x["observedAtMonotonic"]>=warm_end-10 for x in stats["failures"]):
                    raise RuntimeError("Warm-up still unhealthy in its last ten seconds; measurement not started")
                with lock:
                    stats["failures"]=[]
                    stats["api"]={};stats["chatSent"]=stats["chatAck"]=stats["chatDelivery"]=0
                    stats["chatAckMs"]=[];stats["chatDeliveryMs"]=[]
                    stats["chatScheduleLate"]=stats["chatDuplicates"]=stats["chatSequenceGaps"]=0
                    sent.clear();acked.clear();delivered.clear()
                resources.begin(start,end)
                for i,ws in enumerate(sockets):
                    thread=threading.Thread(target=sender,args=(i,ws,start,end),daemon=True);thread.start();threads.append(thread)
                futures=[]
                print("Measured run started: "+stats["mode"],flush=True)
                for slot in range(duration*10):
                    time.sleep(max(0,start+slot/10-time.monotonic()))
                    if time.monotonic()-(start+slot/10)>.1:stats["apiScheduleLate"]+=1
                    futures.append(pool.submit(request,slot))
                for future in futures:future.result()
                time.sleep(max(0,end+3-time.monotonic()))
            driver.wait(timeout=60)
        stats["playerReport"]=json.loads((v.manage.STATE/"load-players-report.json").read_text())
        stats["network"]=network.snapshot()
        stats["network"]["allPlayersUsedTunnelDuringMeasurement"]=all(
            p["measuredDownTlsBytes"] and p["measuredUpTlsBytes"] for p in stats["network"]["players"])
        stats["apiSamplesExpected"]=duration*10;stats["chatSamplesExpected"]=duration*20
        for metric in stats["api"].values():
            metric["p95Ms"]=percentile(metric.pop("ms"))
            metric["errorRate"]=(metric["attempts"]-metric["success"])/metric["attempts"]
        stats["chatAckP95Ms"]=percentile(stats.pop("chatAckMs"));stats["chatDeliveryP95Ms"]=percentile(stats.pop("chatDeliveryMs"))
        stats["resources"]=resources.finish(duration*20)
        frames=stats["playerReport"]["firstFramesMs"]
        stats["firstFrameMaxMs"]=max(frames,default=None)
        stats["hlsAverageMbps"]=stats["playerReport"]["hlsBytes"]*8/duration/1_000_000
        stats["nominalThresholdsPass"]=sum(m["attempts"] for m in stats["api"].values())==duration*10 and \
            stats["network"]["allPlayersUsedTunnelDuringMeasurement"] and \
            stats["resources"]["authorization"]["budgetsPass"] and not stats["resources"]["errors"] and \
            stats["playerReport"]["leaseContinuityPass"] and not stats["failures"] and not stats["playerReport"]["failures"] and \
            stats["apiScheduleLate"]==stats["chatScheduleLate"]==stats["chatDuplicates"]==stats["chatSequenceGaps"]==0 and \
            stats["chatSent"]==stats["chatAck"]==stats["chatDelivery"]==duration*20 and \
            len(frames)==players and max(frames)<=5000 and stats["playerReport"]["leaseCreates"]==players and \
            all(m["attempts"]==m["success"] and m["p95Ms"]<=2000 for m in stats["api"].values()) and \
            stats["chatDeliveryP95Ms"] is not None and stats["chatDeliveryP95Ms"]<1000
        stats["acceptancePass"]=not diagnostic and stats["nominalThresholdsPass"]
    except Exception as error:
        failure("runner",type(error).__name__+": "+str(error).splitlines()[0]);stats["acceptancePass"]=False
    finally:
        stop.set()
        if resources:resources.stop.set()
        for ws in sockets:
            try:ws.close(timeout=1)
            except Exception:pass
        for thread in threads:thread.join(timeout=2)
        if driver and driver.poll() is None:
            driver.terminate()
            try:driver.wait(timeout=10)
            except subprocess.TimeoutExpired:driver.kill();driver.wait()
        if network:
            try:
                stats.setdefault("network",network.snapshot())
                network.close()
                stats["network"]["closed"]=True
            except Exception:
                failure("network","Tunnel cleanup/capture failed");stats["acceptancePass"]=False;stats["nominalThresholdsPass"]=False
        for source in sources:
            source.terminate()
            try:source.wait(timeout=6)
            except subprocess.TimeoutExpired:source.kill();source.wait(timeout=3)
        for owner,config,sid in zip(owners,configs,sessions):
            try:owner.call("/api/streams/sessions/"+sid,"DELETE",expect=204)
            except Exception:pass
        if log:log.close()
        # Preserve useful summaries even when the browser driver fails before normal aggregation.
        for metric in stats["api"].values():
            if "ms" in metric:
                metric["p95Ms"]=percentile(metric.pop("ms"))
                metric["errorRate"]=(metric["attempts"]-metric["success"])/metric["attempts"]
        for name,target in (("chatAckMs","chatAckP95Ms"),("chatDeliveryMs","chatDeliveryP95Ms")):
            if name in stats:stats[target]=percentile(stats.pop(name))
        if resources and hasattr(resources,"start") and "resources" not in stats:
            try:stats["resources"]=resources.finish(duration*20)
            except Exception:failure("metrics","Incomplete resource/authorization capture")
        stats["recordedAtUtc"]=datetime.now(timezone.utc).isoformat()
        stats["host"]={"os":platform.platform(),"machine":platform.machine(),"logicalCpu":os.cpu_count()}
        stats["baseCommit"]=v.manage.run(["git","rev-parse","HEAD"],cwd=v.ROOT).strip()
        digest=hashlib.sha256()
        for relative in v.manage.run(["git","ls-files","--cached","--others","--exclude-standard"],cwd=v.ROOT).splitlines():
            path=v.ROOT/relative
            if path.is_file():digest.update(relative.encode());digest.update(path.read_bytes())
        stats["sourceTreeSha256"]=digest.hexdigest()
        private_json(v.manage.STATE/"load-report.json",stats)
    print(("PASS" if stats.get("nominalThresholdsPass") else "FAIL")+": "+stats["mode"]+"; private report infra/p1/.state/load-report.json",flush=True)
    return stats

if __name__=="__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--confirm-disposable",action="store_true");parser.add_argument("--diagnostic",action="store_true")
    args=parser.parse_args()
    if not args.confirm_disposable:parser.error("--confirm-disposable required for isolated fixture SQL and load")
    result=run(args.diagnostic)
    raise SystemExit(0 if result.get("nominalThresholdsPass") else 1)
