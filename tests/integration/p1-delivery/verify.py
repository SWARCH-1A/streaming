"""Real TLS/recovery acceptance checks against the own persistent P1 profile.

Use a disposable local profile. Test-only SQL belongs to this fixture, never to a runtime.
Secrets and payloads stay in RAM or private .state files; output contains only scenario summaries.
"""
from __future__ import annotations
import argparse
import hashlib
import http.client
import http.cookiejar
import importlib.util
import json
import os
from pathlib import Path
import secrets
import ssl
import subprocess
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import websocket

ROOT = Path(__file__).resolve().parents[3]
def module(name,path):
    spec=importlib.util.spec_from_file_location(name,path)
    value=importlib.util.module_from_spec(spec); spec.loader.exec_module(value); return value
manage=module("p1manage",ROOT/"infra/p1/manage.py")
contracts=module("p1contracts",ROOT/"contracts/generate.py")
BUNDLE,_=contracts.load()
ENV=manage.environment()
ORIGIN=ENV["WEB_ORIGIN"]
CA=manage.STATE/"ca.crt"
TLS=ssl.create_default_context(cafile=str(CA))

class Client:
    def __init__(self,timeout=8):
        self.timeout=timeout
        self.cookies=http.cookiejar.CookieJar()
        self.http=urllib.request.build_opener(urllib.request.ProxyHandler({}),urllib.request.HTTPSHandler(context=TLS),urllib.request.HTTPCookieProcessor(self.cookies))
    def call(self,path,method="GET",body=None,headers=None,expect=200,schema=None,raw=False):
        hdr={"Accept":"*/*" if raw else "application/json","Origin":ORIGIN,"X-Request-ID":str(uuid.uuid4()),**(headers or {})}
        if body is not None and not isinstance(body,bytes):
            body=json.dumps(body).encode(); hdr["Content-Type"]="application/json"
        req=urllib.request.Request(ORIGIN+path,method=method,headers=hdr,data=body)
        try:r=self.http.open(req,timeout=self.timeout)
        except urllib.error.HTTPError as error:r=error
        with r:
            data=r.read()
            if r.status!=expect:raise AssertionError(f"{method} {path.split('?')[0]} expected {expect}, got {r.status}")
            if raw:return data
            value=json.loads(data) if data else None
            if schema:contracts.validate_payload(BUNDLE,schema,value,public=schema not in ("StreamCreated","IngestKey","ViewerLease","Csrf"))
            return value
    def core(self,path,method="POST",body=None,headers=None,expect=200,schema=None):
        csrf=self.call("/api/identity/csrf",schema="Csrf")
        return self.call(path,method,body,{**(headers or {}),csrf["headerName"]:csrf["token"]},expect,schema)
    def login(self,handle,password):return self.core("/api/identity/sessions",body={"login":handle,"password":password})
    def credential(self):return next(c.value for c in self.cookies if c.name=="stream_session")
    def upload(self,path,file):
        boundary="p1_"+uuid.uuid4().hex
        body=(f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"fixture.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n").encode()+file.read_bytes()+f"\r\n--{boundary}--\r\n".encode()
        csrf=self.call("/api/identity/csrf",schema="Csrf")
        return self.call(path,"POST",body,{"Content-Type":"multipart/form-data; boundary="+boundary,csrf["headerName"]:csrf["token"]},201,"Upload")


def compose(*args):return manage.command(ENV,*args)
def sql(statement):
    # Values below are fixture UUIDs, fixed schema identifiers, or sanitized alphanumeric handles.
    return compose("exec","-T","postgres","psql","-U","core","-d","core","-v","ON_ERROR_STOP=1","-At","-c",statement).strip()
def replay_callback(sid):
    # Read only the own fixture outbox: the envelope was produced by the real encoder/adapter.
    assert sid.startswith("ses_")
    sid="ses_"+str(uuid.UUID(sid[4:]))
    payload=compose("exec","-T","db","psql","-U","streaming","-d","streaming_media","-v","ON_ERROR_STOP=1","-At","-c",
        "SELECT payload::text FROM public.media_callback_outbox WHERE payload->>'sessionId'='"+sid+"' AND kind='source-connected' AND delivered_at IS NOT NULL ORDER BY delivered_at DESC LIMIT 1").strip()
    envelope=json.loads(payload)
    # Credentials expand only inside the container and never enter host argv or printed output.
    command=["docker","compose","--env-file",str(manage.STATE/"environment.env"),"-p",manage.PROJECT,
        "-f",str(ROOT/"infra/p1/compose.yaml"),"exec","-T","streaming","sh","-c",
        'curl --fail --silent --cacert /run/secrets/ca.crt -H "Content-Type: application/json" -H "Authorization: Bearer $STREAMING_MEDIA_ADAPTER_SERVICE_TOKEN" -X POST "https://localhost:8091/internal/streaming/sessions/'+sid+'/source-connected" -d @-']
    for discard in (True,False):
        result=subprocess.run(command,input=payload,text=True,capture_output=True,env=ENV,timeout=15)
        if result.returncode:raise AssertionError("Real callback replay failed")
        if not discard:
            reply=json.loads(result.stdout)
            assert reply["accepted"] and reply["duplicate"] and reply["eventId"]==envelope["eventId"]
