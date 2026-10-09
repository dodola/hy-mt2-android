"""Copy a GGUF, re-quantizing only token_embd.weight (tied lm_head) to a smaller type.
usage: requant_embd.py in.gguf out.gguf {q4_0|q5_0|q8_0}
All other tensors are copied bit-exact (including STQ1_0)."""
import sys
sys.path.insert(0, 'third_party/llama.cpp/gguf-py')
import numpy as np
from gguf import GGUFReader, GGUFWriter, GGMLQuantizationType as T, quants

src, dst, kind = sys.argv[1:4]
target = {'q4_0': T.Q4_0, 'q5_0': T.Q5_0, 'q8_0': T.Q8_0}[kind]
r = GGUFReader(src)
arch = r.fields['general.architecture'].contents()
w = GGUFWriter(dst, arch)
for k, f in r.fields.items():
    if k.startswith('GGUF.') or k in ('general.architecture',):
        continue
    ct = f.types[0]
    w.add_key_value(k, f.contents(), ct, sub_type=f.types[-1] if len(f.types) > 1 else None)
for t in r.tensors:
    if t.name == 'token_embd.weight':
        f32 = quants.dequantize(t.data, t.tensor_type).astype(np.float32)
        q = quants.quantize(f32, target)
        w.add_tensor(t.name, q, raw_shape=q.shape, raw_dtype=target)
    else:
        w.add_tensor(t.name, t.data, raw_shape=t.data.shape, raw_dtype=t.tensor_type)
w.write_header_to_file(); w.write_kv_data_to_file(); w.write_tensors_to_file(); w.close()
print('wrote', dst)
