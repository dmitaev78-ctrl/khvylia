"""Run on the server: creates a random token, stores only its SHA-256 hash."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import secrets
from radio_server import load_accounts

parser = argparse.ArgumentParser()
parser.add_argument('name')
parser.add_argument('channels', nargs='+')
parser.add_argument('--file', default='tokens.json')
args = parser.parse_args()
path = Path(args.file)
accounts = load_accounts(path) if path.exists() else {}
if any(a['name'] == args.name for a in accounts.values()):
    raise SystemExit('Name already exists')
token = secrets.token_urlsafe(32)
accounts[hashlib.sha256(token.encode()).hexdigest()] = {'name': args.name, 'channels': args.channels}
tmp = path.with_suffix('.tmp')
try:
    with os.fdopen(os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'w') as f:
        json.dump(accounts, f, ensure_ascii=False, indent=2)
    load_accounts(tmp)
    os.replace(tmp, path)
except Exception:
    tmp.unlink(missing_ok=True)
    raise
print('Token (shown once; send privately to this user):')
print(token)
