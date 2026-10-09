"""Collect own-project resource samples and sanitized authorization timing aggregates."""
from datetime import datetime, timezone
import json
import os
import platform
import re
import subprocess
import threading
import time
import verify as v


def percentile(values):
    return sorted(values)[max(0, __import__('math').ceil(len(values)*.95)-1)] if values else None


class Metrics:
    def __init__(self):
        self.ids=v.compose('ps','-q').splitlines()
        self.samples=[];self.errors=[];self.stop=threading.Event()
        free_kib=int(v.compose('exec','-T','db','sh','-c',"df -Pk /var/lib/postgresql | awk 'NR==2 {print $4}'").strip())
        if free_kib<512*1024:raise RuntimeError('Docker filesystem requires at least 512 MiB available before load')
        self.thread=None
        info=json.loads(v.manage.run(['docker','info','--format','{{json .}}']))
        self.host={'os':platform.platform(),'machine':platform.machine(),'logicalCpu':os.cpu_count(),
                   'dockerServerVersion':info['ServerVersion'],'dockerCpu':info['NCPU'],
                   'dockerMemoryBytes':info['MemTotal'],'sampleIntervalSeconds':10}
        if platform.system()=='Darwin':
            self.host['memoryBytes']=int(v.manage.run(['sysctl','-n','hw.memsize']).strip())
        elif os.path.exists('/proc/meminfo'):
            self.host['memoryBytes']=int(re.search(r'MemTotal:\s+(\d+)',open('/proc/meminfo').read())[1])*1024
    def begin(self,start,end):
        self.start=start;self.end=end
        self.since=datetime.fromtimestamp(time.time()+max(0,start-time.monotonic()),timezone.utc).isoformat()
        self.until=datetime.fromtimestamp(time.time()+max(0,end-time.monotonic()),timezone.utc).isoformat()
        def sample():
            if self.stop.wait(max(0,start-time.monotonic())):return
            due=start
            while time.monotonic()<end and not self.stop.is_set():
                try:
                    rows=v.manage.run(['docker','stats','--no-stream','--format','{{json .}}',*self.ids],timeout=8)
                    self.samples.append({'elapsedSeconds':round(time.monotonic()-start,2),
                                         'containers':[json.loads(x) for x in rows.splitlines()]})
                except Exception:self.errors.append('docker stats unavailable')
                due+=10
                self.stop.wait(max(0,due-time.monotonic()))
        self.thread=threading.Thread(target=sample,daemon=True);self.thread.start()
    def finish(self,expected):
        self.stop.set()
        if self.thread:self.thread.join(timeout=10)
        contexts=[];attempts=[];streaming=[];statuses={}
        for container in self.ids:
            result=subprocess.run(['docker','logs','--since',self.since,'--until',self.until,container],capture_output=True,text=True,timeout=30)
            if result.returncode:self.errors.append('own container logs unavailable');continue
            for line in (result.stdout+'\n'+result.stderr).splitlines():
                line=re.sub(r'\x1b\[[0-9;]*m','',line)
                if 'chat_authorization' in line:
                    # Go slog's JSON and text handlers are both accepted, without retaining IDs or payloads.
                    try:
                        event=json.loads(line);contexts.append(int(event['contextMs']));attempts.append(int(event['persistAttemptMs']))
                    except (ValueError,KeyError):
                        c=re.search(r'contextMs[=":\s]+(\d+)',line);p=re.search(r'persistAttemptMs[=":\s]+(\d+)',line)
                        if c and p:contexts.append(int(c[1]));attempts.append(int(p[1]))
                if re.search(r'/internal/streaming/sessions/[^\s"?]+/context',line):
                    latency=re.search(r'latency_ms[=":\s]+(\d+)',line)
                    status=re.search(r'status[=":\s]+(\d+)',line)
                    if latency:streaming.append(int(latency[1]))
                    if status:statuses[status[1]]=statuses.get(status[1],0)+1
        def summary(values,budget):return {'samples':len(values),'p95Ms':percentile(values),'maxMs':max(values,default=None),
                                          'budgetMs':budget,'overBudget':sum(x>budget for x in values)}
        timing={'chatContext':summary(contexts,400),'persistAttempt':summary(attempts,500),
                'coreToStreamingServer':summary(streaming,200),'streamingStatusCounts':statuses,
                'streamingMeasurement':'server request latency; the 200 ms end-to-end client deadline is enforced by Core'}
        timing['complete']=len(contexts)>=expected and len(attempts)>=expected and len(streaming)>=expected
        timing['budgetsPass']=timing['complete'] and not any(timing[k]['overBudget'] for k in ('chatContext','persistAttempt','coreToStreamingServer')) and set(statuses)=={'200'}
        return {'host':self.host,'samples':self.samples,'errors':self.errors,'authorization':timing}