def eventually(read,predicate,timeout=30):
    end=time.monotonic()+timeout
    while time.monotonic()<end:
        try:
            result=read()
            if predicate(result):return result
        except (OSError,urllib.error.URLError,AssertionError,RuntimeError):pass
        time.sleep(.25)
    raise AssertionError("Scenario did not converge within its deadline")
def register(client,handle,password,key):
    return client.core("/api/identity/registrations",body={"email":handle+"@example.test","handle":handle,"password":password},headers={"Idempotency-Key":key},expect=201,schema="Registration")
def connect(client,sid):
    return websocket.create_connection(ORIGIN.replace("https:","wss:")+"/realtime/chat/sessions/"+sid,origin=ORIGIN,
        cookie="stream_session="+client.credential(),sslopt={"ca_certs":str(CA)},timeout=8,http_proxy_host=None)
def frame(ws,kind,client_id=None,message_id=None):
    end=time.monotonic()+10
    if not hasattr(ws,"p1_pending"):ws.p1_pending=[]
    def matches(value):
        return value["type"]==kind and (client_id is None or value.get("clientMessageId")==client_id) and (message_id is None or value.get("message",{}).get("messageId")==message_id)
    while time.monotonic()<end:
        for i,value in enumerate(ws.p1_pending):
            if matches(value):return ws.p1_pending.pop(i)
        value=json.loads(ws.recv())
        if matches(value):return value
        if value["type"]=="error":raise AssertionError("Unexpected Chat error: "+value["code"])
        ws.p1_pending.append(value)
    raise AssertionError("Expected WS frame missing")
def publish(config,log,media_fixture=None):
    url=config["ingestUrl"] if "ingestUrl" in config else "rtmps://localhost:"+ENV.get("P1_RTMPS_PORT","11936")+"/live/"+config["streamId"]+"?user=publisher&pass="+urllib.parse.quote(config["streamKey"],safe="")
    cmd=["ffmpeg","-hide_banner","-loglevel","error"]
    if media_fixture:
        cmd += ["-re","-stream_loop","-1","-i",str(media_fixture),"-c","copy"]
    else:
        cmd += ["-re","-f","lavfi","-i","testsrc2=size=1280x720:rate=30","-f","lavfi","-i","sine=frequency=1000:sample_rate=48000",
         "-c:v","libx264","-threads","2","-preset","ultrafast","-tune","zerolatency","-g","30","-b:v","2200k","-maxrate","2300k","-bufsize","4600k","-pix_fmt","yuv420p","-c:a","aac","-b:a","96k"]
    cmd += ["-tls_verify","1","-ca_file",str(CA),"-f","flv",url]
    return Publisher(cmd,log)

class Publisher:
    """External encoder with OBS-like reconnect; the ingest key/config remain unchanged."""
    def __init__(self,cmd,log):
        self.cmd,self.log,self.process=cmd,log,None
        self.stopped=threading.Event()
        self.thread=threading.Thread(target=self.run,daemon=True);self.thread.start()
    def run(self):
        while not self.stopped.is_set():
            self.process=subprocess.Popen(self.cmd,cwd=ROOT,env={**ENV,"SSL_CERT_FILE":str(CA)},stdout=self.log,stderr=self.log)
            while self.process.poll() is None:
                if self.stopped.wait(.1):self.process.terminate();break
            try:self.process.wait(timeout=5)
            except subprocess.TimeoutExpired:self.process.kill();self.process.wait()
            self.stopped.wait(.5)
    def terminate(self):self.stopped.set()
    def wait(self,timeout):
        self.thread.join(timeout)
        if self.thread.is_alive():raise subprocess.TimeoutExpired("P1 publisher",timeout)
    def kill(self):
        self.stopped.set()
        if self.process and self.process.poll() is None:self.process.kill()
def decoded(sid):
    # HLS opens nested HTTPS playlists/segments: OpenSSL must trust the same CA there too.
    manage.run(["ffmpeg","-hide_banner","-loglevel","error","-rw_timeout","8000000","-tls_verify","1","-ca_file",str(CA),"-i",f"{ORIGIN}/hls/{sid}/index.m3u8","-frames:v","1","-f","null","-"],timeout=15,env={**ENV,"SSL_CERT_FILE":str(CA)})


