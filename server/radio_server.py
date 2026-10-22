"""Khvylia: TLS-only PCM relay. Python 3.11+, no third-party packages."""
import argparse
import asyncio
import base64
import hashlib
import json
import re
import ssl
import time
from pathlib import Path

MAX_LINE = 8192
FRAME_BYTES = 640  # 20 ms, signed PCM16 little-endian, 16 kHz mono


class Peer:
    def __init__(self, writer):
        self.writer = writer
        self.queue = asyncio.Queue(maxsize=64)
        self.name = ""
        self.channel = ""
        self.closed = False

    def send(self, message):
        if self.closed:
            return
        try:
            self.queue.put_nowait(message)
        except asyncio.QueueFull:
            self.close()

    def close(self):
        self.closed = True
        self.writer.close()

    async def pump(self):
        while True:
            msg = await self.queue.get()
            self.writer.write((json.dumps(msg, ensure_ascii=False) + "\n").encode())
            await asyncio.wait_for(self.writer.drain(), 5)


class Radio:
    def __init__(self, accounts, floor_seconds=30, session_seconds=3600):
        self.accounts = accounts
        self.peers = set()
        self.speakers = {}
        self.floor_seconds = floor_seconds
        self.session_seconds = session_seconds
        self.connections = 0
        self.expiry_tasks = set()

    def members(self, channel):
        return [p for p in self.peers if p.channel == channel and not p.closed]

    def state(self, channel):
        owner = self.speakers.get(channel)
        message = {"type": "state", "members": sorted(p.name for p in self.members(channel)),
                   "speaker": owner[0].name if owner else ""}
        for peer in self.members(channel):
            peer.send(message)

    def release(self, peer):
        if self.speakers.get(peer.channel, (None,))[0] is peer:
            del self.speakers[peer.channel]
            peer.send({"type": "released"})
            self.state(peer.channel)

    async def expire_floor(self, peer, claim):
        await asyncio.sleep(self.floor_seconds)
        if self.speakers.get(peer.channel) == claim:
            self.release(peer)

    def command(self, peer, msg):
        kind = msg.get("type")
        if kind == "claim":
            if peer.channel in self.speakers:
                peer.send({"type": "busy"})
            else:
                claim = (peer, time.monotonic())
                self.speakers[peer.channel] = claim
                peer.send({"type": "granted"})
                self.state(peer.channel)
                task = asyncio.create_task(self.expire_floor(peer, claim))
                # Keep the expiry task alive without accumulating completed tasks.
                self.expiry_tasks.add(task)
                task.add_done_callback(self.expiry_tasks.discard)
        elif kind == "release":
            self.release(peer)
        elif kind == "audio":
            if self.speakers.get(peer.channel, (None,))[0] is not peer:
                return  # Drop late/injected audio; never relay without floor ownership.
            pcm = base64.b64decode(msg.get("data", ""), validate=True)
            if len(pcm) != FRAME_BYTES:
                raise ValueError("Invalid frame")
            for other in self.members(peer.channel):
                if other is not peer:
                    other.send({"type": "audio", "data": msg["data"]})
        elif kind == "ping":
            peer.send({"type": "pong"})
        else:
            raise ValueError("Unknown command")

    async def handle(self, reader, writer):
        if self.connections >= 64:
            writer.close()
            return
        self.connections += 1
        peer = Peer(writer)
        pump = None
        try:
            raw = await asyncio.wait_for(reader.readline(), 10)
            auth = json.loads(raw)
            if not isinstance(auth, dict) or auth.get("type") != "auth":
                raise ValueError("Authentication required")
            token = auth.get("token", "")
            if not isinstance(token, str) or not 32 <= len(token) <= 256:
                raise ValueError("Invalid token")
            account = self.accounts.get(hashlib.sha256(token.encode()).hexdigest())
            channel = auth.get("channel", "")
            if not account or not isinstance(channel, str) or not re.fullmatch(r"[a-zA-Z0-9_-]{1,32}", channel) or channel not in account['channels']:
                raise ValueError("Access denied")
            peer.name, peer.channel = account['name'], channel
            if any(p.name == peer.name and not p.closed for p in self.peers):
                raise ValueError("Account already connected")
            self.peers.add(peer)
            pump = asyncio.create_task(peer.pump())
            # Failed writers must also unblock the reader and release the floor.
            pump.add_done_callback(lambda _: peer.close())
            peer.send({"type": "joined", "name": peer.name, "channel": channel})
            self.state(channel)
            expires = time.monotonic() + self.session_seconds
            window, count = time.monotonic(), 0
            while not peer.closed:
                left = expires - time.monotonic()
                if left <= 0:
                    break
                raw = await asyncio.wait_for(reader.readline(), min(45, left))
                if not raw:
                    break
                now = time.monotonic()
                if now - window >= 1:
                    window, count = now, 0
                count += 1
                if count > 100:
                    raise ValueError("Rate limit")
                msg = json.loads(raw)
                if not isinstance(msg, dict):
                    raise ValueError("Invalid message")
                self.command(peer, msg)
        except (ValueError, TypeError, KeyError, OSError, asyncio.TimeoutError):
            # Do not log tokens, audio or incoming messages.
            pass
        finally:
            self.peers.discard(peer)
            self.release(peer)
            self.state(peer.channel)
            peer.close()
            if pump:
                pump.cancel()
                await asyncio.gather(pump, return_exceptions=True)
            try:
                await writer.wait_closed()
            except OSError:
                pass
            self.connections -= 1


def load_accounts(path):
    accounts = json.loads(Path(path).read_text())
    if not isinstance(accounts, dict) or not accounts:
        raise ValueError("At least one account is required")
    names = set()
    for digest, account in accounts.items():
        name = account['name']
        channels = account['channels']
        if not re.fullmatch(r'[0-9a-f]{64}', digest) or not isinstance(name, str) or not 1 <= len(name) <= 32 or any(ord(c) < 32 for c in name) or name in names:
            raise ValueError("Invalid account")
        if not isinstance(channels, list) or not channels or any(not isinstance(c, str) or not re.fullmatch(r'[a-zA-Z0-9_-]{1,32}', c) for c in channels):
            raise ValueError("Invalid channels")
        names.add(name)
    return accounts


async def main(args):
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    context.load_cert_chain(args.cert, args.key)
    radio = Radio(load_accounts(args.accounts))
    server = await asyncio.start_server(radio.handle, args.host, args.port, ssl=context,
                                        ssl_handshake_timeout=5, limit=MAX_LINE)
    print(f'Khvylia TLS server listening on port {args.port}', flush=True)
    async with server:
        await server.serve_forever()


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--host', default='0.0.0.0')
    parser.add_argument('--port', type=int, default=8443)
    parser.add_argument('--cert', required=True)
    parser.add_argument('--key', required=True)
    parser.add_argument('--accounts', default='tokens.json')
    asyncio.run(main(parser.parse_args()))
