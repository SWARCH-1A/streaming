"""P2 end-to-end: follow (SPEC-03) -> live session -> notice and email (SPEC-20) -> SSR channel HTML (SPEC-18).

Runs against the local installation (python3 infra/local/dev.py up). Creates two fictitious accounts,
publishes a short synthetic RTMPS stream with ffmpeg and checks the inbox and Mailpit (127.0.0.1:8025).
Credentials stay in memory; output contains only scenario summaries.
"""
from __future__ import annotations
import json
import os
from pathlib import Path
import secrets
import sys
import tempfile
import time
import urllib.request
import uuid

os.environ["P1_PROFILE"] = "local"
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "p1-delivery"))
import verify as v  # noqa: E402

MAILPIT = os.environ.get("MAILPIT_URL", "http://127.0.0.1:" + v.ENV.get("MAILPIT_UI_PORT", "8025"))


def key():
    return {"Idempotency-Key": str(uuid.uuid4())}


def mail_to(address):
    with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(MAILPIT + "/api/v1/messages?limit=50", timeout=5) as r:
        messages = json.load(r)["messages"]
    return [m for m in messages if any(t["Address"] == address for t in m["To"])]


def main():
    suffix = secrets.token_hex(3)
    password = secrets.token_urlsafe(18)
    owner, fan, anon = v.Client(), v.Client(), v.Client()
    owner_handle, fan_handle = "p2owner_" + suffix, "p2fan_" + suffix
    v.register(owner, owner_handle, password, str(uuid.uuid4()))
    v.register(fan, fan_handle, password, str(uuid.uuid4()))
    owner.login(owner_handle, password)
    fan.login(fan_handle, password)
    channel = anon.call("/api/channels/by-handle/" + owner_handle)
    cid = channel["channel"]["channelId"]

    follow = fan.core(f"/api/channels/{cid}/followers/me", "PUT", headers=key())
    again = fan.core(f"/api/channels/{cid}/followers/me", "PUT", headers=key())
    assert follow["following"] and again["followVersion"] == follow["followVersion"] == 1
    fan.core(f"/api/channels/{cid}/followers/me", "PUT", headers=key(), expect=200)
    owner.core(f"/api/channels/{cid}/followers/me", "PUT", headers=key(), expect=422)
    page = fan.call("/api/me/followed-channels")
    assert [item["channelId"] for item in page["items"]] == [cid]
    print("PASS SPEC-03 follow: idempotent PUT, version 1, self-follow 422, followed list", flush=True)

    fan.core("/api/notifications/preferences", "PUT", {"emailEnabled": True, "pushEnabled": False, "expectedVersion": 1},
             key())
    fan.core(f"/api/notifications/channels/{cid}/preference", "PUT",
             {"emailEnabled": True, "pushEnabled": False, "expectedVersion": 1}, key())
    fan.core("/api/notifications/preferences", "PUT", {"emailEnabled": False, "pushEnabled": False, "expectedVersion": 1},
             key(), expect=409)
    print("PASS SPEC-20 preferences: general + channel email enabled, stale version 409", flush=True)

    html = anon.call("/channels/" + owner_handle, raw=True, headers={"Accept": "text/html"}).decode()
    assert owner_handle in html and "<h1>" in html and 'id="root"' in html, "channel HTML lacks server content"
    assert "Siguiendo" not in html
    fan_html = fan.call("/channels/" + owner_handle, raw=True, headers={"Accept": "text/html"}).decode()
    assert "Siguiendo" in fan_html, "SSR did not render the session follow state"
    missing = anon.call("/channels/no_such_" + suffix, raw=True, headers={"Accept": "text/html"}, expect=404)
    assert missing
    print("PASS SPEC-18 SSR: channel content in HTML without JS, per-user follow state, real 404", flush=True)

    catalog = anon.call("/api/taxonomy")
    config = owner.call(f"/api/channels/{cid}/streams", "POST",
                        {"title": "Prueba P2 seguimiento", "categoryId": catalog["categories"][0]["id"], "tagIds": []},
                        key(), 201)
    # Same values OBS uses: server URL + stream key.
    config["ingestUrl"] = config["rtmpUrl"].rstrip("/") + "/" + config["streamKey"]
    log = tempfile.TemporaryFile()
    publisher = v.publish(config, log)
    sid = None
    try:
        live = v.eventually(lambda: anon.call("/api/streams/" + config["streamId"]), lambda s: s["availability"] == "PLAYABLE", 90)
        sid = live["sessionId"]
        print("PASS live session PLAYABLE", flush=True)
        inbox = v.eventually(lambda: fan.call("/api/notifications"),
                             lambda p: len(p["items"]) == 1, 30)
        notice = inbox["items"][0]
        assert notice["sessionId"] == sid and notice["linkPath"] == "/channels/" + owner_handle and notice["readAtUtc"] is None
        assert not owner.call("/api/notifications")["items"], "owner must not be notified of their own stream"
        print(f"PASS SPEC-20 inbox: one notice for the follower ({notice['title']!r})", flush=True)
        emails = v.eventually(lambda: mail_to(fan_handle + "@example.test"), lambda found: len(found) == 1, 30)
        assert notice["title"] in emails[0]["Subject"]
        print("PASS SPEC-20 email: one message caught by Mailpit", flush=True)
        read = fan.core(f"/api/notifications/{notice['notificationId']}/read", "PUT", headers=key())
        assert read["readAtUtc"]
        owner.core(f"/api/notifications/{notice['notificationId']}/read", "PUT", headers=key(), expect=404)
        print("PASS SPEC-20 read: idempotent for the owner of the notice, 404 for another account", flush=True)
    finally:
        if sid:
            owner.call("/api/streams/sessions/" + sid, "DELETE", expect=204)
        publisher.terminate()
        publisher.wait(20)
    time.sleep(3)
    assert len(fan.call("/api/notifications")["items"]) == 1 and len(mail_to(fan_handle + "@example.test")) == 1
    print("PASS no duplicates after the session ended", flush=True)


if __name__ == "__main__":
    main()
