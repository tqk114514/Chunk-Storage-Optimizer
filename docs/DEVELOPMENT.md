# 开发文档

仓库结构、多版本构建、发布流程与两个加载器之间的实现差异。面向用户的内容见
[`README.md`](../README.md)。

## 仓库结构

仓库按依赖边界分成四块：

- `core`：不依赖 Minecraft，也不依赖加载器。包含文件格式、指标、离线工具；单元测试全部在这里。
- `common`：依赖 Minecraft，不依赖加载器。包含存储层、mixin、命令实现，以及按权限 API 分成的
  两套 `CsoPermissions`。
- `neoforge` 与 `fabric`：各自的加载器入口与配置存储。

`core` 和 `common` 不产出独立 jar，它们作为源码目录被两个加载器模块各自编译一次。

## 多版本构建

`minecraft_version` 是唯一的版本开关。一个 Minecraft 版本对应哪一行的 NeoForge 下限、Fabric API
与 Mod Menu 版本、写入 mod 元数据的版本范围、Java 级别、命令权限 API 使用哪一套实现，全部由
`supported-versions.csv` 中的对应行决定：

```bash
./gradlew :neoforge:build -Pminecraft_version=1.21.11   # csv 里列出的版本都能直接编
./gradlew :fabric:build -Pminecraft_version=1.21.2      # 只有 NeoForge 写 '-' 的那些行照样能编
```

Gradle 会为任何被请求的任务配置所有已 include 的模块，因此无法服务该版本行的模块会使构建在
到达自身任务之前失败。决定模块集合的位置是 `settings.gradle`：它读取同一份 csv 的两个加载器
列，列值为 `-` 的模块不被 include，请求该模块会得到 "project not found"。不传
`-Pminecraft_version` 时两个模块都被 include，各自使用自己那一行的默认版本（NeoForge 26.1.2、
Fabric 1.21.11）。

同一份 csv 也是 CI 矩阵的来源（workflow 按两个加载器列展开成 job），本地可构建的版本与发布的
版本因此始终一致。

### supported-versions.csv 的维护

csv 的列依次为：Minecraft 版本、Java 级别、命令权限 API family（`legacy` / `modern`）、NeoForge
下限、Fabric API、Mod Menu、FML 大版本。某个加载器没有该游戏版本的构建时，在对应列写 `-`。
Java 级别取自 Mojang 的版本清单（javaVersion）；family 取决于该 Minecraft 是否包含
`net.minecraft.server.permissions`（1.21.11 起为 `modern`）。

新增一个 Minecraft 版本的步骤：

```bash
tools/neoforge-floor.sh 26.2.0
# ① 把脚本打出来的行填进 supported-versions.csv：<mc>, <java>, <legacy|modern>,
#    <NeoForge 下限>, <Fabric API>, <Mod Menu>；某个加载器没有该游戏版本的构建就在该列写 -
#    java 看 Mojang 版本清单的 javaVersion，family 看这个 MC 有没有 net.minecraft.server.permissions
# ② 逐个模块验证：./gradlew :neoforge:build -Pminecraft_version=26.2.0，再 :fabric:build
#    同一版本；某一列写 '-' 时那个模块不会被 include，请求它会得到 "project not found"
```

NeoForge 下限的取法：该 loader 线最老的稳定版；整条线都没有稳定版时取最新 beta。NeoForge 在新的
Minecraft 发布后关闭旧的 loader 线，因此存续期短的版本（1.21.2 / 1.21.6 / 1.21.7 / 1.21.9 /
26.1 / 26.1.1）留下的线以 beta 结尾；取最新 beta 而不是更老的 beta，是因为较老的 beta 更可能存在
无法用于构建的构建输入。

已知问题：1.21.10 的下限取 21.10.64，而不是该线最老的稳定版 21.10.63。21.10.63 发布的 binpatch
基于被修改的 base jar 生成，通过 binpatch 装配工件的环境无法用它构建，21.10.64 修复了该问题。

26.3.0 的线仍在接收构建，该行下限随最新 beta 更新。

`tools/check-loader-versions.sh` 把 csv 中的 pin 与上游逐一核对：NeoForge 下限、Fabric API 的
`+<mc>` 后缀、Mod Menu 是否存在、以及构建中 pin 的 Fabric loader 是否支持该 Minecraft。CI 每次
push 都运行该脚本；核对 26.3.0 行时若 csv 落后于最新 beta，仅发出警告。

版本范围是精确单版本（NeoForge 的 `[26.1.2]`、Fabric 的 `"minecraft": "1.21.11"`），不使用
`>=`。`mixins.json` 中 `defaultRequire` 为 1，目标类或方法的签名变化会导致启动崩溃；精确范围使
未经测试的版本在加载前被拒绝。

未覆盖的版本及原因记录在
[`docs/uncovered-minecraft-versions.md`](uncovered-minecraft-versions.md)。

### 发布流程

