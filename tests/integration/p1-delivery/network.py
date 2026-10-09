"""Bounded, opaque CONNECT tunnels for the own loopback load fixture.

Each player has an independent listener and aggregate downstream queue. TLS stays
between Firefox and Caddy. This is a generator tool, never a deployed service.
"""
from __future__ import annotations

import asyncio
import threading
import time
from urllib.parse import urlsplit


class PlayerNetwork:
    def __init__(self, origin, players, down_mbps=15, setup_delay_ms=10):
        parsed = urlsplit(origin)
        if (parsed.scheme != "https" or parsed.hostname != "localhost" or not parsed.port
                or parsed.path or parsed.query or parsed.fragment or parsed.username):
            raise ValueError("Only the fixed HTTPS localhost fixture is supported")
        if not 1 <= players <= 100 or not 0 < down_mbps <= 15 or not 0 <= setup_delay_ms <= 10:
            raise ValueError("Invalid load network limits")
        self.authority = f"localhost:{parsed.port}"
        self.target_port = parsed.port
        self.players = players
        self.bytes_per_second = down_mbps * 1_000_000 / 8
        self.delay = setup_delay_ms / 1000
        self.loop = None
        self.thread = None
        self.servers = []
        self.connections = set()
        self.closing = False
        self.start = float("inf")
        self.end = float("inf")
        self.ports = []
        self.stats = [{"connections": 0, "rejected": 0, "transportErrors": 0,
                       "downTlsBytes": 0, "measuredDownTlsBytes": 0,
                       "upTlsBytes": 0, "measuredUpTlsBytes": 0} for _ in range(players)]

    def __enter__(self):
        ready = threading.Event()
        errors = []

        def run():
            self.loop = asyncio.new_event_loop()
            asyncio.set_event_loop(self.loop)
            try:
                self.loop.run_until_complete(self._listen())
            except Exception as error:
                errors.append(error)
                self.loop.run_until_complete(self._shutdown())
            finally:
                ready.set()
            if not errors:
                self.loop.run_forever()
            self.loop.close()

        self.thread = threading.Thread(target=run, name="p1-player-network", daemon=True)
        self.thread.start()
        if not ready.wait(10) or errors:
            self.close()
            raise RuntimeError("Player network setup failed") from (errors[0] if errors else None)
        return self

    async def _listen(self):
        self.queues = [asyncio.Lock() for _ in range(self.players)]
        for i in range(self.players):
            server = await asyncio.start_server(
                lambda reader, writer, player=i: self._accept(reader, writer, player),
                "127.0.0.1", 0, limit=8192)
            self.servers.append(server)
            self.ports.append(server.sockets[0].getsockname()[1])

    def begin(self, start, end):
        async def update():
            self.start, self.end = start, end
        asyncio.run_coroutine_threadsafe(update(), self.loop).result(timeout=5)

    async def _pipe(self, reader, writer, player, downstream):
        # One initial delay per direction, not an artificial delay per TLS record.
        await asyncio.sleep(self.delay)
        while data := await reader.read(32768):
            if downstream:
                # Hold the per-player lock through delivery: parallel connections
                # cannot multiply the allowance or create an unbounded body queue.
                async with self.queues[player]:
                    await asyncio.sleep(len(data) / self.bytes_per_second)
                    writer.write(data)
                    await writer.drain()
            else:
                writer.write(data)
                await writer.drain()
            key = "downTlsBytes" if downstream else "upTlsBytes"
            self.stats[player][key] += len(data)
            if self.start <= time.monotonic() < self.end:
                self.stats[player]["measured" + key[0].upper() + key[1:]] += len(data)

    async def _accept(self, reader, writer, player):
        task = asyncio.current_task()
        self.connections.add(task)
        upstream = None
        pipes = []
        try:
            if self.closing:
                return
            header = await asyncio.wait_for(reader.readuntil(b"\r\n\r\n"), timeout=3)
            request = header.split(b"\r\n", 1)[0]
            if len(header) > 8192 or request != f"CONNECT {self.authority} HTTP/1.1".encode():
                self.stats[player]["rejected"] += 1
                writer.write(b"HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                await writer.drain()
                return
            source, upstream = await asyncio.wait_for(
                asyncio.open_connection("127.0.0.1", self.target_port, limit=65536), timeout=3)
            self.stats[player]["connections"] += 1
            writer.write(b"HTTP/1.1 200 Connection Established\r\n\r\n")
            await writer.drain()
            pipes = [asyncio.create_task(self._pipe(reader, upstream, player, False)),
                     asyncio.create_task(self._pipe(source, writer, player, True))]
            done, _ = await asyncio.wait(pipes, return_when=asyncio.FIRST_COMPLETED)
            for finished in done:
                finished.result()
        except (asyncio.IncompleteReadError, asyncio.LimitOverrunError, asyncio.TimeoutError):
            self.stats[player]["rejected"] += 1
        except (ConnectionError, OSError):
            self.stats[player]["transportErrors"] += 1
        finally:
            for pipe in pipes:
                pipe.cancel()
            # Close synchronously before awaiting child tasks. Shutdown may
            # cancel a handler already in cleanup; its transports must still close.
            for stream in (writer, upstream):
                if stream:
                    stream.close()
            try:
                await asyncio.gather(*pipes, return_exceptions=True)
                for stream in (writer, upstream):
                    if stream:
                        try:
                            await asyncio.wait_for(stream.wait_closed(), timeout=1)
                        except (ConnectionError, OSError, asyncio.TimeoutError):
                            pass
            finally:
                self.connections.discard(task)

    def snapshot(self):
        async def copy():
            return {"players": [dict(item) for item in self.stats],
                    "activeConnections": len(self.connections),
                    "perPlayerDownMbps": self.bytes_per_second * 8 / 1_000_000,
                    "initialDelayPerDirectionMs": self.delay * 1000,
                    "configuredLossPercent": 0,
                    "mechanism": "opaque loopback CONNECT; aggregate downstream per player; TLS end-to-end"}
        return asyncio.run_coroutine_threadsafe(copy(), self.loop).result(timeout=5)

    async def _shutdown(self):
        self.closing = True
        for server in self.servers:
            server.close()
        # Python 3.12 Server.wait_closed also waits for accepted clients. Cancel
        # their handlers first, otherwise an idle CONNECT prevents shutdown.
        for task in list(self.connections):
            task.cancel()
        await asyncio.gather(*list(self.connections), return_exceptions=True)
        await asyncio.gather(*(server.wait_closed() for server in self.servers))

    def close(self):
        if self.loop and self.loop.is_running():
            asyncio.run_coroutine_threadsafe(self._shutdown(), self.loop).result(timeout=5)
            self.loop.call_soon_threadsafe(self.loop.stop)
        if self.thread:
            self.thread.join(timeout=5)
            if self.thread.is_alive():
                raise RuntimeError("Player network did not stop")

    def __exit__(self, *_):
        self.close()
