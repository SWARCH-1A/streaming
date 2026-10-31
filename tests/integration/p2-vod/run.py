#!/usr/bin/env python3
"""Disposable PostgreSQL/MinIO/RabbitMQ + actual FFmpeg media. No ports or existing volumes touched.

Core is a test port in this suite; run VodAccessIT separately. This does not certify OBS/P2-08,
external S3, TLS end to end, Chat durable replay or real audiovisual playback over 60 seconds.
"""
from __future__ import annotations
import json
from pathlib import Path
import runpy
import subprocess
import tempfile
import time
import uuid

ROOT = Path(__file__).resolve().parents[3]
RABBIT = "rabbitmq:4.3.7-management@sha256:79fb281ef7e0a15e3c5e0e12e66e944cebbf14a129493ef464369abd2c48d01a"


def command(*args, capture=False):
    return subprocess.run(args, cwd=ROOT, check=True, text=True,
                          stdout=subprocess.PIPE if capture else None).stdout


def wait(args, timeout=90):
    deadline=time.monotonic()+timeout
    while time.monotonic()<deadline:
        try:
            result=subprocess.run(args,cwd=ROOT,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,timeout=8)
            if result.returncode==0:return
        except subprocess.TimeoutExpired:pass
        time.sleep(1)
    if args[:2]==['docker','exec']:
        # Only this runner's disposable fixture; retain useful failure diagnostics.
        subprocess.run(['docker','logs','--tail','60',args[2]],cwd=ROOT,check=False)
    raise RuntimeError("Fixture did not become ready")