推送 `v<version>` 格式的 tag 会触发 CI，自动发布到 Modrinth。编译矩阵来自
`supported-versions.csv`，与 tag 无关：一个 tag 会编译全部 29 个 jar（NeoForge 17 + Fabric 12）
并全部发布。tag 中的版本号必须与 `gradle.properties` 的 `mod_version` 一致，不一致时 CI 直接
失败。

发布说明取自 `CHANGELOG.md` 中 `## [<版本>]` 段落的正文，标题行不包含在发布说明中。同一版本的
29 个条目使用同一份说明。发布前会检查该段落是否存在且有内容，缺失或为空时发布在上传前失败并
给出错误信息。

上传是幂等的：创建版本前先查询项目在该（游戏版本，加载器）组合下是否已存在同号版本，已存在则
跳过；中途失败后重跑只会补传缺失的部分。不推送 tag 也可以在 Actions 页面手动触发 workflow
（默认只演练、不上传），已发布过的版本可借此补传。

一个 jar 对应一个 Modrinth 版本条目的原因写在 `.github/workflows/build.yml` 的注释里。

Modrinth 凭据在 Settings → Secrets and variables → Actions 中配置。两项都必须使用 repository
作用域；environment 作用域的值对工作流不可见，表现为 token 为空且不产生错误信息。

- Repository secret `MODRINTH_TOKEN`：Modrinth 个人访问令牌，在
  <https://modrinth.com/settings/account> 生成，需要 `VERSION_CREATE`、`PROJECT_READ`、
  `VERSION_READ` 三个权限。项目仍在审核中时，缺少读取权限的 token 访问该项目会得到 404：Modrinth
  对无法读取的项目返回 404 而非 403，与项目 id 填写错误时的表现相同。令牌名称区分大小写。
- Repository variable `MODRINTH_PROJECT`（在 Variables 标签页，不是 Secrets 页）：项目的 id，即
  Modrinth 项目页面上显示的 8 位字符串（形如 `cJqzw49d`）。应填写 id 而不是 slug：id 不会变化，
  slug 可以随时修改，项目改名后以 slug 配置的条目会失效。

### 跨版本的编译缝

1.21 至 26.x 之间存在两处不兼容的 API，各有一条缝。每条缝由两个目录提供签名相同的实现，构建时
按 csv 的对应列只把其中一个加入编译路径，运行期没有条件分支，也没有反射。

命令权限：`common/src/version/legacy/java` 与 `modern/java` 各提供一个签名相同的
`CsoPermissions.operatorOnly()`，由 csv 的 family 列选择。两者的判定标准都是权限等级 3。

配置类型：`neoforge/src/version/fml11/java` 与 `fml12/java` 各提供一个签名相同的
`CsoConfigType.common()`，由 csv 的 FML 列选择。FancyModLoader 12（NeoForge 26.3 起）把
`ModConfig.Type.COMMON` 改名为 `LOCAL`，旧常量在新版本中不存在，共享代码无法引用。注册时显式
传入文件名 `chunkstorageoptimizer-common.toml`；不传入时该次改名会使配置文件变成
`-local.toml`，已有的配置文件失效。这条缝只在 NeoForge 侧，Fabric 不读取该列。

另有一处相关差异：26.1 将 `ChunkPos` 改为 record（`pack` / `unpack` / `x()` / `z()`）。写入
批处理使用的坐标键不来自游戏数据，`CsoStorage` 使用自己的打包方式，因此该差异不需要缝。其余
部分中 `RegionFileStorage` 的方法签名在整个支持范围内没有变化。

### 各版本行的实测状态

NeoForge：

| Minecraft（NeoForge 构建） | 测试方式 | 加载与 mixin | `/cso` | 写出 `.cso` |
|---|---|---|---|---|
| 1.21 (21.0.143) | dev + 真实服务端 | 通过 | 已执行 | 是：真实服务端 5 个文件、0 个 `.mca` |
| 1.21.1 (21.1.1) | dev | 通过 | 未执行 | 否，回退 Anvil |
| 1.21.3 (21.3.56) | dev | 通过 | 未执行 | 否，回退 Anvil |
| 1.21.4 (21.4.121) | dev | 通过 | 未执行 | 否，回退 Anvil |
| 1.21.5 (21.5.74) | dev | 通过 | 未执行 | 否，回退 Anvil |
| 1.21.8 (21.8.9) | dev | 通过 | 未执行 | 否，回退 Anvil |
| 1.21.10 (21.10.63) | dev | 通过 | 未执行 | 是：5 个文件、0 个 `.mca` |
| 1.21.11 (21.11.42) | dev | 通过 | 未执行 | 是：9 个文件、0 个 `.mca` |
| 26.1.2 (26.1.2.71) | dev | 通过 | 已执行 | 是：5 个文件 |
| 26.2.0 (26.2.0.57) | dev + 真实服务端 | 通过 | 已执行 | 是：真实服务端 9 个文件 |

Fabric：

| Minecraft（loader / Fabric API） | 测试方式 | 加载与 mixin | `/cso` | 写出 `.cso` |
|---|---|---|---|---|
| 1.21.11 (0.19.5 / 0.141.6) | dev + 真实服务端 | 通过 | 已执行 | 是：真实服务端 9 个文件、0 个 `.mca` |
| 1.21 (0.19.5 / 0.102.0) | 真实服务端 | 通过 | 已执行 | 是：真实服务端 8 个文件、0 个 `.mca` |

