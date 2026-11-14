import base64
p = 2**255 - 19
A = 486662
L = 2**252 + 27742317777372353535851937790883648493
A3 = A * pow(3, -1, p) % p
a = (3 - A * A) * pow(3, -1, p) % p
b = (2 * A**3 - 9 * A) * pow(27, -1, p) % p



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



