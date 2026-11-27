# EllanMaid

Ellan 服的女仆插件：在 Paper / Leaf 26.x 上用 ModelEngine + CraftEngine 重现 Touhou Little Maid（车万女仆）的玩法。
从零编写，不含也不依赖付费的 XiaoMengMaid 核心；只读取 XiaoMengMaidLib 素材包里的运行时清单（`runtime/*.json`，契约协议 "1"）。

## 功能（0.6.0）

- 祭坛召唤（读 `altar_recipes.json`），消耗玩家的「力量」；击杀怪物掉落力量点
- 女仆跟随 / 坐下 / 回家模式、背包（小/中/大）、吃背包里的食物回血、灭火、磁铁吸物、无线 IO
- 好感度、伤害与死亡、墓碑、相片 / 胶片、神社复活、雕像与手办、女仆信标
- 椅子与床、零食柜、御币、河童指南针、仆从铃、狐狸卷轴、凿子等道具
- 棋盘：中国象棋、国际象棋、五子棋，女仆用引擎对弈

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
SERVER=/path/to/server ./build.sh
```

## 许可

代码 GPL-2.0（见 `LICENSE`），第三方代码与素材署名见 `THIRD-PARTY.md`。
