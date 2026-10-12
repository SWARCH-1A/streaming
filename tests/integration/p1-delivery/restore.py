"""Restore own fictitious P1 data into empty volumes; private backups survive a failed check."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import uuid
import verify as v


def compose_args(*args):
    return ['docker','compose','--env-file',str(v.manage.STATE/'environment.env'),'-p',v.manage.PROJECT,
            '-f',str(v.ROOT/'infra/p1/compose.yaml'),*args]


def binary_command(args, *, source=None, target=None):
    with open(source,'rb') if source else open(os.devnull,'rb') as stdin:
        if target:
            fd=os.open(target,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
            with os.fdopen(fd,'wb') as stdout:
                result=subprocess.run(args,stdin=stdin,stdout=stdout,stderr=subprocess.PIPE,env=v.ENV,timeout=90)
        else:result=subprocess.run(args,stdin=stdin,stdout=subprocess.PIPE,stderr=subprocess.PIPE,env=v.ENV,timeout=90)
    if result.returncode:
        diagnostic=Path(source or target).parent/'restore-command.log'
        diagnostic.write_bytes(result.stderr);diagnostic.chmod(0o600)
        raise RuntimeError('Backup/restore command failed; private backup and diagnostics retained')


def volume(name):
    full=v.manage.PROJECT+'_'+name
    inspected=json.loads(v.manage.run(['docker','volume','inspect',full]))[0]
    if inspected.get('Labels',{}).get('com.docker.compose.project')!=v.manage.PROJECT:
        raise RuntimeError('Refusing a volume not owned by the disposable P1 project')
    return full


def archive(backup,name,restore=False):
    # No network and only an explicitly checked own volume plus the private backup directory.
    args=['docker','run','--rm','--network','none','--mount','type=volume,source='+volume(name)+',target=/data'+('' if restore else ',readonly'),
          '--mount','type=bind,source='+str(backup)+',target=/backup'+(',readonly' if restore else ''),
          'redis:8-alpine','sh','-c',
          ('tar -xpf /backup/'+name+'.tar -C /data' if restore else 'umask 077; tar -cpf /backup/'+name+'.tar -C /data . && chown '+str(os.getuid())+':'+str(os.getgid())+' /backup/'+name+'.tar')]
    v.manage.run(args,timeout=90)
    if not restore:os.chmod(backup/(name+'.tar'),0o600)


def up():
    v.manage.run(['python3',str(v.ROOT/'infra/p1/manage.py'),'up','--replicas','2'],timeout=180)


def run():
    fixture=json.loads((v.manage.STATE/'load-seed.json').read_text())
    owner=v.Client();owner.login('p1_load_000',fixture['password'])
    bootstrap=owner.call('/api/channels/by-handle/p1_load_000');channel_id=bootstrap['channel']['channelId']
    user_id=bootstrap['profile']['userId']
    image=v.ROOT/'apps/web/public/images/studio-1.jpg';expected_bytes=image.read_bytes()
    avatar=owner.upload('/api/profile/me/avatar-uploads',image)
    owner.core('/api/profile/me','PATCH',{'avatarUploadId':avatar['uploadId'],'displayName':'Persona backup P1'})
    banner=owner.upload('/api/channels/'+channel_id+'/banner-uploads',image)
    owner.core('/api/channels/'+channel_id,'PATCH',{'bannerUploadId':banner['uploadId'],'description':'Canal restaurado P1'})
    config=owner.call('/api/channels/'+channel_id+'/streams')
    config['streamKey']=owner.call('/api/streams/'+config['streamId']+'/ingest-keys/rotate','POST')['streamKey']
    backup=v.manage.STATE/('backup-'+uuid.uuid4().hex);backup.mkdir(mode=0o700)
    source=None;ws=None;sid=None
    log_path=backup/'media.log'
    with open(log_path,'w') as log:
        os.chmod(log_path,0o600)
        try:
            source=v.publish(config,log)
            live=v.eventually(lambda:owner.call('/api/streams/'+config['streamId']),lambda x:x['availability']=='PLAYABLE')
            sid=live['sessionId'];v.decoded(sid)
            ws=v.connect(owner,sid);v.frame(ws,'chat.ready')
            cmid=str(uuid.uuid4());ws.send(json.dumps({'type':'message.send','clientMessageId':cmid,'text':'Mensaje confirmado antes del backup'}))
            ack=v.frame(ws,'message.accepted',cmid)
            history_path='/api/chat/sessions/'+sid+'/messages';expected_history=owner.call(history_path)['items']
            assert any(m['messageId']==ack['messageId'] for m in expected_history)
            expected=owner.call('/api/channels/by-handle/p1_load_000')
            ws.close(timeout=1);ws=None;source.terminate();source.wait(timeout=6);source=None
            v.compose('stop','core','chat','streaming')
            for service,user,database in (('postgres','core','core'),('db','streaming','streaming'),('db','streaming','streaming_media')):
                binary_command(compose_args('exec','-T',service,'pg_dump','-U',user,'-Fc',database),target=backup/(database+'.dump'))
            v.compose('stop','chat-redis')
            for name in ('chat-redis','core-avatars','core-banners'):archive(backup,name)
            # Backup consistency uses stopped writers. All resets are restricted to this fixed own project.
            print('Backup captured with stopped writers; replacing only own fixture volumes',flush=True)
            v.compose('down','--volumes','--remove-orphans')
            v.compose('create')
            v.compose('up','-d','postgres','db')
            for service,user,database in (('postgres','core','core'),('db','streaming','streaming'),('db','streaming','streaming_media')):
                v.eventually(lambda:v.compose('exec','-T',service,'pg_isready','-h',service,'-U',user,'-d',database),lambda x:'accepting connections' in x)
                binary_command(compose_args('exec','-T',service,'pg_restore','-U',user,'-d',database,'--clean','--if-exists','--exit-on-error'),source=backup/(database+'.dump'))
            for name in ('chat-redis','core-avatars','core-banners'):archive(backup,name,restore=True)
            print('SQL/AOF/filesystem restored into empty volumes; starting own runtimes',flush=True)
            up()
            restored=v.eventually(lambda:owner.call('/api/channels/by-handle/p1_load_000'),lambda x:x['profile']['userId']==user_id)
            assert restored['channel']==expected['channel'] and restored['profile']==expected['profile']
            assert owner.call(restored['profile']['avatarUri'],raw=True)==expected_bytes
            assert owner.call(restored['channel']['bannerUri'],raw=True)==expected_bytes
            assert owner.call('/api/channels/'+channel_id+'/streams')['streamId']==config['streamId']
            restored_history=v.eventually(lambda:owner.call(history_path),lambda x:len(x['items'])==len(expected_history))
            assert restored_history['items']==expected_history
            assert owner.call('/api/streams/sessions/'+sid)['status']=='ENDED'
            # The same persisted key/config must work without reconstructing the lost monotonic clock.
            source=v.publish(config,log)
            renewed=v.eventually(lambda:owner.call('/api/streams/'+config['streamId']),lambda x:x['availability']=='PLAYABLE')
            assert renewed['sessionId']!=sid;sid=renewed['sessionId'];v.decoded(sid)
            metadata={'scenario':'empty-volume-restore','baseCommit':v.manage.run(['git','rev-parse','HEAD'],cwd=v.ROOT).strip(),
                      'files':{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in backup.iterdir() if p.suffix in ('.dump','.tar')}}
            with open(backup/'verification.json','w') as out:json.dump(metadata,out,indent=2)
            os.chmod(backup/'verification.json',0o600)
            print('PASS empty-volume restore: SQL IDs/config/session, Redis ACK/history, avatar/banner bytes and fenced automatic reconnect',flush=True)
        finally:
            if ws:
                try:ws.close(timeout=1)
                except Exception:pass
            if source:
                source.terminate()
                try:source.wait(timeout=6)
                except Exception:source.kill();source.wait(timeout=3)
            up()
            if sid:
                try:owner.call('/api/streams/sessions/'+sid,'DELETE',expect=204)
                except Exception:pass


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--confirm-disposable',action='store_true')
    if not parser.parse_args().confirm_disposable:parser.error('--confirm-disposable required: only own fixture volumes are replaced')
    run()
