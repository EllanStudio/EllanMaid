"""Adds the 'attack' animation (same as Installer.attackAnimation) to installed maid blueprints.
usage: python3 patch_attack.py <ModelEngine blueprints dir>; then `meg reload` and, after it finishes, `ce reload all`."""
import json, glob, sys, uuid
TRACKS = {'armRight': [(0, 0, 0), (150, 0, -15), (40, 0, 0), (0, 0, 0)], 'body': [(0, 0, 0), (0, -15, 0), (0, 10, 0), (0, 0, 0)]}
TIMES = [0, 0.1, 0.25, 0.4]
done = 0
for f in glob.glob(f'{sys.argv[1]}/tlm_maid_*.bbmodel'):
    d = json.load(open(f, encoding='utf-8'))
    anims = d.setdefault('animations', [])
    if any(a.get('name') == 'attack' for a in anims): continue
    groups = {g['name']: g['uuid'] for g in d.get('groups', []) if isinstance(g, dict) and 'name' in g}
    animators = {}
    for bone, vals in TRACKS.items():
        if bone not in groups: continue
        animators[groups[bone]] = {'name': bone, 'type': 'bone', 'keyframes': [
            {'channel': 'rotation', 'data_points': [{'x': x, 'y': y, 'z': z}], 'uuid': str(uuid.uuid4()), 'time': t, 'interpolation': 'linear'}
            for t, (x, y, z) in zip(TIMES, vals)]}
    if not animators: continue
    anims.append({'uuid': str(uuid.uuid4()), 'name': 'attack', 'loop': 'once', 'override': False, 'length': 0.4, 'snapping': 20,
                  'selected': False, 'anim_time_update': '', 'blend_weight': '', 'start_delay': '', 'loop_delay': '', 'animators': animators})
    json.dump(d, open(f, 'w', encoding='utf-8'), ensure_ascii=False)
    done += 1
print('attack added to', done)
