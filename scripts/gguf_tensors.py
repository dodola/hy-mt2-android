"""Dump GGUF header: metadata keys + tensor (name, type id, dims, offset). usage: gguf_tensors.py file [n]"""
import struct, sys
f = open(sys.argv[1], 'rb'); N = int(sys.argv[2]) if len(sys.argv) > 2 else 12
u32 = lambda: struct.unpack('<I', f.read(4))[0]
u64 = lambda: struct.unpack('<Q', f.read(8))[0]
def s(): return f.read(u64()).decode('utf8', 'replace')
SZ = {0:1,1:1,2:2,3:2,4:4,5:4,6:4,7:1,10:8,11:8,12:8}
def val(t):
    if t == 8: return s()
    if t == 9:
        et = u32(); n = u64(); return [val(et) for _ in range(n)] if n < 64 else (f.seek(0, 1), [f.read(0) for _ in range(0)], [skip(et, n)])[2] and None
    return struct.unpack('<' + 'BbHhIifBqQd'[[0,1,2,3,4,5,6,7,10,11,12].index(t)] if False else '<' + {0:'B',1:'b',2:'H',3:'h',4:'I',5:'i',6:'f',7:'?',10:'Q',11:'q',12:'d'}[t], f.read(SZ[t]))[0]
def skip(et, n):
    for _ in range(n): val(et)
assert f.read(4) == b'GGUF'; ver = u32(); nt = u64(); nkv = u64()
print('version', ver, 'tensors', nt, 'kv', nkv)
for _ in range(nkv):
    k = s(); t = u32(); v = val(t)
    if not isinstance(v, list) and (k.startswith('general.') or 'quant' in k or k.endswith(('block_count','embedding_length','feed_forward_length','head_count','head_count_kv','context_length','vocab_size','rope.freq_base'))): print(' ', k, v)
types = {}
for i in range(nt):
    name = s(); nd = u32(); dims = [u64() for _ in range(nd)]; ty = u32(); off = u64()
    types.setdefault(ty, 0); types[ty] += 1
    if i < N: print(f'{name:40s} type={ty:3d} dims={dims} off={off}')
print('type histogram', types)
