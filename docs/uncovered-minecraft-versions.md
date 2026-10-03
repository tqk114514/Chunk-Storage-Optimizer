# 未覆盖的 Minecraft 版本

支持范围是 Minecraft 1.21 至 26.3。`supported-versions.csv` 是已经提供构建的版本；本文件记录该范围内
**仍未提供**的部分及原因。1.20.x 及更早的版本不在支持范围内，见第 5 节。

数据取自 2026-10-03，来源为 `maven.neoforged.net` 的版本 API、Mojang 的 `version_manifest_v2.json`、
`maven.fabricmc.net`、`meta.fabricmc.net` 与 `maven.terraformersmc.com`，以及本机缓存的 Minecraft jar。
复核方式：

```bash
tools/neoforge-floor.sh <minecraft 版本号>     # 该 loader 线的下限（最老稳定版，无则最新 beta）
tools/check-loader-versions.sh                 # 把 csv 的 pin 与上游逐一核对
```

1.21 至 26.3 共 **17 个正式版**。NeoForge 侧覆盖 16 行、Fabric 侧 12 行。缺口有两处：NeoForge 的
26.3（第 2 节，loader 改了配置 API）与 Fabric 的 26.x（第 3 节，loom 不支持「无映射」）。

## 1. NeoForge：1.21 线没有缺口了，但其中 6 行以 beta 为下限

NeoForge 一有新 Minecraft 就关掉旧线，所以只活了一周的版本会留下一条**以 beta 收尾**的线。这些线不会
再有稳定版，等下去等于永远不支持，所以下限取该线**最新 beta**：

| Minecraft | loader 线 | 稳定版 | 下限（= 该线最新 beta） | 该线最后构建 |
|---|---|---|---|---|
| 1.21.2 | 21.2 | 无 | `21.2.1-beta` | 2024-10-23 |
| 1.21.6 | 21.6 | 无 | `21.6.20-beta` | 2025-06-26 |
| 1.21.7 | 21.7 | 无 | `21.7.25-beta` | 2025-07-16 |
| 1.21.9 | 21.9 | 无 | `21.9.16-beta` | 2025-10-07 |
| 26.1.0 | 26.1.0 | 无 | `26.1.0.19-beta` | 2026-03-31 |
| 26.1.1 | 26.1.1 | 无 | `26.1.1.15-beta` | 2026-04-08 |

六条线都已冻结（不再收构建），所以这些下限是定值，检查脚本可以精确比对。**26.3.0 不在此列，见
第 2 节。**

**为什么用最新而不是最老 beta**：最老那个更可能带着「编不过的构建输入」。先例是 21.10.63——它发布的
binpatch 是用被改过的 base jar 生成的，任何走 binpatch 的环境都编不过，四天后才由 21.10.64 修好。最新
beta 离这类问题最远。

**1.21.10 是唯一的例外行**：该线的最老稳定版是 21.10.63，也就是上面那个编不过的构建，所以 csv 用
21.10.64。检查脚本里单独列了这条例外。

## 2. NeoForge 26.3：FancyModLoader 12 改了配置 API

26.3 的 loader 线仍在收构建（最新 `26.3.0.43-beta`），但**它编不过**，原因是 NeoForge 26.3 把
FancyModLoader 从 **11.0.16 升到 12.0.8**，而 FML 12 重构了配置类型：

| FML 11 | FML 12 | 语义 |
|---|---|---|
| `COMMON` | **`LOCAL`** | 两侧都加载、不同步（FML 12 源码：*Local mod config ... loaded on both servers and clients ... not synced*） |
| `SERVER` | **`SYNCED`** | 服务端加载、同步给客户端 |
| `CLIENT` | `CLIENT` | 不变 |
| `STARTUP` | `STARTUP` | 不变 |

CI 实测（run `37091086268`）：`26.3.0` 的 `:neoforge:compileJava` 报

```
ChunkStorageOptimizer.java:27: error: cannot find symbol
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
                                          ^ symbol: variable COMMON  location: class Type
```

**不是 csv 能解决的**：`COMMON` 与 `LOCAL` 的**配置文件后缀不同**（`-common` / `-local`），所以换过去
之后 `config/chunkstorageoptimizer-common.toml` 会被忽略——这是用户可见的迁移。加入条件：