其余 10 个 Fabric 行只有编译验证（CI 覆盖），运行时未逐版运行。NeoForge 侧 1.21.2 / 1.21.6 /
1.21.7 / 1.21.9 / 26.1.0 / 26.1.1 / 26.3.0 七行（1.0.9 起加入 csv）同样只有编译验证。

已知问题（dev 环境）：`runServer` 在 21.8 及更早的构建上看不到 zstd（`NoClassDefFoundError`，
随后回退原版 Anvil），21.10 起正常；两种环境下生成的
`neoforge/build/moddev/serverLegacyClasspath.txt` 都不包含 zstd。该差异仅存在于 dev 环境；发行
路径使用嵌套 jar，不受影响，1.21 与 26.2.0 两行已按发行路径验证。Fabric 的 dev 运行没有该差异，
1.21.11 的 `:fabric:runServer` 直接写出 `.cso`。

### 真实服务端验证方法

NeoForge：在仓库之外用 `neoforge-<版本>-installer.jar --installServer` 安装一个服务端，把
`neoforge/build/libs/` 下对应的 jar 放入 `mods/`。Fabric：从
`meta.fabricmc.net/v2/versions/loader/<mc>/<loader>/<installer>/server/jar` 获取可直接
`java -jar` 启动的启动器，`mods/` 中放入本 mod 与该行对应版本的 Fabric API。两侧通过 RCON 执行
`cso stats` 与 `save-all flush`。NeoForge 侧的 1.21.1 至 1.21.8 未做这一步，只验证了 1.21 这
一行。

存档目录布局：1.21.1、1.21.11 以及 1.21 的真实服务端为 `<世界>/{region,poi,entities}` 加
`DIM-1/`、`DIM1/`；26.1.2 与 26.2.0 为 `<世界>/dimensions/minecraft/<维度>/…`。`.cso` 写在游戏
提供的目录中，两种布局的落盘位置均已实测。

### 两个加载器之间的差异

按实际 import 关系，加载器需要各自实现的内容为四项：入口（`ChunkStorageOptimizer` /
`ChunkStorageOptimizerFabric`）、配置存储（`Config` 的 `ModConfigSpec` / `FabricConfig` 的
properties）、命令注册钩子、配置界面。NeoForge 复用 FML 自带的 `ConfigurationScreen`；Fabric
没有官方界面，由 `CsoModMenuIntegration` 与 `CsoConfigScreen` 提供给 Mod Menu 打开。
`storage/CsoStorage`、mixin、命令实现在 `common` 中共用一份；`format` / `metrics` / `tools`
既不依赖 Minecraft 也不依赖任何加载器，格式层因此可以脱离游戏独立运行测试。

实现层面的差异：

- mixin 的映射方式。loom 从 1.15 起默认不再挂载 mixin 的注解处理器，改由 remapJar 阶段把
  intermediary 名字直接写入注解（`MixinRefmapInliner`），因此 `fabric/src/main/resources` 中的
  `chunkstorageoptimizer.mixins.json` 不带 `refmap` 字段；NeoForge 那份带，refmap 文件由 FML
  的注解处理器生成。发行 jar 中 `@Mixin` 的值为 `net/minecraft/class_2867`，`@Inject` 的
  `method` 值为 `method_17911`；dev 运行直接使用未 remap 的类，两条路径都能绑定。
- 嵌套依赖。NeoForge 使用 `jarJar`，Fabric 使用 loom 的 `include`；zstd-jni 位于
  `META-INF/jars/`，加载后是一个独立的嵌套 mod（id 为 `com_github_luben_zstd-jni`）。
- 可选的第三方界面。Mod Menu 只以 `modCompileOnly` 参与编译；未安装它时 `modmenu` 入口点不会
  被实例化，配置退回为纯文件编辑。
- 下限的位置。NeoForge 的下限写入产物内容：`neoforge.mods.toml` 中的 `versionRange` 直接决定
  能否安装，该列填该版本的下限，取法见「supported-versions.csv 的维护」。Fabric 侧的
  `fabric.mod.json` 不写 `fabricloader`：唯一的硬依赖是 Fabric API，而每个 fabric-api 模块自带
  loader 下限（MC 1.21.1 那一档为 `>=0.15.11`；实测将本 mod 放到 0.14.24 上，拒绝来自 API 而
  非本 mod）。编译使用的 loader 版本（`fabric_loader_version`，默认 0.19.5）只影响 dev 运行
  与 loom。

配置界面只使用 `Button.builder(Component, OnPress)` 与 `Screen.addRenderableWidget` 两个控件
API；每个选项是一个循环取值的按钮，点击即写入文件。不使用文本框，也不自绘，以减少界面依赖的
客户端 API 数量。界面用到的其余签名（`Tooltip.create`、`Component.plainCopy`、`Screen.width`）
已在 1.21 与 1.21.8 的反编译源码中核对，两端一致。