def main():
    name="streaming-vod-test-"+uuid.uuid4().hex[:10]
    label="streaming.vod.test="+name
    containers=[]
    try:
        command("docker","build","-t","streaming-vod-minio","-f","infra/media/Dockerfile.minio",".")
        command("docker","build","-t","streaming-vod-test","-f","services/vod/Dockerfile.test","services/vod")
        command("docker","build","-t","streaming-vod-media-test","--target","test","-f","infra/media/Dockerfile.mediamtx",".")
        command("docker","build","-t","streaming-vod-media","-f","infra/media/Dockerfile.mediamtx",".")
        command("docker","network","create","--label",label,name)
        command("docker","volume","create","--label",label,name+"-cache")
        command("docker","volume","create","--label",label,name+"-native")
        command("docker","run","--rm","-u","0","-v",name+"-cache:/tmp","streaming-vod-test","chown","1000:1000","/tmp")
        with tempfile.TemporaryDirectory(prefix="streaming-vod-broker-") as folder:
            directory=Path(folder);directory.chmod(0o755)
            topology=runpy.run_path(str(ROOT/"infra/broker/topology.py"))
            roles=["operator","vod","streaming","core","chat","notifications","recovery-email"]
            (directory/"definitions.json").write_text(json.dumps(topology["definitions"]({role:"fixture-only-broker-password" for role in roles})))
            (directory/"rabbitmq.conf").write_text("listeners.tcp.default = 5672\ndefinitions.import_backend = local_filesystem\ndefinitions.local.path = /fixture/definitions.json\n")
            def start(suffix,image,*args,command_args=()):
                container=name+suffix;containers.append(container)
                command("docker","run","-d","--name",container,"--label",label,"--network",name,*args,image,*command_args)
                return container
            db=start("-db","postgres:18-alpine","-e","POSTGRES_DB=vod_test","-e","POSTGRES_USER=vod_fixture","-e","POSTGRES_PASSWORD=fixture-only-vod-password")
            s3=start("-s3","streaming-vod-minio","-e","MINIO_ROOT_USER=vodfixture","-e","MINIO_ROOT_PASSWORD=fixture-only-vod-password","-e","MC_HOST_fixture=http://vodfixture:fixture-only-vod-password@localhost:9000")
            broker=start("-broker",RABBIT,"--hostname",name+"-broker","-e","RABBITMQ_SERVER_ADDITIONAL_ERL_ARGS=+S 2:2 +A 2","-e","RABBITMQ_CTL_ERL_ARGS=+S 1:1 +A 1","-v",str(directory)+":/fixture:ro","-v",str(directory/"rabbitmq.conf")+":/etc/rabbitmq/rabbitmq.conf:ro")
            wait(["docker","exec",db,"pg_isready","-U","vod_fixture","-d","vod_test"])
            wait(["docker","exec",s3,"mc","ready","fixture"])
            wait(["docker","exec",broker,"rabbitmq-diagnostics","-q","check_running"])
            command("docker","run","--rm","--network",name,
                    "-e",f"VOD_TEST_DATABASE_URL=postgres://vod_fixture:fixture-only-vod-password@{db}:5432/vod_test?sslmode=disable",
                    "-e",f"VOD_TEST_S3_ENDPOINT=http://{s3}:9000",
                    "-e",f"VOD_TEST_BROKER_URL=amqp://vod:fixture-only-broker-password@{broker}:5672/streaming-p2",
                    "-e",f"VOD_TEST_BROKER_OPERATOR_URL=amqp://operator:fixture-only-broker-password@{broker}:5672/streaming-p2",
                    "-v",str(ROOT/"services/vod")+":/src:ro","-v",name+"-cache:/tmp",
                    "streaming-vod-test","sh","-c","go test -count=1 -v ./... && go vet ./...")
            command("docker","exec",db,"createdb","-U","vod_fixture","vod_native_test")
            command("docker","run","--rm","-u","0","-v",name+"-cache:/tmp","-v",name+"-native:/data/native","streaming-vod-test","chown","-R","10001:10001","/tmp","/data/native")
            session="ses_00000000-0000-4000-8000-000000000014"
            crashed="ses_00000000-0000-4000-8000-000000000015"
            authority=name+"-authority"
            config=f"""logDestinations: []
authMethod: http
authHTTPAddress: http://{authority}:8090/auth
authHTTPExclude: []
api: true
rtsp: false
rtmp: true
hls: false
webrtc: false
srt: false
moq: false
pathDefaults:
  record: true
  recordFormat: mpegts
  recordPartDuration: 200ms
  recordSegmentDuration: 2s
  recordDeleteAfter: 0s
paths:
  '~^live/sk_[0-9a-f]{{64}}$': {{}}
"""
            (directory/"mediamtx.yml").write_text(config)
            start("-authority","streaming-vod-media-test","-v",str(ROOT/"tests/integration/p2-vod/native_authority.py")+":/authority.py:ro","--entrypoint","python3",command_args=("/authority.py",))
            engine=start("-media","streaming-vod-media","-e","MEDIA_NATIVE_RECORDING=true","-v",str(directory/"mediamtx.yml")+":/mediamtx.yml:ro","-v",name+"-native:/data/native")
            def native_check(script):
                return ["docker","run","--rm","--user","10001:10001","-v",name+"-native:/data/native","streaming-vod-media-test","python3","-c",script]
            def publish(path,duration,detached=False):
                options=["docker","run","--network",name,"--label",label]
                if detached:
                    source=name+"-source-"+path[-1];containers.append(source);options += ["-d","--name",source]
                else:options += ["--rm"]
                command(*options,"streaming-vod-test","ffmpeg","-nostdin","-v","error",
                        "-re","-f","lavfi","-i","testsrc=size=160x90:rate=25",
                        "-re","-f","lavfi","-i","sine=frequency=440:sample_rate=44100",
                        "-t",str(duration),"-c:v","libx264","-threads","1","-preset","ultrafast","-g","25","-bf","0","-pix_fmt","yuv420p",
                        "-c:a","aac","-f","flv",f"rtmp://{engine}:1935/{path}")
                if detached:return source
            # No VOD process is running while native Media captures both generations.
            wait(["docker","run","--rm","--network",name,"streaming-vod-media-test","python3","-c",f"from urllib.request import urlopen; urlopen('http://{engine}:9997/v3/info',timeout=3)"])
            publish("live/sk_"+"a"*64,5)
            time.sleep(1)  # Known reconnect gap, independent of Docker/encoder startup jitter.
            publish("live/sk_"+"a"*64,5)
            for generation in [1,2]:
                wait(native_check(f"import json; assert json.load(open('/data/native/{session}/{generation}/rend_source/index.json'))['closed']"))
            retired="ses_00000000-0000-4000-8000-000000000016"
            active=publish("live/sk_"+"c"*64,10,detached=True)
            wait(native_check(f"import json; assert len(json.load(open('/data/native/{retired}/1/rend_source/index.json'))['segments'])>=1"))
            command(*native_check(f"import os; f=open('/data/native/{retired}/retired','w'); f.write('VOD_DELETED\\n'); f.flush(); os.fsync(f.fileno()); f.close()"))
            wait(native_check(f"import json; d=json.load(open('/data/native/{retired}/1/rend_source/index.json')); assert d['closed'] and d['failure']=='CAPTURE_RETIRED'"))
            if command("docker","wait",active,capture=True).strip()!="0":raise RuntimeError("Capture retirement stopped the live publisher")
            publish("live/sk_"+"b"*64,10,detached=True)
            wait(native_check(f"import json; d=json.load(open('/data/native/{crashed}/1/rend_source/index.json')); assert len(d['segments'])>=2 and not d['closed']"))
            command("docker","kill","--signal","KILL",engine)
            command("docker","run","--rm","--user","10001:10001","--network",name,
                    "-e",f"VOD_TEST_NATIVE_DATABASE_URL=postgres://vod_fixture:fixture-only-vod-password@{db}:5432/vod_native_test?sslmode=disable",
                    "-e","VOD_TEST_NATIVE_DIR=/data/native",
                    "-e",f"VOD_TEST_S3_ENDPOINT=http://{s3}:9000",
                    "-e",f"VOD_TEST_BROKER_URL=amqp://vod:fixture-only-broker-password@{broker}:5672/streaming-p2",
                    "-v",str(ROOT/"services/vod")+":/src:ro","-v",name+"-cache:/tmp","-v",name+"-native:/data/native",
                    "streaming-vod-test","go","test","-count=1","-v","./cmd/media-recorder","-run","^TestNativeEngine")
    finally:
        for container in containers:
            result=subprocess.run(["docker","inspect","--format",'{{index .Config.Labels "streaming.vod.test"}}',container],capture_output=True,text=True)
            if result.returncode==0 and result.stdout.strip()==name:
                subprocess.run(["docker","rm","-fv",container],check=True,stdout=subprocess.DEVNULL)
        for kind,resource in [("network",name),("volume",name+"-cache"),("volume",name+"-native")]:
            result=subprocess.run(["docker",kind,"inspect","--format",'{{index .Labels "streaming.vod.test"}}',resource],capture_output=True,text=True)
            if result.returncode==0 and result.stdout.strip()==name:
                subprocess.run(["docker",kind,"rm",resource],check=True,stdout=subprocess.DEVNULL)


if __name__=="__main__":main()
