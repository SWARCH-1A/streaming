"""SPEC-18 browser checks against the local installation (python3 infra/local/dev.py up).

Real Chromium through the HTTPS proxy: CA-04 login/logout/CSRF with SSR session, CA-05 leaving /watch closes
the chat WebSocket, CA-07 offline -> live -> ended without reloading, CA-08 keyboard path and clean hydration.
Accounts are fictitious; credentials stay in memory. Output contains only scenario summaries.
"""
from __future__ import annotations
import os
import argparse
from pathlib import Path
import secrets
import sys
import tempfile
import time
import uuid

os.environ["P1_PROFILE"] = "local"
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "p1-delivery"))
import verify as v  # noqa: E402
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "p2-fixtures"))
from accounts import fixture_account
from playwright.sync_api import expect, sync_playwright  # noqa: E402

ORIGIN = v.ORIGIN
NOMINAL_S = 5  # P2 end-to-end visibility budget; no additional polling margin


def key():
    return {"Idempotency-Key": str(uuid.uuid4())}


def stop(owner, sid):
    # Ending is idempotent; retry while a busy Streaming answers 503.
    v.eventually(lambda: owner.call("/api/streams/sessions/" + sid, "DELETE", expect=204) or True, bool, 60)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--browser", choices=("chromium", "firefox", "webkit"), default="chromium")
    args = parser.parse_args()
    suffix = secrets.token_hex(3)
    password = secrets.token_urlsafe(18)
    owner, fan = v.Client(), v.Client()
    owner_handle, fan_handle = "ssrowner_" + suffix, "ssrfan_" + suffix
    owner_fixture, fan_fixture = fixture_account("ssr-owner"), fixture_account("ssr-fan")
    if owner_fixture and fan_fixture:
        owner_handle, fan_handle = owner_fixture["handle"], fan_fixture["handle"]
        password = fan_fixture["password"]
        owner.login(owner_handle, owner_fixture["password"])
    else:
        v.register(owner, owner_handle, password, str(uuid.uuid4()))
        v.register(fan, fan_handle, password, str(uuid.uuid4()))
        owner.login(owner_handle, password)
    catalog = owner.call("/api/taxonomy")
    cid = owner.call("/api/channels/by-handle/" + owner_handle)["channel"]["channelId"]
    if fan_fixture:
        fan.login(fan_handle, password)
        fan.core(f"/api/channels/{cid}/followers/me", "DELETE", headers=key(), expect=200)
    config = owner.call(f"/api/channels/{cid}/streams", "POST",
                        {"title": "Prueba SSR navegador", "categoryId": catalog["categories"][0]["id"], "tagIds": []}, key(), (200, 201) if owner_fixture else 201)
    if owner_fixture and not config.get("streamKey"):
        owner.call(f"/api/streams/{config['streamId']}", "PATCH", {"title": "Prueba SSR navegador"}, headers=key())
        rotated = owner.call(f"/api/streams/{config['streamId']}/ingest-keys/rotate", "POST", headers=key())
        config["streamKey"] = rotated["streamKey"]
    config["ingestUrl"] = config["rtmpUrl"].rstrip("/") + "/" + config["streamKey"]

    with sync_playwright() as p:
        browser = getattr(p, args.browser).launch()
        context = browser.new_context(ignore_https_errors=True, locale="es-CO")
        page = context.new_page()
        errors = []
        page.on("pageerror", lambda error: errors.append(str(error)))
        page.on("console", lambda m: errors.append(m.text) if m.type == "error" and "Failed to load resource" not in m.text else None)

        # CA-04: login form (CSRF + cookie) -> SSR renders the session on reload -> logout clears it.
        page.goto(ORIGIN + "/login")
        page.get_by_label("Handle o correo").fill(fan_handle)
        page.get_by_label("Contraseña", exact=True).fill(password)
        page.get_by_role("button", name="Iniciar sesión", exact=True).click()
        expect(page.get_by_role("button", name="Salir")).to_be_visible(timeout=15000)
        # The loader gives Core 3 s; when Core is busy the HTML degrades to anonymous and the browser probes
        # again after hydrating (root loader user=undefined). Retry to observe the nominal server render.
        html = v.eventually(lambda: page.request.get(ORIGIN + "/channels/" + owner_handle, headers={"Accept": "text/html"}).text(),
                            lambda text: "Salir" in text and owner_handle in text, 30)
        assert f"<title>{owner_handle} · Vault tv</title>" in html, "channel title missing from SSR head"
        page.reload()
        expect(page.get_by_role("button", name="Salir")).to_be_visible()
        page.get_by_role("button", name="Salir").click()
        expect(page.get_by_role("link", name="Iniciar sesión")).to_be_visible(timeout=10000)
        page.goto(ORIGIN + "/notifications")
        expect(page).to_have_url(ORIGIN + "/login", timeout=10000)
        anonymous = page.request.get(ORIGIN + "/", headers={"Accept": "text/html"}).text()
        assert "Salir" not in anonymous
        print("PASS CA-04 login/logout over HTTPS: CSRF form, SSR session after reload, logout and protected redirect", flush=True)

        # Log in again for the keyboard and WebSocket checks.
        page.get_by_label("Handle o correo").fill(fan_handle)
        page.get_by_label("Contraseña", exact=True).fill(password)
        page.get_by_role("button", name="Iniciar sesión", exact=True).click()
        expect(page.get_by_role("button", name="Salir")).to_be_visible(timeout=15000)

        # CA-08: focus lands on the route heading, then "Seguir" is reached and pressed with the keyboard only.
        page.goto(ORIGIN + "/channels/" + owner_handle)
        expect(page.get_by_role("heading", level=1)).to_be_visible()
        # RouteFocus (SPEC-08) moves focus to the page heading so screen readers announce the new route.
        expect(page.get_by_role("heading", level=1)).to_be_focused(timeout=5000)
        assert page.evaluate("document.querySelector('a[href=\"#main-content\"]').textContent.trim()") == "Saltar al contenido"
        for _ in range(40):
            page.keyboard.press("Tab")
            if page.evaluate("document.activeElement.textContent.trim()") == "Seguir":
                break
        else:
            raise AssertionError("Seguir is not reachable with Tab")
        page.keyboard.press("Enter")
        follow = page.get_by_role("button", name="Siguiendo")
        expect(follow).to_be_visible(timeout=10000)
        expect(follow).to_have_attribute("aria-pressed", "true")
        print("PASS CA-08 keyboard: route focus on h1, skip link present, Tab to Seguir, Enter follows (aria-pressed)", flush=True)

        # CA-07: the open channel page changes offline -> live -> ended without reload.
        badge = page.locator("header").get_by_text("Offline", exact=True)
        expect(badge).to_be_visible()
        log = tempfile.TemporaryFile()
        publisher = v.publish(config, log)
        sid = None
        try:
            started = time.monotonic()
            expect(page.get_by_text("En vivo", exact=True).first).to_be_visible(timeout=90000)
            live = v.eventually(lambda: owner.call("/api/streams/" + config["streamId"]), lambda s: s["availability"] == "PLAYABLE", 30)
            sid = live["sessionId"]
            print(f"PASS CA-07 live shown without reload ({time.monotonic() - started:.1f} s after the publisher started)", flush=True)

            v.eventually(lambda: owner.call("/api/screens/explore?limit=12"),
                         lambda screen: any(item["channel"]["handle"] == owner_handle for item in screen["discovery"].get("data", {}).get("streams", {}).get("items", [])), NOMINAL_S)
            for path in ("/", "/search?q=" + owner_handle):
                rendered = page.request.get(ORIGIN + path, headers={"Accept": "text/html"})
                assert rendered.status == 200
                html = rendered.text()
                assert f'href="/channels/{owner_handle}"' in html, "real channel missing from first HTML"
                assert "Prueba SSR navegador" in html if path == "/" else owner_handle in html
                assert "no-store" in rendered.headers.get("cache-control", "")
            print("PASS home/search first HTML contains real Gateway discovery results", flush=True)

            # CA-05: /watch opens the chat WebSocket; leaving the route closes it.
            sockets = []
            page.on("websocket", lambda ws: sockets.append(ws) if "/realtime/chat/" in ws.url else None)
            # expect_websocket keeps Playwright dispatching events while it waits (a time.sleep loop would
            # block the callback). The chat connects once the session read finishes: wide local margin.
            with page.expect_websocket(lambda ws: "/realtime/chat/" in ws.url, timeout=90000):
                page.goto(ORIGIN + "/watch/" + config["streamId"])
            page.get_by_role("link", name="Explorar").first.click()
            expect(page).to_have_url(ORIGIN + "/", timeout=10000)
            deadline = time.monotonic() + 10
            while time.monotonic() < deadline and not all(ws.is_closed() for ws in sockets):
                page.wait_for_timeout(200)
            assert all(ws.is_closed() for ws in sockets), "chat WebSocket stayed open after leaving /watch"
            print(f"PASS CA-05 leaving /watch closed {len(sockets)} chat WebSocket(s)", flush=True)

            page.goto(ORIGIN + "/channels/" + owner_handle)
            expect(page.get_by_text("En vivo", exact=True).first).to_be_visible(timeout=20000)
            # The fixture reconnects like OBS: stop it before the terminal action,
            # otherwise it legitimately creates a new live session after DELETE.
            publisher.terminate()
            publisher.wait(20)
            stop(owner, sid)
            sid = None
            ended = time.monotonic()
            expect(page.locator("header").get_by_text("Offline", exact=True)).to_be_visible(timeout=NOMINAL_S * 1000)
            print(f"PASS CA-07 end shown without reload ({time.monotonic() - ended:.1f} s)", flush=True)
        finally:
            if sid:
                stop(owner, sid)
            publisher.terminate()
            publisher.wait(20)

        hydration = [e for e in errors if "ydrat" in e or "did not match" in e]
        assert not hydration, "hydration errors: " + "; ".join(hydration[:3])
        print(f"PASS clean hydration (no hydration errors; {len(errors)} other console messages)", flush=True)
        browser.close()


if __name__ == "__main__":
    main()
