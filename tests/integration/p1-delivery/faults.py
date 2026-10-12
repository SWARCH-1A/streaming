"""Separate TLS outage run: Chat/Core isolation and authoritative Streaming denial.

Requires the load fixture; no nominal-load samples include these deliberate outages.
"""
import argparse
import json
import os
import uuid
import verify as v


def run():
    fixture=json.loads((v.manage.STATE/'load-seed.json').read_text())
    owner=v.Client();owner.login('p1_load_000',fixture['password'])
    channel=owner.call('/api/channels/by-handle/p1_load_000')['channel']['channelId']
    config=owner.call('/api/channels/'+channel+'/streams')
    config['streamKey']=owner.call('/api/streams/'+config['streamId']+'/ingest-keys/rotate','POST')['streamKey']
    log_path=v.manage.STATE/'faults-media.log';ws=None;source=None;sid=None
    with open(log_path,'w') as log:
        os.chmod(log_path,0o600)
        try:
            source=v.publish(config,log)
            live=v.eventually(lambda:owner.call('/api/streams/'+config['streamId']),lambda x:x['availability']=='PLAYABLE')
            sid=live['sessionId'];v.decoded(sid)
            # Kill only Chat replicas; HLS has no dependency on them.
            v.compose('stop','chat');v.decoded(sid)
            try:owner.call('/api/chat/sessions/'+sid+'/messages');raise AssertionError('Chat unexpectedly available')
            except AssertionError as error:
                if 'got 502' not in str(error) and 'got 503' not in str(error):raise
            v.compose('start','chat')
            v.eventually(lambda:owner.call('/api/chat/sessions/'+sid+'/messages'),lambda x:'items' in x)
            ws=v.connect(owner,sid);v.frame(ws,'chat.ready')
            message={'type':'message.send','clientMessageId':str(uuid.uuid4()),'text':'Mensaje previo a fallos'}
            ws.send(json.dumps(message));v.frame(ws,'message.accepted',message['clientMessageId'])
            before=len(owner.call('/api/chat/sessions/'+sid+'/messages')['items'])
            print('PASS Chat stopped: TLS HLS still decodes; Chat read recovers',flush=True)
            for service,code in (('core','CORE_UNAVAILABLE'),('streaming','STREAMING_UNAVAILABLE')):
                v.compose('stop',service)
                if service=='core':v.decoded(sid)
                rejected={**message,'clientMessageId':str(uuid.uuid4()),'text':'Escritura que no debe persistir '+service}
                ws.send(json.dumps(rejected));assert v.frame(ws,'error')['code']==code
                # Chat history remains available from Redis while authorization denies new sends.
                assert len(owner.call('/api/chat/sessions/'+sid+'/messages')['items'])==before
                print('PASS '+service+' stopped: '+code+' and no new Chat persistence',flush=True)
                v.compose('start',service)
                if service=='core':v.eventually(lambda:owner.call('/api/taxonomy'),lambda x:bool(x))
                else:
                    live=v.eventually(lambda:owner.call('/api/streams/'+config['streamId']),lambda x:x['availability']=='PLAYABLE')
                    assert live['sessionId']!=sid
                    sid=live['sessionId'];v.decoded(sid)
            print('PASS Streaming recovery: automatic source reconnect uses a new fenced session',flush=True)
        finally:
            if ws:
                try:ws.close(timeout=1)
                except Exception:pass
            if source:
                source.terminate()
                try:source.wait(timeout=6)
                except Exception:source.kill();source.wait(timeout=3)
            v.compose('start','core','chat','streaming')
            if sid:
                try:owner.call('/api/streams/sessions/'+sid,'DELETE',expect=204)
                except Exception:pass


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--confirm-disposable',action='store_true')
    if not parser.parse_args().confirm_disposable:parser.error('--confirm-disposable required')
    run()
