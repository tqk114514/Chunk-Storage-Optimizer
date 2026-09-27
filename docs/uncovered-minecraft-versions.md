# 未覆盖的 Minecraft 版本

支持范围是 Minecraft 1.21 至 26.3。`supported-versions.csv` 是已经提供构建的版本；本文件记录该范围内
未提供的版本及原因。1.20.x 及更早的版本不在支持范围内，见第 4 节。

下面的数据取自 2026-09-27，来源为 `maven.neoforged.net` 的 `neoforge/maven-metadata.xml` 与 Mojang 的
`version_manifest_v2.json`。复核方式：

```bash
tools/oldest-stable-neoforge.sh <minecraft 版本号>
```

## 1. 尚无稳定 loader

| Minecraft | loader 线 | 构建情况 |
|---|---|---|
| 26.3 | 26.3.0 | 23 个构建，均为 `-beta`，最新 `26.3.0.9-beta` |

该线出现第一个稳定构建后，即可加入 `supported-versions.csv`。步骤见 README 的“多版本构建”一节：CI
矩阵由该文件生成，不需要改动 workflow；发版标签用 `tools/tag-release.sh <mod 版本号>`。

## 2. 不会有稳定 loader

下限取稳定版是既定规则。以下 loader 线只发布过 `-beta` 构建，因此没有对应的 jar。

| Minecraft | loader 线 | 构建情况 |
|---|---|---|
| 1.21.2 | 21.2 | 2 个构建：`21.2.0-beta`、`21.2.1-beta` |
| 1.21.6 | 21.6 | 21 个，均为 `-beta`，最新 `21.6.9-beta` |
| 1.21.7 | 21.7 | 26 个，均为 `-beta`，最新 `21.7.9-beta` |
| 1.21.9 | 21.9 | 17 个，均为 `-beta`，最新 `21.9.9-beta` |
| 26.1 | 26.1.0 | 33 个，均为 `-beta`，最新 `26.1.0.8-beta` |
| 26.1.1 | 26.1.1 | 14 个，均为 `-beta`，最新 `26.1.1.8-beta` |

## 3. 版本号的两种写法

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

在做出变更之前，新增行沿用 loader 写法。

## 4. 1.20.x 及更早

不在支持范围内。跨大版本不做格式兼容是既定政策，见 README 开头第二条。