def smoke():
    client=Client(); anon=Client()
    eventually(lambda:anon.call("/api/taxonomy",schema="Taxonomy"),lambda v:len(v["categories"])>=7)
    # Trust must be explicit, and plaintext must not serve the profile.
    try:urllib.request.urlopen(ORIGIN+"/api/taxonomy",timeout=3);raise AssertionError("Untrusted CA accepted")
    except urllib.error.URLError:pass
    try:urllib.request.urlopen(ORIGIN.replace("https:","http:")+"/api/taxonomy",timeout=3);raise AssertionError("Plaintext accepted")
    except urllib.error.HTTPError as e:assert e.code==400
    except (urllib.error.URLError,ConnectionError):pass
    handle="tls_"+uuid.uuid4().hex[:14]; password=secrets.token_urlsafe(32); key=str(uuid.uuid4())
    # Fail after account/profile writes but before the channel insert, then recover the same intent.
    sql("CREATE OR REPLACE FUNCTION channels.p1_test_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF EXISTS(SELECT 1 FROM identity.accounts WHERE user_id=NEW.owner_user_id AND handle='"+handle+"') THEN RAISE EXCEPTION 'fixture rollback'; END IF; RETURN NEW; END $$; CREATE TRIGGER p1_test_fail BEFORE INSERT ON channels.channels FOR EACH ROW EXECUTE FUNCTION channels.p1_test_fail();")
    try:
        csrf=client.call("/api/identity/csrf")
        client.call("/api/identity/registrations","POST",{"email":handle+"@example.test","handle":handle,"password":password},{csrf["headerName"]:csrf["token"],"Idempotency-Key":key},503)
        assert sql("SELECT count(*) FROM identity.accounts WHERE handle='"+handle+"'")=="0"
    finally:sql("DROP TRIGGER IF EXISTS p1_test_fail ON channels.channels; DROP FUNCTION IF EXISTS channels.p1_test_fail()")
    # Drop the entire HTTP response after sending. The durable commit is observed only by this fixture.
    csrf=client.call("/api/identity/csrf")
    conn=http.client.HTTPSConnection(urllib.parse.urlsplit(ORIGIN).netloc,context=TLS,timeout=8)
    conn.request("POST","/api/identity/registrations",json.dumps({"email":handle+"@example.test","handle":handle,"password":password}),
        {"Content-Type":"application/json","Origin":ORIGIN,"Idempotency-Key":key,csrf["headerName"]:csrf["token"],
         "Cookie":"; ".join(c.name+"="+c.value for c in client.cookies)})
    conn.close()
    eventually(lambda:sql("SELECT count(*) FROM identity.accounts WHERE handle='"+handle+"'"),lambda v:v=="1")
    registration=register(client,handle,password,key)
    assert register(client,handle,password,key)==registration
    print("PASS registration rollback and same-intent retry",flush=True)
    client.login(handle,password)
    channel=client.call("/api/channels/by-handle/"+handle,schema="ChannelBootstrap")
    image=ROOT/"apps/web/public/images/studio-1.jpg"
    avatar=client.upload("/api/profile/me/avatar-uploads",image)
    client.core("/api/profile/me","PATCH",{"displayName":"Autora TLS","avatarUploadId":avatar["uploadId"]},schema="Profile")
    banner=client.upload("/api/channels/"+channel["channel"]["channelId"]+"/banner-uploads",image)
    client.core("/api/channels/"+channel["channel"]["channelId"],"PATCH",{"description":"Canal TLS persistente","bannerUploadId":banner["uploadId"]})
    channel=client.call("/api/channels/by-handle/"+handle,schema="ChannelBootstrap")
    avatar_bytes=anon.call(channel["profile"]["avatarUri"],raw=True)
    banner_bytes=anon.call(channel["channel"]["bannerUri"],raw=True)
    print("PASS TLS uploads and public image bytes",flush=True)
    catalog=anon.call("/api/taxonomy")
    config=client.call("/api/channels/"+channel["channel"]["channelId"]+"/streams","POST",{"title":"TLS integrado","categoryId":catalog["categories"][0]["id"],"tagIds":[]},{"Idempotency-Key":str(uuid.uuid4())},201,"StreamCreated")
    log_path=manage.STATE/"smoke-media.log"
    with open(log_path,"w") as log:
        os.chmod(log_path,0o600)
        source=publish(config,log); ws=None
        try:
            live=eventually(lambda:anon.call("/api/streams/"+config["streamId"],schema="PublicStream"),lambda v:v["availability"]=="PLAYABLE")
            sid=live["sessionId"];decoded(sid)
            print("PASS RTMPS source and decoded HTTPS HLS",flush=True)
            before=anon.call("/api/streams/sessions/"+sid)
            replay_callback(sid)
            after=anon.call("/api/streams/sessions/"+sid)
            assert after["status"]=="LIVE" and after["sessionVersion"]==before["sessionVersion"]
            print("PASS real delivered callback replay with discarded response leaves one transition",flush=True)
            ws=connect(client,sid);frame(ws,"chat.ready")
            message={"type":"message.send","clientMessageId":str(uuid.uuid4()),"text":"Persistencia TLS"}
            ws.send(json.dumps(message));ack=frame(ws,"message.accepted",message["clientMessageId"])
            frame(ws,"message.created")
            query=json.loads((ROOT/"contracts/generated/graphql-queries.json").read_text())[0]["query"]
            def projected_count(expected):
                result=anon.call("/api/discovery/graphql","POST",{"query":query,"variables":{"limit":20}})
                assert not result.get("errors")
                return any(item["sessionId"]==sid and item["viewerCount"]==expected and item["viewerCountFresh"]
                           for item in result["data"]["streams"]["items"])
            started=time.monotonic()
            lease=anon.call("/api/streams/sessions/"+sid+"/viewer-leases","POST",headers={"Idempotency-Key":str(uuid.uuid4())},expect=201,schema="ViewerLease")
            eventually(lambda:projected_count(1),lambda x:x,timeout=max(0,5-(time.monotonic()-started)))
            assert time.monotonic()-started<=5
            started=time.monotonic()
            anon.call("/api/streams/viewer-leases/"+lease["leaseId"],"DELETE",headers={"Authorization":"ViewerLease "+lease["leaseToken"]},expect=204)
            eventually(lambda:projected_count(0),lambda x:x,timeout=max(0,5-(time.monotonic()-started)))
            assert time.monotonic()-started<=5
            print("PASS real viewer changes reach fresh SQL Discovery within 5 s",flush=True)
            for service in ("core","chat","chat-redis"):
                if ws and service in ("chat","chat-redis"):
                    ws.close();ws=None
                compose("restart",service)
                eventually(lambda:client.call("/api/profile/me"),lambda v:v["userId"]==registration["userId"])
                eventually(lambda:anon.call("/api/chat/sessions/"+sid+"/messages"),lambda v:len(v["items"])==1)
                assert anon.call(channel["profile"]["avatarUri"],raw=True)==avatar_bytes
                assert anon.call(channel["channel"]["bannerUri"],raw=True)==banner_bytes
                decoded(sid)
                print("PASS persisted data after restart: "+service,flush=True)
            ws=connect(client,sid);frame(ws,"chat.ready");ws.send(json.dumps(message));assert frame(ws,"message.accepted",message["clientMessageId"])==ack
            # Live media is independent of Core and Chat; fresh sends fail closed.
            compose("stop","core")
            decoded(sid)
            rejected={**message,"clientMessageId":str(uuid.uuid4())};ws.send(json.dumps(rejected));assert frame(ws,"error")["code"]=="CORE_UNAVAILABLE"
            compose("start","core");eventually(lambda:client.call("/api/profile/me"),lambda v:bool(v))
            assert len(anon.call("/api/chat/sessions/"+sid+"/messages")["items"])==1
            compose("restart","streaming")
            live=eventually(lambda:anon.call("/api/streams/"+config["streamId"]),lambda v:v["availability"]=="PLAYABLE" and v["sessionId"]!=sid)
            # SPEC-04 never extends/reconstructs an unverified monotonic clock on restart.
            assert anon.call("/api/streams/sessions/"+sid)["status"]=="ENDED"
            assert live["streamId"]==config["streamId"] and live["sessionId"]!=sid
            assert len(anon.call("/api/chat/sessions/"+sid+"/messages")["items"])==1
            sid=live["sessionId"];decoded(sid)
            print("PASS Streaming restart: stable config, old clock fenced, automatic new ingestion",flush=True)
            compose("restart","mediamtx")
            live=eventually(lambda:anon.call("/api/streams/"+config["streamId"]),lambda v:v["availability"]=="PLAYABLE")
            assert live["sessionId"]==sid
            eventually(lambda:decoded(sid) or True,lambda v:v)
            print("PASS MediaMTX restart: reconnect within the original grace",flush=True)
            client.call("/api/streams/sessions/"+sid,"DELETE",expect=204)
            eventually(lambda:anon.call("/api/streams/"+config["streamId"]),lambda v:v["status"]=="ENDED")
            print("PASS TLS: CA rejection/plaintext rejection, rollback+idempotency, real uploads, RTMPS→decoded HLS, leases, WS ACK/dedupe/AOF and independent Core/Chat/Redis/Streaming recovery")
        finally:
            if ws:ws.close()
            source.terminate()
            try:source.wait(timeout=5)
            except subprocess.TimeoutExpired:source.kill();source.wait()
            compose("start","core","chat","chat-redis","streaming")

if __name__=="__main__":
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument("--confirm-disposable",action="store_true")
    args=parser.parse_args()
    if not args.confirm_disposable:parser.error("--confirm-disposable required for fixture SQL/failure injection")
    smoke()
