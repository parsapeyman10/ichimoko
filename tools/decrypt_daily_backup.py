#!/usr/bin/env python3
"""Decrypt an Aurum daily envelope locally; install cryptography first. Never paste a password in shell history."""
import argparse
import base64
import getpass
import gzip
import json
from pathlib import Path
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.kdf.pbkdf2 import PBKDF2HMAC

p = argparse.ArgumentParser()
p.add_argument('envelope', type=Path)
p.add_argument('output', type=Path)
a = p.parse_args()
e = json.loads(a.envelope.read_text(encoding='utf-8'))
if e.get('format') != 'AurumDailyBackup' or e.get('version') != 1 or not 310000 <= e.get('iterations', 0) <= 1000000:
    raise SystemExit('Unsupported or invalid envelope')
b64 = lambda s: base64.b64decode(s, validate=True)
password = getpass.getpass('Backup passphrase: ').encode('utf-8')
key = PBKDF2HMAC(algorithm=hashes.SHA256(), length=32, salt=b64(e['salt']), iterations=e['iterations']).derive(password)
data = AESGCM(key).decrypt(b64(e['nonce']), b64(e['ciphertext']), b'AurumDailyBackup:1')
plain = gzip.decompress(data)
json.loads(plain)
with a.output.open('xb') as f:
    f.write(plain)
print('Decrypted locally:', a.output)
