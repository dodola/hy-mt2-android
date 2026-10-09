"""Rewrite tensor type ids in a GGUF header in place (data blob untouched).
usage: remap_gguf_types.py file.gguf 42:43 [40:99 ...]
Used to map Tencent's STQ1_0 id (42) onto the id used by upstream llama.cpp PR #22836 (43)."""
import struct, sys
path = sys.argv[1]
m = {int(a): int(b) for a, b in (x.split(':') for x in sys.argv[2:])}
f = open(path, 'r+b')
u32 = lambda: struct.unpack('<I', f.read(4))[0]
u64 = lambda: struct.unpack('<Q', f.read(8))[0]
def s(): return f.read(u64()).decode('utf8', 'replace')
FM = {0:'B',1:'b',2:'H',3:'h',4:'I',5:'i',6:'f',7:'?',10:'Q',11:'q',12:'d'}
def val(t):
    if t == 8: return s()
    if t == 9:
        et = u32(); n = u64()
        for _ in range(n): val(et)
        return None
    sz = struct.calcsize(FM[t]); return struct.unpack('<' + FM[t], f.read(sz))[0]
assert f.read(4) == b'GGUF'; u32(); nt = u64(); nkv = u64()
for _ in range(nkv):
    s(); val(u32())
n = 0
for _ in range(nt):
    s(); nd = u32(); [u64() for _ in range(nd)]
    pos = f.tell(); ty = u32(); u64()
    if ty in m:
        end = f.tell(); f.seek(pos); f.write(struct.pack('<I', m[ty])); f.seek(end); n += 1
print(f'remapped {n} tensors in {path}')
