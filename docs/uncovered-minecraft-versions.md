# 未覆盖的 Minecraft 版本

支持范围是 Minecraft 1.21 至 26.3。该范围内已无未覆盖版本;本文件保留 NeoForge 下限的取法与
例外(第 1 节)、26.x Fabric 行的构建方式与 pin(第 2 节)、版本号写法约定(第 3 节)。
1.20.x 及更早的版本不在支持范围内,见第 4 节。

数据取自 2026-10-03，来源为 `maven.neoforged.net` 的版本 API、Mojang 的 `version_manifest_v2.json`、
`maven.fabricmc.net`、`meta.fabricmc.net` 与 `maven.terraformersmc.com`，以及本机缓存的 Minecraft jar。
Fabric 26.x 一节更新于 2026-10-07（原阻塞点已消除，26.x 五行已全部填齐）。
复核方式：

```bash
tools/neoforge-floor.sh <minecraft 版本号>     # 该 loader 线的下限（最老稳定版，无则最新 beta）
tools/check-loader-versions.sh                 # 把 csv 的 pin 与上游逐一核对
```

1.21 至 26.3 共 **17 个正式版**。NeoForge 侧与 Fabric 侧各覆盖全部 17 行,该范围内没有缺口;
26.x Fabric 行的构建方式记录在第 2 节。

## 1. NeoForge：没有缺口，但 6 行以 beta 为下限

NeoForge 一有新 Minecraft 就关掉旧线，所以只活了一周的版本会留下一条**以 beta 收尾**、永远不会有稳定
版的线。等下去等于永远不支持，所以下限取该线**最新 beta**：

| Minecraft | loader 线 | 稳定版 | 下限（= 该线最新 beta） | 该线最后构建 |
|---|---|---|---|---|
| 1.21.2 | 21.2 | 无 | `21.2.1-beta` | 2024-10-23 |
| 1.21.6 | 21.6 | 无 | `21.6.20-beta` | 2025-06-26 |
| 1.21.7 | 21.7 | 无 | `21.7.25-beta` | 2025-07-16 |
| 1.21.9 | 21.9 | 无 | `21.9.16-beta` | 2025-10-07 |
| 26.1.0 | 26.1.0 | 无 | `26.1.0.19-beta` | 2026-03-31 |
| 26.1.1 | 26.1.1 | 无 | `26.1.1.15-beta` | 2026-04-08 |

六条线都已冻结（不再收构建），所以这些下限是定值，检查脚本可以精确比对。**26.3.0 的线仍在收构建**，
所以它的下限会漂；检查脚本对这种情况只警告不报错——下限是下界，落后了仍然成立。

**为什么用最新而不是最老 beta**：最老那个更可能带着「编不过的构建输入」。先例是 21.10.63——它发布的
binpatch 是用被改过的 base jar 生成的，任何走 binpatch 的环境都编不过，四天后才由 21.10.64 修好。最新
beta 离这类问题最远。

**1.21.10 是唯一的例外行**：该线的最老稳定版是 21.10.63，也就是上面那个编不过的构建，所以 csv 用
21.10.64。检查脚本里单独列了这条例外。

### 26.3 起还有一条配置类型的版本缝

NeoForge 26.3 把 FancyModLoader 从 **11.0.16 升到 12.0.8**，而 FML 12 重构了配置类型枚举：

| FML 11 | FML 12 | 语义 |
|---|---|---|
| `COMMON` | **`LOCAL`** | 两侧都加载、不同步（FML 12 源码：*Local mod config ... loaded on both servers and clients ... not synced*） |
| `SERVER` | **`SYNCED`** | 服务端加载、同步给客户端 |
| `CLIENT` | `CLIENT` | 不变 |
| `STARTUP` | `STARTUP` | 不变 |

旧常量在新版本里**不存在**，所以共享代码无法命名它。按仓库既有的做法做了一条**编译期缝**：
`neoforge/src/version/fml11/java` 与 `fml12/java` 各提供一个同签名的 `CsoConfigType`，由 csv 的
**FML 列**决定编译哪一份（`11` / `12`）。用 per-row 的列而不是版本阈值比较，是因为阈值会**静默**选错一侧。

配置文件名是**显式传入**的（`chunkstorageoptimizer-common.toml`），否则重命名会把它变成
`-local.toml`，让已有的配置文件悄悄失效。

## 2. Fabric:26.x 五行的构建方式

原先的阻塞点("loom 稳定版不接受无映射")已消除。26.x 的客户端 jar 本身就是官方名(26.2 与 26.3 的
`version.json` 只剩 client / server 两项,没有 `client_mappings`),没有可声明映射,而 loom 1.18 的
稳定版提供了**无重映射模式**:sodium 的 26.3 构建用的就是它——插件 ID `net.fabricmc.fabric-loom`
是 loom 的 no-remap 标记插件,同一个开关。本项目一个构建要服务所有行,所以走该开关的属性形式:
settings.gradle 在 26.x 行注入 `fabric.loom.disableObfuscation`(loom 在插件 apply 时就读这个属性,
任何晚于 settings 阶段的注入都来不及)。该模式下:

- 不声明 mappings;客户端 API 的版本差异按 csv 的 **screen 列**走 `fabric/src/version/` 编译缝
  (分界在 26.2:`Minecraft.setScreen` 到 26.1 线为止,26.2 起是 `Minecraft.gui.setScreen`);
- loom 不创建 `mod*` 依赖 DSL,fabric-loader / Fabric API / Mod Menu 走普通
  `implementation` / `compileOnly`(sodium 同款写法);
- `com.mojang:minecraft` 与 `fabric.mod.json` 用**游戏写法**(行键 `26.3.0` 去掉结尾的 `.0`);
- 输出 jar 不重映射,mixin 注解保持官方名——26.x 运行时就是官方名,与 1.21 行的 intermediary
  内联互补。

实测(2026-10-07):26.3.0 / 26.1.2 / 26.2.0 三行 `:fabric:runServer` 均在 3 秒内起服,mixin 绑定,
各写出 4–5 个 `.cso`、0 个 `.mca`(26.x 目录布局 `world/dimensions/minecraft/<维度>/…`);
26.1.0 / 26.1.1 两行经构建验证。Gradle wrapper 因此从 9.2.1 升到 9.7.1(loom 1.18 的下限;
moddev 2.0.147 在 9.7.1 下的组合由 sodium 先行验证)。

26.x 五行的 Fabric API 与 Mod Menu pin:26.1.0 行 `0.145.1+26.1` / `18.0.2`,26.1.1 行
`0.145.4+26.1.1` / `18.0.2`,26.1.2 行 `0.155.3+26.1.2` / `18.0.2`(Mod Menu 的 18.0.2 一个
构建覆盖 26.1 至 26.1.2 三个游戏版本),26.2.0 行 `0.161.0+26.2` / `20.0.3`,26.3.0 行
`0.162.0+26.3` / `21.0.0`。**26.x 的 Fabric API 版本号带的是游戏写法**(`+26.2`,不是
`+26.2.0`),`check-loader-versions.sh` 两种写法都接受。

1.21.2 / 1.21.6 / 1.21.7 / 1.21.9 四行反过来曾经是只有 Fabric 有构建:NeoForge 从未为这四版发布稳定
构建。第 1 节之后它们两侧都有了。

## 3. 版本号的两种写法

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

## 4. 1.20.x 及更早

不在支持范围内。跨大版本不做格式兼容是既定政策，见 README 开头第二条。
