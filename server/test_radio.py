import asyncio
import base64
import hashlib
import json
import ssl
import subprocess
import tempfile
import unittest
from pathlib import Path
from radio_server import Radio, MAX_LINE


class RadioTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        cert, key = Path(self.tmp.name)/'cert.pem', Path(self.tmp.name)/'key.pem'
        subprocess.run(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1', '-keyout', str(key), '-out', str(cert), '-subj', '/CN=localhost', '-addext', 'subjectAltName=DNS:localhost'], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ctx.load_cert_chain(cert, key)
        self.client_ctx = ssl.create_default_context(cafile=str(cert))
        self.tokens = {name: name.ljust(43, 'x') for name in ['alice', 'bob', 'carol']}
        accounts = {hashlib.sha256(t.encode()).hexdigest(): {'name': n, 'channels': ['general', 'other']} for n, t in self.tokens.items()}
        self.radio = Radio(accounts, floor_seconds=0.6)
        self.server = await asyncio.start_server(self.radio.handle, '127.0.0.1', 0, ssl=ctx, limit=MAX_LINE)
        self.port = self.server.sockets[0].getsockname()[1]
        self.clients = []

    async def asyncTearDown(self):
        for _, w in self.clients:
            w.close()
        for _, w in self.clients:
            try: await w.wait_closed()
            except OSError: pass
        self.server.close()
        await self.server.wait_closed()
        for task in list(self.radio.expiry_tasks): task.cancel()
        await asyncio.gather(*self.radio.expiry_tasks, return_exceptions=True)
        await asyncio.sleep(0.02)
        self.tmp.cleanup()

    async def connect(self, name, channel='general', token=None):
        r, w = await asyncio.open_connection('127.0.0.1', self.port, ssl=self.client_ctx, server_hostname='localhost')
        self.clients.append((r,w))
        await self.send(w, {'type':'auth', 'token': token or self.tokens[name], 'channel':channel})
        return r,w

    async def send(self, w, msg):
        w.write((json.dumps(msg)+'\n').encode()); await w.drain()

    async def until(self, r, kind):
        async def read():
            while True:
                raw = await r.readline()
                if not raw: raise EOFError()
                msg = json.loads(raw)
                if msg['type'] == kind: return msg
        return await asyncio.wait_for(read(), 2)

    async def test_bad_token_and_channel_denied(self):
        r,_ = await self.connect('alice', token='bad'.ljust(43, 'x'))
        self.assertEqual(await asyncio.wait_for(r.readline(), 1), b'')
        r,_ = await self.connect('alice', channel='private')
        self.assertEqual(await asyncio.wait_for(r.readline(), 1), b'')

    async def test_floor_audio_isolation_and_release(self):
        a,aw = await self.connect('alice'); await self.until(a,'joined')
        b,bw = await self.connect('bob'); await self.until(b,'joined')
        c,cw = await self.connect('carol','other'); await self.until(c,'joined'); await self.until(c,'state')
        await self.send(aw, {'type':'claim'}); await self.until(a,'granted')
        await self.send(bw, {'type':'claim'}); await self.until(b,'busy')
        payload = base64.b64encode(b'\x00\x10'*320).decode()
        await self.send(aw, {'type':'audio','data':payload})
        self.assertEqual((await self.until(b,'audio'))['data'], payload)
        await self.send(cw, {'type':'ping'})
        self.assertEqual(json.loads(await c.readline())['type'], 'pong')
        await self.send(aw, {'type':'release'}); await self.until(a,'released')
        await self.send(bw, {'type':'claim'}); await self.until(b,'granted')

    async def test_disconnect_frees_floor(self):
        a,aw = await self.connect('alice'); await self.until(a,'joined')
        b,bw = await self.connect('bob'); await self.until(b,'joined')
        await self.send(aw, {'type':'claim'}); await self.until(a,'granted')
        aw.close(); await aw.wait_closed()
        while (await self.until(b,'state'))['speaker']: pass
        await self.send(bw, {'type':'claim'}); await self.until(b,'granted')

    async def test_floor_timeout_and_injected_audio_dropped(self):
        a,aw = await self.connect('alice'); await self.until(a,'joined')
        b,bw = await self.connect('bob'); await self.until(b,'joined')
        await self.until(b,'state')
        await self.send(aw, {'type':'audio','data':base64.b64encode(bytes(640)).decode()})
        await self.send(aw, {'type':'ping'}); await self.until(a,'pong')
        await self.send(bw, {'type':'ping'})
        self.assertEqual(json.loads(await b.readline())['type'],'pong')
        await self.send(aw, {'type':'claim'}); await self.until(a,'granted')
        await self.until(a,'released')
        await self.send(bw, {'type':'claim'}); await self.until(b,'granted')

    async def test_oversized_frame_disconnects(self):
        a,aw = await self.connect('alice'); await self.until(a,'joined'); await self.until(a,'state')
        aw.write(b'x' * (MAX_LINE+2) + b'\n'); await aw.drain()
        self.assertEqual(await asyncio.wait_for(a.readline(),1), b'')

    async def test_invalid_pcm_disconnects_speaker(self):
        a,aw = await self.connect('alice'); await self.until(a,'joined')
        await self.send(aw, {'type':'claim'}); await self.until(a,'granted'); await self.until(a,'state')
        await self.send(aw, {'type':'audio','data':'AAAA'})
        self.assertEqual(await asyncio.wait_for(a.readline(),1), b'')

    async def test_duplicate_account_rejected(self):
        a,_ = await self.connect('alice'); await self.until(a,'joined')
        b,_ = await self.connect('alice')
        self.assertEqual(await asyncio.wait_for(b.readline(),1), b'')

    async def test_certificate_hostname_checked(self):
        with self.assertRaises(ssl.SSLCertVerificationError):
            await asyncio.open_connection('127.0.0.1', self.port, ssl=self.client_ctx, server_hostname='wrong.example')


if __name__ == '__main__': unittest.main()