1. 给配置类型加一条**版本缝**（`common/src/version/<family>/java` 已有这套机制，目前只用于命令权限），
   让 26.2 及更早用 `COMMON`、26.3 起用 `LOCAL`；
2. 处理配置文件名从 `-common` 到 `-local` 的迁移（至少要在日志里提示）；
3. 26.3 的 loader 仍是 beta，API 还可能再动——等它出稳定版更稳妥。

第 1 节里那六条线的构建**已在 CI 验证通过**（同一个 run，六个 build job 全绿）。

## 3. Fabric：26.x 无构建

26.1.0 / 26.1.1 / 26.1.2 / 26.2.0 / 26.3.0 五行的 Fabric API 列都是 `-`。1.21 至 26.3 的 17 个正式版
Fabric 侧**全都有**稳定版加载器、Fabric API 与 Mod Menu，所以缺口来自构建工具链，不来自生态：

| 事实 | 依据 |
|---|---|
| 26.x 的客户端 jar 本身就是 Mojang 名字 | 本机 `minecraft_26.1.2_client.jar` 内直接有 `net/minecraft/world/level/chunk/storage/RegionFileStorage.class`；26.2 与 26.3 的 `version.json` 只剩 client / server 两项，没有 `client_mappings`（1.21.11 仍有） |
| Fabric 对 26.3 不发 yarn，intermediary 是 `0.0.0`（恒等映射） | `meta.fabricmc.net/v2/versions/yarn/26.3` 返回空列表 |
| loom 的稳定版不接受"无映射" | 实测 1.15.5 / 1.16.3 / 1.17.21 / 1.18.2：写 `loom.officialMojangMappings()` 报 `Failed to find official mojang mappings for 26.3`，不写报 `Configuration 'mappings' has no dependencies`，自造 identity tiny 映射死在 `Provider "jar" not found` |
| 快照工具链可用，本项目不采用 | Mod Menu 自身用 loom `1.18-SNAPSHOT` + Gradle 9.7.1 构建 26.x，其 `build.gradle` 没有 mappings 行；loom 稳定版对 Gradle 的下限（1.15.5→9.2.0、1.18.2→9.7.0）也要求先升 wrapper |

加入条件：loom 出现支持"无映射"的稳定版。届时改 `fabric/build.gradle` 中按 `26.` 前缀拒绝的分支，
并把 csv 两列填上。**注意 26.x 的 Fabric API 版本号带的是游戏写法**（`+26.2`，不是 `+26.2.0`），
`check-loader-versions.sh` 两种写法都接受。

1.21.2 / 1.21.6 / 1.21.7 / 1.21.9 四行反过来曾经是只有 Fabric 有构建：NeoForge 从未为这四版发布稳定
构建。第 1 节之后它们两侧都有了。

## 4. 版本号的两种写法

两段式的正式版号省略了 patch 位：游戏版本号为 `26.2`，NeoForge 的 artifact 线为 `26.2.0`
（`neoforge-26.2.0.57` 的 moddev-config 指向 `neoform:26.2-2`）。当前行键采用 loader 的写法，jar 中
`modId="minecraft"` 的范围是 `[26.2.0]`。

已验证这不影响加载：NeoForge 26.2.0.57 服务端加 `chunkstorageoptimizer-26.2.0-1.0.3.jar` 正常启动
（日志中游戏版本自报为 `Minecraft 26.2`），写入 9 个 `.cso` 文件。

已定：**行键沿用 loader 写法**（`26.2.0`、`26.3.0`，范围 `[26.2.0]`、`[26.3.0]`），它与游戏自报的
`26.2` / `26.3` 等价已实测；新增行照此填。文件名侧自 1.0.4 起带加载器段
（`chunkstorageoptimizer-neoforge-26.2.0-1.0.4.jar`），行键写法只管 Minecraft 那一段。

**但 Modrinth 用的是游戏写法**：`26.2.0` 不是它认得的 game_version，`26.2` 才是。发布脚本因此把标签
取回来解析，去掉结尾的 `.0` 再匹配，而不是直接把行键发出去。

## 5. 1.20.x 及更早

不在支持范围内。跨大版本不做格式兼容是既定政策，见 README 开头第二条。
