# 没覆盖到的 Minecraft 版本

受支持窗口是 **1.21 到 26.3**：`supported-versions.csv` 里的是已经能发包的，这个文件记的是
窗口内暂时没包的版本、为什么没包、以及什么条件下它就该被加进去。窗口之外（1.20.x 及更早）
**明确不做**，不是排期问题——见文末。

数据是 2026-09-27 从 `maven.neoforged.net` 的 `neoforge/maven-metadata.xml` 与 Mojang 的
`version_manifest_v2.json` 现取的，会过期。重测：

```bash
tools/oldest-stable-neoforge.sh 26.3      # 该 loader 线有没有稳定版
```

## 一、只是还没出稳定 loader（该盯着）

| Minecraft | loader 线 | 现在的构建 | 加行的条件 |
|---|---|---|---|
| **26.3**（正式版） | 26.3.0 | 23 个构建，**全部是 beta**，最新 `26.3.0.9-beta` | NeoForge 发出第一个不带后缀的构建就加 |

加的时候按 README 的两步走（填 csv 一行 → `./gradlew build -Pminecraft_version=...`），
CI 的矩阵是从 csv 派生的，不用改 workflow；发版用 `tools/tag-release.sh <版本>`。

## 二、这条版本永远不会有稳定 loader

这些 Minecraft 的 loader 线只出过 `-beta`，NeoForge 直接跳到下一版本线去了。下限只能取稳定版
（beta 玩家装不到，写下限就是承诺一个不存在的地盘），所以**不是"以后补"，是补不了**：

| Minecraft | loader 线 | 构建情况 |
|---|---|---|
| 1.21.2 | 21.2 | 只有 `21.2.0-beta`、`21.2.1-beta` 两个 |
| 1.21.6 | 21.6 | 21 个，全 beta，最新 `21.6.9-beta` |
| 1.21.7 | 21.7 | 26 个，全 beta，最新 `21.7.9-beta` |
| 1.21.9 | 21.9 | 17 个，全 beta，最新 `21.9.9-beta` |
| 26.1 | 26.1.0 | 33 个，全 beta，最新 `26.1.0.8-beta` |
| 26.1.1 | 26.1.1 | 14 个，全 beta，最新 `26.1.1.8-beta` |

有人问"为什么没有 1.21.9 的包"，答案是这里，不是我们漏了。

## 三、版本号写法：Mojang 说的和 loader 说的不一样

新版本号体系里，两段式的正式版号会少一位：**MC 正式版写作 `26.2`，NeoForge 的 artifact 线是
`26.2.0`**（`neoforge-26.2.0.57` 的 moddev-config 里钉的是 `neoform:26.2-2`）。我们现在的行键跟着
loader 写作 `26.2.0`，jar 里 `modId="minecraft"` 的范围就是 `[26.2.0]`。

实测确认这不影响加载：真实 `26.2.0.57` 服务端 + `chunkstorageoptimizer-26.2.0-1.0.3.jar`
正常启动（游戏自报 `Minecraft 26.2`），`[26.2.0]` 判定通过并写出 9 个 `.cso`——尾零在这套版本
比较里是等价的。**但等 26.3 的时候要做一次选择**（所以现在记下来）：

- 继续按 loader 写法（行键 `26.3.0`，范围 `[26.3.0]`）：上限推导天然正确（loader 线 `26.3.0` →
  排他上界 `26.3.1`），代价是文件名和 Modrinth 上的 game version（`26.3`）看着不一致。
- 改成按 Mojang 写法（行键 `26.3`，范围 `[26.3]`，文件名 `...-26.3-...`）：玩家选包最不容易看错，
  但 `build.gradle` 的 loader 线推导必须给两段式的新版本号补 `.0`（现在只给 `1.x` 补），
  否则上界会算成 `26.4`，把别的 Minecraft 的 loader 也放进来。

两种都能做，差别只在"谁的名字优先"。没定之前，新行按现在的写法（loader 拼写）填。

## 窗口之外：1.20.x 及更早不做

既定政策是大版本之间格式互不兼容、也不做兼容层（README 顶部第一条），往 1.20.x 扩展意味着
再开一套 `RegionFileStorage` 之外的缝（那边的存储入口与 `RegionStorageInfo` 形态都不同），
而 1.20.x 的玩家本来就要先跨过一次区块格式变更才能升到 1.21。这条线不跟。
