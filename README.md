# EllanMaid

Ellan 服的女仆插件：在 Paper / Leaf 26.x 上用 ModelEngine + CraftEngine 重现 Touhou Little Maid（车万女仆）的玩法。
从零编写，不含也不依赖付费的 XiaoMengMaid 核心；只读取 XiaoMengMaidLib 素材包里的运行时清单（`runtime/*.json`，契约协议 "1"）。

## 功能（0.7.0）

- 祭坛召唤（读 `altar_recipes.json`），消耗玩家的「力量」；击杀怪物掉落力量点
- 女仆跟随 / 坐下 / 回家模式、背包（小/中/大）、吃背包里的食物回血、灭火、磁铁吸物、无线 IO
- 好感度、伤害与死亡、墓碑、相片 / 胶片、神社复活、雕像与手办、女仆信标
- 椅子与床、零食柜、御币、河童指南针、仆从铃、狐狸卷轴、凿子等道具
- 棋盘：中国象棋、国际象棋、五子棋，女仆用引擎对弈
- **干活（0.7）**：用河童的罗盘设好工作点（家模式），白天主人在线时：
  - 背包里有镰刀或锄头 → 在工作点 6 格内收割成熟作物并补种（CustomCrops 作物与原版作物）
  - 工作点附近有加热的锅 / 汤锅 / 蒸笼 → 按万象烹饪的精确配方用背包里的食材做菜（每道 20 秒）
  - 跟着主人、背包里有鱼竿 → 主人钓到鱼时有概率多钓一条（稀有渔获不会复制）
- **干活的代价**：每次干活消耗女仆体力（只能吃背包里的食物恢复），磨损工具，并计入主人每日上限（所有女仆、所有服务器共用）；同时干活最多 2 位
- **力量来源**：MythicMobs 怪物按资源包设掉率（Alex's Mobs 包更高）
- **跨服随行**：跟随中的女仆在主人下线 / 换服时变成照片，到新服务器后自动出来；看家的女仆留在原地
- **换装**：女仆换装券打开全部衣服列表，选一件用掉一张；商店里的空白手办放下后随机一位女仆（盲盒）

## 依赖

- Paper/Leaf 26.1.2+（`folia-supported: true`）
- ModelEngine R4.2.0+，CraftEngine 26.x
- XiaoMengMaidLib 素材包 jar（素材 CC BY-NC-SA 4.0，见 `THIRD-PARTY.md`）

## 安装

1. 放入插件 jar 并启动一次。
2. 把素材包 jar 放进 `plugins/EllanMaid/source/`，执行 `/ellanmaid install`：写入 `CraftEngine/resources/touhou_little_maid`、`ModelEngine/blueprints/tlm_*.bbmodel` 和 `plugins/EllanMaid/runtime`，随后自动 `meg reload`，20 秒后 `ce reload all`。
3. 服务器冷启动时 CraftEngine 可能在 ModelEngine 打包完成前读取它的资源包（日志 `zip END header not found`），此时开服后再执行一次 `/ce reload all`。

命令：`/ellanmaid <reload|install|list|give|summon>`，权限 `ellanmaid.admin`（默认 OP）。

## 构建

```bash
SERVER=/path/to/server EXTRA_CP=CustomCrops.jar:CustomFishing.jar:MythicMobs.jar:SparrowSync.jar ./build.sh
```

可选联动（softdepend）：CustomCrops、CustomFishing、MythicMobs、SparrowSync，缺哪个就关掉对应功能。
所有数值在 `config.yml` 的 `work`、`power-mythic`、`travel`、`wardrobe` 下。

`tools/shop_add.py <服务器目录>`：把女仆道具接到 zMenu 星露谷道具商店最后一个商品后面，并给换装券改名。

## 许可

代码 GPL-2.0（见 `LICENSE`），第三方代码与素材署名见 `THIRD-PARTY.md`。
