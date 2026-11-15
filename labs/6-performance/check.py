#!/usr/bin/env python3
"""Prepara y comprueba el recorrido del canal público, sin generar carga."""

import argparse
import http.cookiejar
import json
import secrets
import ssl
import time
import urllib.error
import urllib.request
import uuid

HANDLE = "lab6_canal"
SCREEN = f"/api/screens/channels/{HANDLE}"
CHANNEL = f"/channels/{HANDLE}"


class Client:
    def __init__(self, origin, ca):
        self.origin = origin.rstrip("/")
        self.opener = urllib.request.build_opener(
            urllib.request.ProxyHandler({}),
            urllib.request.HTTPSHandler(context=ssl.create_default_context(cafile=ca)),
            urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()),
        )

    def call(self, path, *, headers=None, body=None):
        data = json.dumps(body).encode() if body is not None else None
        request = urllib.request.Request(self.origin + path, data=data, headers=headers or {})
        try:
            response = self.opener.open(request, timeout=5)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            return response.status, response.read()

    def register(self):
        status, raw = self.call("/api/identity/csrf")
        require(status == 200, f"CSRF devolvió HTTP {status}")
        csrf = json.loads(raw)
        status, _ = self.call(
            "/api/identity/registrations",
            headers={
                "Content-Type": "application/json",
                "Origin": self.origin,
                "Idempotency-Key": str(uuid.uuid4()),
                csrf["headerName"]: csrf["token"],
            },
            body={
                "email": f"{HANDLE}@example.test",
                "handle": HANDLE,
                "password": secrets.token_urlsafe(24),
            },
        )
        require(status == 201, f"Registro devolvió HTTP {status}")
        print(f"Canal ficticio @{HANDLE} creado por Core")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def wait_for_screen(client):
    deadline = time.monotonic() + 90
    while True:
        try:
            status, raw = client.call(SCREEN)
        except urllib.error.URLError as error:
            if isinstance(error.reason, ssl.SSLCertVerificationError):
                raise
            last = str(error)
        except TimeoutError as error:
            last = str(error)
        else:
            if status not in {502, 503, 504}:
                return status, raw
            last = f"HTTP {status}"
        if time.monotonic() >= deadline:
            raise ValueError(f"Gateway/Core no están listos: {last}")
        time.sleep(2)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("origin", help="URL HTTPS del perfil, p. ej. https://localhost:8443")
    parser.add_argument("--ca", default="lab6-ca.crt", help="Certificado raíz público de Caddy")
    parser.add_argument("--seed", action="store_true", help="Crea el canal si aún no existe")
    args = parser.parse_args()
    require(args.origin.startswith("https://"), "Se requiere un origen HTTPS")
    client = Client(args.origin, args.ca)
    status, raw = wait_for_screen(client)
    if status == 404 and args.seed:
        client.register()
        status, raw = client.call(SCREEN)
    require(status == 200, f"{SCREEN} devolvió HTTP {status}; usa --seed en una instalación nueva")
    screen = json.loads(raw)
    channel = screen["channel"]
    require(channel["handle"] == HANDLE and channel["channel"]["channelId"],
            "El Gateway no devolvió los datos del canal ficticio")
    require(channel["stream"] is None and channel["availability"] == "UNKNOWN",
            "Sin el servicio de emisiones, Core debe conservar el canal con estado UNKNOWN")
    require(screen["live"]["status"] == "EMPTY" and screen["follow"]["status"] == "DENIED",
            "El canal debe estar sin emisión y consultarse como visitante")
    require(screen["vods"]["status"] == "UNAVAILABLE" and screen["meta"]["partial"] is True,
            "Este perfil debe devolver los datos del canal aunque VOD no esté desplegado")
    print(f"OK {SCREEN} → Gateway → Core → PostgreSQL")

    status, raw = client.call(CHANNEL)
    require(status == 200 and f"<h1>{HANDLE}</h1>".encode() in raw,
            f"{CHANNEL} no devolvió el canal renderizado (HTTP {status})")
    print(f"OK {CHANNEL} → SSR → Gateway → Core → PostgreSQL")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, KeyError) as error:
        raise SystemExit(f"Lab 6: {error}")
