# Test vectors of protocol v2 (docs/live-v2/spec.md, section 18; checked by LiveVectorsTest and LiveAeadVectorsTest). Needs: pip install cryptography. Run: python3 -I vectors.py
import hmac, hashlib, struct
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

def varint(n):
    out = bytearray()
    while True:
        b = n & 0x7f; n >>= 7
        if n: out.append(b | 0x80)
        else: out.append(b); return bytes(out)
def zz(n): return (n << 1) ^ (n >> 63)
def key(field, wt): return varint((field << 3) | wt)
def f_varint(field, n): return key(field, 0) + varint(n)
def f_bytes(field, b): return key(field, 2) + varint(len(b)) + b
def f_fixed32(field, n): return key(field, 5) + struct.pack('<I', n)

# Up: seq=1, ack_state=0, time_ms, two fixes (chained), one sighting, chat_after=0
time_ms = 1760457600000
fix1 = f_varint(1, zz(504501000)) + f_varint(2, zz(305234000)) + f_varint(3, 42) + f_varint(4, zz(-1200))
fix2 = f_varint(1, zz(9)) + f_varint(2, zz(-14)) + f_varint(3, 38) + f_varint(4, zz(-200))
sight = f_fixed32(1, 0x3fa91c07) + f_varint(2, zz(-71)) + f_varint(3, zz(-850))
up = f_varint(1, 1) + f_varint(3, time_ms) + f_bytes(4, fix1) + f_bytes(4, fix2) + f_bytes(5, sight) + f_varint(7, 0)
print('Up protobuf', len(up), up.hex())

up_key = bytes(range(16))
down_key = bytes(range(16, 32))
session_id = bytes.fromhex('a1b2c3d4e5f60718')
pn = 1
header = bytes([0x21]) + session_id + struct.pack('>I', pn)
plaintext = bytes([0x01]) + up
nonce = bytes(8) + struct.pack('>I', pn)
ct = AESGCM(up_key).encrypt(nonce, plaintext, header)
dg = header + ct
print('nonce', nonce.hex())
print('header', header.hex())
print('datagram', len(dg), dg.hex())

reset_secret = bytes(range(32, 64))
reset_tag = hmac.new(reset_secret, session_id, hashlib.sha256).digest()[:16]
print('reset_tag', reset_tag.hex())
print('reset datagram', (bytes([0x22]) + session_id + reset_tag).hex())

# Down: reply_to=1, ack_up=1, server_time, state_id=1, base 0, state{ me{player_id, role SEEKER, radar_on}, tokens{hider:[...]}}
me = f_bytes(1, b'p7Qk2Lm9Xa1B') + f_varint(2, 1) + f_varint(11, 1)
tokens = key(2, 2) + varint(8) + struct.pack('<I', 0x3fa91c07) + struct.pack('<I', 0x0b44e2d1)
state = f_bytes(4, me) + f_bytes(7, tokens)
down = f_varint(1, 1) + f_varint(2, 1) + f_varint(3, 1760457600150) + f_varint(4, 1) + f_bytes(6, state)
print('Down protobuf', len(down), down.hex())
dheader = bytes([0x21]) + session_id + struct.pack('>I', 1)
dct = AESGCM(down_key).encrypt(bytes(8) + struct.pack('>I', 1), bytes([0x01]) + down, dheader)
print('down datagram', len(dheader + dct), (dheader + dct).hex())
