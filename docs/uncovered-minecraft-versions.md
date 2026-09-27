# 未覆盖的 Minecraft 版本

支持范围是 Minecraft 1.21 至 26.3。`supported-versions.csv` 是已经提供构建的版本；本文件记录该范围内
未提供的版本及原因。1.20.x 及更早的版本不在支持范围内，见第 5 节。

下面的数据取自 2026-09-27 与 2026-09-28，来源为 `maven.neoforged.net` 的 `neoforge/maven-metadata.xml`、
Mojang 的 `version_manifest_v2.json`、`maven.fabricmc.net` 与 `meta.fabricmc.net`、
`maven.terraformersmc.com`，以及本机缓存的 Minecraft jar。复核方式：

```bash
tools/oldest-stable-neoforge.sh <minecraft 版本号>
```

提供范围按加载器分别计算：NeoForge 10 行，Fabric 12 行（`supported-versions.csv` 中该加载器列不为
`-` 的行）。

## 1. NeoForge：该 loader 线仍在收构建，稳定版未出

| Minecraft | loader 线 | 构建数 | 最后构建 | 最后构建时间 |
|---|---|---|---|---|
| 26.3 | 26.3.0 | 23 | `26.3.0.23-beta` | 2026-09-26 |

该线最后构建为 2026-09-26，尚无后继版本线。稳定版发布后即可加入
`supported-versions.csv`：步骤见 README 的“多版本构建”一节，CI 矩阵由该文件生成、不需要改 workflow，
发版标签用 `tools/tag-release.sh <mod 版本号>`。

## 2. NeoForge：该 loader 线已停止收构建

每行给出该线最后一次构建的时间，以及后继版本线的稳定版。最后构建停在 `-beta` 且此后无更新、
后继版本线已有稳定构建的，该线不再有稳定版的可能。

| Minecraft | loader 线 | 构建数 | 最后构建 | 最后构建时间 | 后继版本线的稳定版 |
|---|---|---|---|---|---|
| 1.21.2 | 21.2 | 2 | `21.2.1-beta` | 2024-10-23 | 21.3.56（MC 1.21.3） |
| 1.21.6 | 21.6 | 21 | `21.6.20-beta` | 2025-06-26 | 21.8.9（MC 1.21.8；21.7 线同样无稳定版） |
| 1.21.7 | 21.7 | 26 | `21.7.25-beta` | 2025-07-16 | 21.8.9（MC 1.21.8） |
| 1.21.9 | 21.9 | 17 | `21.9.16-beta` | 2025-10-07 | 21.10.63（MC 1.21.10） |
| 26.1 | 26.1.0 | 33 | `26.1.0.19-beta` | 2026-03-31 | 26.1.2.71（MC 26.1.2） |
| 26.1.1 | 26.1.1 | 14 | `26.1.1.15-beta` | 2026-04-08 | 26.1.2.71（MC 26.1.2） |

1.21 至 26.3 共 17 个正式版，其中 6 个从未获得稳定构建；26.x 体系的 5 个正式版中占 2 个
（26.1、26.1.1）。通常是正式版之间间隔很短，loader 线停在 beta 就被取代：MC 26.1、26.1.1、26.1.2
分别在 2026-03-24、04-01、04-09 发布，相隔 8 天。

## 3. Fabric：26.x 无构建

26.1.2 与 26.2.0 两行的 Fabric API 列是 `-`。1.21 至 26.3 的 17 个正式版 Fabric 侧全都有稳定版
加载器、Fabric API 与 Mod Menu，所以缺口来自构建工具链，不来自生态：

| 事实 | 依据 |
|---|---|
| 26.x 的客户端 jar 本身就是 Mojang 名字 | 本机 `minecraft_26.1.2_client.jar` 内直接有 `net/minecraft/world/level/chunk/storage/RegionFileStorage.class`；26.2 与 26.3 的 `version.json` 只剩 client / server 两项，没有 `client_mappings`（1.21.11 仍有） |
| Fabric 对 26.3 不发 yarn，intermediary 是 `0.0.0`（恒等映射） | `meta.fabricmc.net/v2/versions/yarn/26.3` 返回空列表 |
| loom 的稳定版不接受"无映射" | 实测 1.15.5 / 1.16.3 / 1.17.21 / 1.18.2：写 `loom.officialMojangMappings()` 报 `Failed to find official mojang mappings for 26.3`，不写报 `Configuration 'mappings' has no dependencies`，自造 identity tiny 映射死在 `Provider "jar" not found` |
| 快照工具链可用，本项目不采用 | Mod Menu 自身用 loom `1.18-SNAPSHOT` + Gradle 9.7.1 构建 26.x，其 `build.gradle` 没有 mappings 行；loom 稳定版对 Gradle 的下限（1.15.5→9.2.0、1.18.2→9.7.0）也要求先升 wrapper |

加入条件：loom 出现支持"无映射"的稳定版。届时改 `fabric/build.gradle` 中按 `26.` 前缀拒绝的分支，
并把 csv 两列填上。26.1 与 26.1.1 两行的情况相同（第 2 节列的是它们的 NeoForge 状态）。

1.21.2 / 1.21.6 / 1.21.7 / 1.21.9 四行反过来是只有 Fabric 有构建：NeoForge 从未发布过这四版的稳定
构建（第 2 节），csv 的 NeoForge 列因此是 `-`。

## 4. 版本号的两种写法

两段式的正式版号省略了 patch 位：游戏版本号为 `26.2`，NeoForge 的 artifact 线为 `26.2.0`
（`neoforge-26.2.0.57` 的 moddev-config 指向 `neoform:26.2-2`）。当前行键采用 loader 的写法，jar 中
`modId="minecraft"` 的范围是 `[26.2.0]`。

已验证这不影响加载：NeoForge 26.2.0.57 服务端加 `chunkstorageoptimizer-26.2.0-1.0.3.jar` 正常启动
（日志中游戏版本自报为 `Minecraft 26.2`），写入 9 个 `.cso` 文件。

新增 26.3 时需要确定行键写法。两种方案的差别：

- loader 写法：行键 `26.3.0`、范围 `[26.3.0]`。loader 线推导与排他上界（`26.3.1`）直接正确；文件名与
  Mojang 的版本号 `26.3` 不一致。
- Mojang 写法：行键 `26.3`、范围 `[26.3]`。文件名与游戏版本号一致；`build.gradle` 推导 loader 线时
  需要给两段式的新版本号补 `.0`，否则排他上界会算成 `26.4`。

已定：沿用 loader 写法（`26.2.0`、范围 `[26.2.0]`），它与游戏自报的 `26.2` 等价已实测；新增行照此填。

## 5. 1.20.x 及更早

不在支持范围内。跨大版本不做格式兼容是既定政策，见 README 开头第二条。
