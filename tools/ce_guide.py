"""Altar crafting recipe + clickable altar + how-to lore on the maid items (CraftEngine pack touhou_little_maid).
usage: python3 ce_guide.py <server dir>   (backups *.bak-guide-20261127, rerunnable)"""
import json, shutil, sys, os
d = f'{sys.argv[1]}/plugins/CraftEngine/resources/touhou_little_maid/configuration'
NS = 'touhou_little_maid:'
G = '<!i><#b7afa0>'  # label colour of the shop descriptions
LORE = {
    'altar': [G + '召唤女仆：把钻石、青金石、金锭、红石、铁锭、煤', G + '各一个扔在祭坛旁边，右键祭坛（花 0.5 力量）', G + '也能合成背包、相机、罗盘等女仆道具', '<!i><#8f887c>输入 /maid 查看完整玩法'],
    'maid_spawn_egg': [G + '右键地面，召唤一位随机衣服的女仆', G + '她会认你为主人', '<!i><#8f887c>输入 /maid 查看怎么照顾她'],
    'power_point': [G + '右键吃掉，增加 0.3~0.9 力量', G + '祭坛召唤和合成要用力量（最多攒 5.0）', '<!i><#8f887c>完成限时订单可以获得'],
    'kappa_compass': [G + '依次右键三个方块：工作、休闲、睡觉位置', G + '再右键女仆，她就会在家干活', G + '潜行右键女仆：取消看家，继续跟着你'],
    'camera': [G + '右键自己的女仆，把她拍进照片里'],
    'photo': [G + '右键地面，放出照片里的女仆'],
    'film': [G + '带到祭坛或神龛，让倒下的女仆回来'],
    'maid_backpack_small': [G + '右键自己的女仆给她背上（18 格）'],
    'maid_backpack_middle': [G + '右键自己的女仆给她背上（27 格）'],
    'maid_backpack_big': [G + '右键自己的女仆给她背上（36 格）'],
    'garage_kit': [G + '放下后摆出女仆手办', G + '空白手办会随机一位女仆'],
    'snack_cabinet': [G + '手持食物右键放进去，空手右键取出'],
    'change_maid_model': [G + '拿着它右键自己的女仆，挑一件衣服换上', '<!i><#8f887c>换好后用掉一张'],
    'model_switcher': [G + '拿着它右键女仆绑定，放到地上', G + '右键挑选要轮换的衣服', G + '旁边给红石信号或潜行右键：换下一件'],
}
items_p, rec_p = f'{d}/items.yml', f'{d}/recipes.yml'
for p in (items_p, rec_p):
    if not os.path.exists(p + '.bak-guide-20261127'): shutil.copy(p, p + '.bak-guide-20261127')
items = json.load(open(items_p, encoding='utf-8'))
for k, lore in LORE.items():
    items['items'][NS + k]['data']['lore'] = lore
alt = items['items'][NS + 'altar']['behavior']['furniture']['variants']['ground']
alt['hitboxes'] = [{'type': 'shulker', 'position': '0,0,0', 'scale': 1.0, 'blocks_building': True, 'interactive': True, 'invisible': True, 'can_be_hit_by_projectile': False}]
open(items_p, 'w', encoding='utf-8').write(json.dumps(items, ensure_ascii=False))
rec = json.load(open(rec_p, encoding='utf-8'))
rec['recipes'][NS + 'altar'] = {'type': 'shaped', 'pattern': ['RQR', 'LGL', 'RQR'],
    'ingredients': {'R': 'minecraft:red_wool', 'Q': 'minecraft:quartz_block', 'L': '#minecraft:logs', 'G': 'minecraft:gold_block'},
    'result': {'id': NS + 'altar', 'count': 1}}
open(rec_p, 'w', encoding='utf-8').write(json.dumps(rec, ensure_ascii=False, indent=2))
print('lore', len(LORE), 'altar hitbox + recipe done')
