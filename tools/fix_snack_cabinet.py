"""Fixes the snack cabinet blueprint: several cubes carry inflate values with the wrong sign or a bogus 15.8,
which blows the glass up to ~3 blocks. Each cube is brought back to at most 16 px wide (same as Installer.fixSnackCabinet).
usage: python3 fix_snack_cabinet.py <ModelEngine blueprints dir>; then meg reload, then ce reload all."""
import json, sys, shutil, os
p = os.path.join(sys.argv[1], 'tlm_block_snack_cabinet.bbmodel')
bak = os.path.join(os.path.dirname(os.path.abspath(sys.argv[1])), 'blueprint-backups')  # not inside blueprints: ModelEngine would load it
os.makedirs(bak, exist_ok=True)
if not os.path.exists(os.path.join(bak, 'tlm_block_snack_cabinet.bbmodel.bak-inflate')): shutil.copy(p, os.path.join(bak, 'tlm_block_snack_cabinet.bbmodel.bak-inflate'))
d = json.load(open(p, encoding='utf-8'))
for e in d['elements']:
    inf = e.get('inflate')
    if inf is None: continue
    w = max(e['to'][0] - e['from'][0], e['to'][2] - e['from'][2])
    if w + 2 * inf > 16.01:
        new = -abs(inf) if abs(inf) < w / 2 else (15.8 - w) / 2  # wrong sign, or a size written as inflate
        if w + 2 * new > 16.01: new = (15.8 - w) / 2
        new = round(new, 3)
        print(e['name'], 'inflate', inf, '->', new, 'width', w, '->', round(w + 2 * new, 2))
        e['inflate'] = new
json.dump(d, open(p, 'w', encoding='utf-8'), ensure_ascii=False)
