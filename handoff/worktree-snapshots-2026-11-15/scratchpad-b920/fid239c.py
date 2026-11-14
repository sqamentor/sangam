import base64, json, sys
sys.path.insert(0, sys.argv[1])
from fid239lib import ladder, p, A3, L, a, b
vec = json.load(open(sys.argv[2], encoding='utf-8'))
out = {}
for name, v in vec.items():
    res = {}
    keys = {}
    for side in ('sender', 'receiver'):
        k = int.from_bytes(base64.b64decode(v[side]['privateKey']), 'big')
        pub = base64.b64decode(v[side]['publicKey'])
        x = int.from_bytes(pub[1:33], 'big'); y = int.from_bytes(pub[33:], 'big')
        u = (x - A3) % p
        ok = len(pub) == 65 and pub[0] == 4 and (y * y - (x**3 + a * x + b)) % p == 0 and ladder(k, 9) == u
        eq = None
        for sign in (1, -1):
            m = ((sign * k - 2**254) * pow(8, -1, L)) % L
            if m < 2**251:
                eq = 2**254 + 8 * m
                break
        keys[side] = (k, u, eq)
        res[side + 'Consistent'] = ok
        res[side + 'ClampedEquivalent'] = eq is not None
        res[side + 'ClampedAsIs'] = (k & ~7 & ~(1 << 255)) | (1 << 254) == k
    ss1 = (ladder(keys['sender'][0], keys['receiver'][1]) + A3) % p
    ss2 = (ladder(keys['receiver'][0], keys['sender'][1]) + A3) % p
    res['sharedAgrees'] = ss1 == ss2
    if 'sharedSecret' in v:
        res['sharedMatchesVector'] = ss1 == int.from_bytes(base64.b64decode(v['sharedSecret']), 'big')
    res['sharedSecret'] = base64.b64encode(ss1.to_bytes(32, 'big')).decode()
    eq = keys['receiver'][2]
    res['receiverClampedLE'] = eq.to_bytes(32, 'little').hex() if eq else None
    res['senderU'] = str(keys['sender'][1])
    out[name] = res
print(json.dumps(out, indent=1))
