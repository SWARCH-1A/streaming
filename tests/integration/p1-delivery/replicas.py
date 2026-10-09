"""Real two-Core/two-Chat checks with explicit per-replica TLS routing in the own fixture.

Temporary public test prefixes exist only in the private generated Caddyfile and are restored in finally.
No service IP, test route, password or alternate contract is committed into the application.
"""
from __future__ import annotations
import argparse
from concurrent.futures import ThreadPoolExecutor
import json
import os
import re
import time
import uuid
import websocket
import verify as v
from seed import seed


def run():
    fixture=seed()
    routes=[]
    for service,port in (("core",8081),("chat",8085)):
        ids=v.compose("ps","-q",service).splitlines()
        if len(ids)!=2:raise RuntimeError("Use manage.py up --replicas 2 first")
        for i,ident in enumerate(ids,1):
            inspected=json.loads(v.manage.run(["docker","inspect",ident]))[0]
            if inspected["Config"]["Labels"]["com.docker.compose.project"]!=v.manage.PROJECT:raise RuntimeError("Foreign container")
            name=inspected["Name"].lstrip("/")
            if not re.fullmatch(re.escape(v.manage.PROJECT)+r"-(core|chat)-[12]",name):raise RuntimeError("Unexpected replica identity")
            routes.append(f"""    handle_path /__p1test/{service}{i}/* {{
      reverse_proxy https://{name}:{port} {{
        import proxy_headers
        transport http {{
          tls_server_name {service}
          tls_trust_pool file /run/secrets/ca.crt
          response_header_timeout 5s
        }}
      }}
    }}
""")
    path=v.manage.STATE/"Caddyfile";original=path.read_text()
    owner=v.Client();sockets=[];source=None;sid=None;log=None
    try:
        path.write_text(original.replace("  route {","  route {\n"+"".join(routes),1))
        v.compose("exec","-T","web","caddy","validate","--config","/etc/caddy/Caddyfile")
        v.compose("restart","web")
        v.eventually(lambda:v.Client().call("/__p1test/core2/api/taxonomy"),lambda x:bool(x))
        owner.core("/__p1test/core1/api/identity/sessions",body={"login":"p1_load_099","password":fixture["password"]})
        profile=owner.call("/__p1test/core2/api/profile/me")
        assert profile["userId"]=="usr_p1load_099"
        name="Persona réplica "+uuid.uuid4().hex[:8]
        owner.core("/__p1test/core1/api/profile/me","PATCH",{"displayName":name})
        assert owner.call("/__p1test/core2/api/profile/me")["displayName"]==name
        image=v.ROOT/"apps/web/public/images/studio-1.jpg"
        upload=owner.upload("/__p1test/core1/api/profile/me/avatar-uploads",image)
        profile=owner.core("/__p1test/core1/api/profile/me","PATCH",{"avatarUploadId":upload["uploadId"]})
        assert owner.call("/__p1test/core2"+profile["avatarUri"],raw=True)==image.read_bytes()
        catalog=owner.call("/api/taxonomy")
        channel=owner.call("/api/channels/by-handle/p1_load_099")["channel"]
        upload=owner.upload("/__p1test/core1/api/channels/"+channel["channelId"]+"/banner-uploads",image)
        owner.core("/__p1test/core1/api/channels/"+channel["channelId"],"PATCH",{"bannerUploadId":upload["uploadId"]})
        channel=owner.call("/__p1test/core2/api/channels/by-handle/p1_load_099")["channel"]
        assert owner.call("/__p1test/core2"+channel["bannerUri"],raw=True)==image.read_bytes()
        print("PASS two Core replicas: shared session, fresh profile, avatar/banner bytes",flush=True)
        path_config="/api/channels/"+channel["channelId"]+"/streams"
        try:
            config=owner.call(path_config)
            key=owner.call("/api/streams/"+config["streamId"]+"/ingest-keys/rotate","POST")
            config["streamKey"]=key["streamKey"]
        except AssertionError as e:
            if "got 404" not in str(e):raise
            config=owner.call(path_config,"POST",{"title":"Réplicas TLS","categoryId":catalog["categories"][0]["id"],"tagIds":[]},{"Idempotency-Key":str(uuid.uuid4())},201)
        log_path=v.manage.STATE/"replicas-media.log";log=open(log_path,"w");os.chmod(log_path,0o600)
        source=v.publish(config,log)
        live=v.eventually(lambda:owner.call("/api/streams/"+config["streamId"]),lambda x:x["availability"]=="PLAYABLE");sid=live["sessionId"]
        for i in (1,2):
            ws=websocket.create_connection(v.ORIGIN.replace("https:","wss:")+f"/__p1test/chat{i}/realtime/chat/sessions/"+sid,
                origin=v.ORIGIN,cookie="stream_session="+owner.credential(),sslopt={"ca_certs":str(v.CA)},timeout=8,http_proxy_host=None)
            v.frame(ws,"chat.ready");sockets.append(ws)
        first={"type":"message.send","clientMessageId":str(uuid.uuid4()),"text":"Fan-out entre réplicas"}
        sockets[1].send(json.dumps(first));ack=v.frame(sockets[1],"message.accepted",first["clientMessageId"])
        created=v.frame(sockets[0],"message.created")
        assert created["message"]["messageId"]==ack["messageId"]
        sockets[0].send(json.dumps(first));assert v.frame(sockets[0],"message.accepted",first["clientMessageId"])==ack
        denied={**first,"clientMessageId":str(uuid.uuid4())}
        sockets[0].send(json.dumps(denied));assert v.frame(sockets[0],"error")["code"]=="RATE_LIMITED"
        time.sleep(1.05)
        name="Autor fresco "+uuid.uuid4().hex[:8]
        owner.core("/__p1test/core2/api/profile/me","PATCH",{"displayName":name})
        second={**first,"clientMessageId":str(uuid.uuid4()),"text":"Contexto fresco entre réplicas"}
        sockets[0].send(json.dumps(second));ack2=v.frame(sockets[0],"message.accepted",second["clientMessageId"])
        received=v.frame(sockets[1],"message.created",message_id=ack2["messageId"])
        assert received["message"]["author"]["displayName"]==name and ack2["sequence"]==ack["sequence"]+1
        for i in (1,2):
            history=owner.call(f"/__p1test/chat{i}/api/chat/sessions/{sid}/messages")
            assert len(history["items"])==2
        print("PASS two Chat replicas: fan-out, shared quota/sequence/dedupe and fresh Core author",flush=True)
        # Five failures split across replicas must share the identifier's SQL quota.
        negative="absent_"+uuid.uuid4().hex[:12]
        for i in range(5):owner.core(f"/__p1test/core{i%2+1}/api/identity/sessions",body={"login":negative,"password":"fictitious wrong password"},expect=401)
        owner.core("/__p1test/core2/api/identity/sessions",body={"login":negative,"password":"fictitious wrong password"},expect=429)
        print("PASS shared Core SQL login quota across replicas",flush=True)
        query=json.loads((v.ROOT/"contracts/generated/graphql-queries.json").read_text())[1]["query"]
        def discovery(index):
            try:
                v.Client().call(f"/__p1test/core{index%2+1}/api/discovery/graphql","POST",{"query":query,"variables":{"limit":1}})
                return True
            except AssertionError as error:
                if "got 429" not in str(error):raise
                return False
        with ThreadPoolExecutor(max_workers=16) as pool:admitted=list(pool.map(discovery,range(40)))
        assert not all(admitted),"Two replicas must share Discovery's burst instead of granting 20 each"
        print("PASS shared Discovery quota across both physical Core replicas",flush=True)
        v.decoded(sid)
    finally:
        for ws in sockets:ws.close(timeout=1)
        if source:
            source.terminate()
            try:source.wait(timeout=6)
            except Exception:source.kill()
        if sid:
            try:owner.call("/api/streams/sessions/"+sid,"DELETE",expect=204)
            except Exception:pass
        if log:log.close()
        path.write_text(original)
        v.compose("restart","web")
    print("PASS replica routing restored; public application contracts unchanged",flush=True)

if __name__=="__main__":
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument("--confirm-disposable",action="store_true")
    args=parser.parse_args()
    if not args.confirm_disposable:parser.error("--confirm-disposable required for isolated fixture setup")
    run()
