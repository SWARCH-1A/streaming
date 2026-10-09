"""Idempotent, fictitious 100-account fixture for the own disposable P1 project.

One real registration supplies the password hash; fixture SQL clones it into Core only.
Streaming LIVE is never seeded through SQL. All credentials stay in the private state directory.
"""
from __future__ import annotations
import argparse
import json
import os
import secrets
import uuid
import verify as v


def seed():
    path=v.manage.STATE/"load-seed.json"
    if path.exists():
        fixture=json.loads(path.read_text())
    else:
        if v.sql("SELECT count(*) FROM identity.accounts WHERE handle LIKE 'p1_load_%'")!="0":
            raise RuntimeError("Existing load accounts have no matching private fixture; use a new disposable profile")
        fixture={"password":secrets.token_urlsafe(32),"key":str(uuid.uuid4())}
        with os.fdopen(os.open(path,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600),"w") as out:json.dump(fixture,out)
    owner=v.Client()
    registration=v.register(owner,"p1_load_000",fixture["password"],fixture["key"])
    # Only this fixture's prefix is inserted. No existing user's rows are updated/deleted.
    v.sql("""BEGIN;
      INSERT INTO identity.accounts(user_id,email,normalized_email,handle,canonical_handle,password_hash,created_at_utc)
      SELECT 'usr_p1load_'||lpad(n::text,3,'0'),'p1_load_'||lpad(n::text,3,'0')||'@example.test',
        'p1_load_'||lpad(n::text,3,'0')||'@example.test','p1_load_'||lpad(n::text,3,'0'),
        'p1_load_'||lpad(n::text,3,'0'),a.password_hash,now()
      FROM generate_series(1,99) n CROSS JOIN identity.accounts a WHERE a.handle='p1_load_000'
      ON CONFLICT DO NOTHING;
      INSERT INTO profile.profiles(user_id,display_name,bio,created_at_utc,updated_at_utc)
      SELECT user_id,'Persona carga '||right(handle,3),'Fixture P1',now(),now() FROM identity.accounts
      WHERE handle LIKE 'p1_load_%' ON CONFLICT DO NOTHING;
      INSERT INTO channels.channels(channel_id,owner_user_id,description,created_at_utc,updated_at_utc)
      SELECT 'chn_p1load_'||right(handle,3),user_id,'Canal de carga P1',now(),now() FROM identity.accounts
      WHERE handle LIKE 'p1_load_%' AND handle<>'p1_load_000' ON CONFLICT DO NOTHING;
      COMMIT;""")
    assert v.sql("SELECT count(*) FROM identity.accounts a JOIN profile.profiles p USING(user_id) JOIN channels.channels c ON c.owner_user_id=a.user_id WHERE a.handle LIKE 'p1_load_%'")=="100"
    fixture["firstChannelId"]=registration["channelId"]
    with open(path,"w") as out:json.dump(fixture,out)
    print("PASS fixture: 100 fictitious account/profile/channel triples; no SQL writes to Streaming")
    return fixture


if __name__=="__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--confirm-disposable",action="store_true")
    args=parser.parse_args()
    if not args.confirm_disposable:parser.error("--confirm-disposable required for own fixture SQL")
    seed()
