import base64
p = 2**255 - 19
A = 486662
L = 2**252 + 27742317777372353535851937790883648493
A3 = A * pow(3, -1, p) % p
a = (3 - A * A) * pow(3, -1, p) % p
b = (2 * A**3 - 9 * A) * pow(27, -1, p) % p
print('a', hex(a)); print('b', hex(b))

V = dict(
    spriv="AYhVZpbVeX4KS5Qm/W0+9Ye2q3rnVVGmqRICmseWni4=",
    spub="BABVt+mpRLMXiQpIfEq6bj8hlXsdtXIxLsspmMgLNI1SR5mHgDVbjHO2A+U4QlMddGzqyEidzm1AkhtSxSO2Ahg=",
    rpriv="DMxHPri8d7IT23KgLk281zZenMfVHSdeamq0RhwlIBk=",
    rpub="BAheD5rUqTy4V5xR4/6HWmYpopu5CO+KO8BECS0udNqUTSNo91TIqIIy1A4Vh+F94c+n9vAcwXU2bGcfsI5f69Y=",
    ss="HZbc9a4h9kMAReILN5VtvbSYHWQpfIcrZ9pWHlQZUHs=")
d = {k: base64.b64decode(v) for k, v in V.items()}


def ladder(k, u):
    x1, x2, z2, x3, z3, swap = u, 1, 0, u, 1, 0
    for t in reversed(range(256)):
        kt = (k >> t) & 1
        swap ^= kt
        if swap:
            x2, x3, z2, z3 = x3, x2, z3, z2
        swap = kt
        A_ = (x2 + z2) % p; AA = A_ * A_ % p; B_ = (x2 - z2) % p; BB = B_ * B_ % p; E = (AA - BB) % p
        C = (x3 + z3) % p; D = (x3 - z3) % p; DA = D * A_ % p; CB = C * B_ % p
        x3 = (DA + CB) ** 2 % p; z3 = x1 * (DA - CB) ** 2 % p
        x2 = AA * BB % p; z2 = E * (AA + 121665 * E) % p
    if swap:
        x2, z2 = x3, z3
    return x2 * pow(z2, p - 2, p) % p


def clamp(k):
    k &= ~7; k &= ~(1 << 255); k |= 1 << 254
    return k


def isclamped(k):
    return k == clamp(k)


for side in ('s', 'r'):
    pub = d[side + 'pub']
    x = int.from_bytes(pub[1:33], 'big'); y = int.from_bytes(pub[33:], 'big')
    print(side, 'pub len', len(pub), 'prefix', pub[0], 'on curve', (y * y - (x**3 + a * x + b)) % p == 0)
    u = (x - A3) % p
    kbe = int.from_bytes(d[side + 'priv'], 'big'); kle = int.from_bytes(d[side + 'priv'], 'little')
    print(side, 'priv BE clamped?', isclamped(kbe), 'LE clamped?', isclamped(kle))
    for name, k in (('BE', kbe), ('LE', kle)):
        print(side, name, 'k*9 == pub u:', ladder(k, 9) == u, ' clamped(k)*9 == pub u:', ladder(clamp(k), 9) == u)
    # subgroup check of the public point (order L)
    print(side, 'L*P u == 0 (identity)?', ladder(L, u) == 0)

ss = int.from_bytes(d['ss'], 'big')
ur = (int.from_bytes(d['rpub'][1:33], 'big') - A3) % p
us = (int.from_bytes(d['spub'][1:33], 'big') - A3) % p
ks = int.from_bytes(d['spriv'], 'big'); kr = int.from_bytes(d['rpriv'], 'big')
for name, k, u in (('sender*receiverPub', ks, ur), ('receiver*senderPub', kr, us)):
    raw = (ladder(k, u) + A3) % p
    cl = (ladder(clamp(k), u) + A3) % p
    print(name, 'unclamped -> x_W == sharedSecret:', raw == ss, '| JDK-clamped -> x_W == sharedSecret:', cl == ss)
    # an equivalent clamped scalar k' = 2^254 + 8m with k' = +-k mod L
    found = None
    for sign in (1, -1):
        m = ((sign * k - 2**254) * pow(8, -1, L)) % L
        if m < 2**251:
            found = (sign, m)
            break
    print(name, 'equivalent clamped scalar exists:', found is not None, found[0] if found else '')
    if found:
        kc = 2**254 + 8 * found[1]
        print(name, ' clamped equivalent reproduces:', (ladder(kc, u) + A3) % p == ss)
print('ss hex', d['ss'].hex())
