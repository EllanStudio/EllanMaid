"""Append the maid items to 星露谷道具商店 right after the last item (no gaps) and rename the outfit ticket.
usage: python3 shop_add.py <server dir>"""
import json, re, sys, shutil
srv = sys.argv[1]
shop = f'{srv}/plugins/zMenu/inventories/星露谷道具商店.yml'
GRID = [5, 6, 7, 14, 15, 16, 23, 24, 25, 32, 33, 34, 41, 42, 43]
ITEMS = [
    ('touhou_little_maid:change_maid_model', '女仆换装券', 3000, ['<#b7afa0>类型  <#f5a3c7>女仆道具', '<#b7afa0>用法  <#f5f1e8>拿着它右键自己的女仆', '<#b7afa0>效果  <#f5f1e8>从全部衣服里挑一件换上', '<#8f887c>换好后用掉一张']),
    ('touhou_little_maid:maid_backpack_small', '小背包', 5000, ['<#b7afa0>类型  <#f5a3c7>女仆道具', '<#b7afa0>容量  <#f5f1e8>18 格', '<#b7afa0>用法  <#f5f1e8>拿着它右键自己的女仆', '<#8f887c>也能在祭坛用力量合成']),
    ('touhou_little_maid:maid_backpack_middle', '中背包', 12000, ['<#b7afa0>类型  <#f5a3c7>女仆道具', '<#b7afa0>容量  <#f5f1e8>27 格', '<#b7afa0>用法  <#f5f1e8>拿着它右键自己的女仆', '<#8f887c>也能在祭坛用力量合成']),
    ('touhou_little_maid:maid_backpack_big', '大背包', 25000, ['<#b7afa0>类型  <#f5a3c7>女仆道具', '<#b7afa0>容量  <#f5f1e8>36 格', '<#b7afa0>用法  <#f5f1e8>拿着它右键自己的女仆', '<#8f887c>也能在祭坛用力量合成']),
    ('touhou_little_maid:garage_kit', '女仆手办盲盒', 8000, ['<#b7afa0>类型  <#f5a3c7>摆件', '<#b7afa0>内容  <#f5f1e8>随机一位女仆的手办', '<#b7afa0>用法  <#f5f1e8>放下后揭晓', '<#8f887c>用自己女仆的照片也能做手办']),
]
s = open(shop, encoding='utf-8').read()
if 'touhou_little_maid:' in s:
    sys.exit('already added')
shutil.copy(shop, shop + '.bak-maid-20261127')
nums = [int(n) for n in re.findall(r'^  item_(\d+):', s, re.M)]
pos = [(int(p), int(sl)) for p, sl in re.findall(r'^      page: (\d+)\n      slot: (\d+)', s, re.M)]
page, slot = max(pos)
n = max(nums)
out = []
for iid, name, price, desc in ITEMS:
    i = GRID.index(slot) + 1
    if i >= len(GRID): page, slot = page + 1, GRID[0]
    else: slot = GRID[i]
    n += 1
    lines = [f'  item_{n}:', '    pattern:', '      file-name: shop_open_tool_select_with_description',
             f'      page: {page}', f'      slot: {slot}', '      currency_type: VAULT', '      currency_symbol: <#ffeea2>艾尔岚金币',
             f"      price: '{price}'", f'      item_id: {iid}', f'      item_name: {name}']
    for k, d in enumerate(desc, 1): lines.append(f'      description_{k}: "{d}"')
    lines += ["      balance_placeholder: '%excellenteconomy_balance_raw_ellan_coin%'", '      select_inventory: 星露谷道具购买选择']
    out.append('\n'.join(lines))
    print(f'item_{n} page {page} slot {slot} {name} {price}')
s = s.rstrip('\n') + '\n' + '\n'.join(out) + '\n'
open(shop, 'w', encoding='utf-8').write(s)

items = f'{srv}/plugins/CraftEngine/resources/touhou_little_maid/configuration/items.yml'
shutil.copy(items, items + '.bak-maid-20261127')
j = json.load(open(items, encoding='utf-8'))
c = j['items']['touhou_little_maid:change_maid_model']
c['data']['item_name'] = '<!i>女仆换装券'
c['data']['components']['minecraft:max_stack_size'] = 16
c['data']['lore'] = ['<!i><#b7afa0>拿着它右键自己的女仆，挑一件衣服换上', '<!i><#8f887c>换好后用掉一张']
open(items, 'w', encoding='utf-8').write(json.dumps(j, ensure_ascii=False))
print('renamed change_maid_model')
