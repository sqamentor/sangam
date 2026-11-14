import base64
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
key = bytes(range(32))
iv = bytes(range(12))
aad = 't1|d1|op-1|u1'.encode('utf-8')
pt = '{"secret":"नोट"}'.encode('utf-8')
ct = AESGCM(key).encrypt(iv, pt, aad)
print(base64.b64encode(key).decode())
print(base64.b64encode(iv).decode())
print(base64.b64encode(ct).decode())
print(len(pt), len(ct))
