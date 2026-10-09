# Chunk Storage Optimizer

> ## ⚠️ 使用前须知
>
> **1. 存档一旦被本 mod 写入，就不再是原版能读的存档。** 卸载 mod 后，只存在于 `.cso` 中的进度
> 对原版不可见，MCA Selector 等外部工具也无法读取。要回到原版，先执行
> `/cso convert mca prune`。操作前请备份存档。
>
> **2. 大版本之间格式不互通，不提供跨大版本兼容。** 版本规则：文件格式或任何不兼容的内容发生
> 变动时跳大版本（1.0.1 → 2.0.0）；同一大版本内（1.0.1 → 1.4.3 → 1.100.0）格式完全互通，新版本
> 可直接覆盖安装。2.x 不读取 1.x 写的存档。跨大版本升级的步骤：先用旧版本执行 `/cso convert mca`
> 转回原版格式，更换版本，再转换回来。升级前查看 `CHANGELOG.md`。
>
> **3. 转换期间游戏会完全冻结，且无法中途退出。** `/cso convert` 在服务器主线程上执行——线程冻结
> 正是"转换过程中没有任何东西能写区域目录"的保证本身。屏幕顶部有原版样式的进度条显示进度；
> "保存并退出"会排队等在转换之后，转完自动继续。**任意时刻强制关闭游戏都是安全的**：每个区域
> 文件独立原子落位，切换只在全部文件完成后才记录，半转换的存档会以两种格式的联合方式正常读取
> ——重新进入存档再跑一次转换即可收尾。

本 mod 用自定义的区块存储格式替换 Minecraft 的 `.mca` region 文件：将 32×32 的 region 划分为
多个 bucket，每个 bucket 用一条 zstd 流整体压缩，并去除原版按 4 KiB 扇区对齐产生的填充，从而
得到更小的存档和更快的读写。在一个 889 MB 的城市存档上实测：体积减少 55%，写入快 8.1 倍，
读取快 3.1 倍。

## 目录

- [支持版本](#支持版本)
- [安装](#安装)
- [开始使用](#开始使用)
- [配置](#配置)
- [游戏内命令](#游戏内命令)
- [离线工具](#离线工具)
- [性能](#性能)
- [文件格式](#文件格式)
- [注意事项](#注意事项)
- [从源码构建](#从源码构建)

## 支持版本

- 加载器与版本：NeoForge 与 Fabric 各覆盖 Minecraft 1.21 至 26.3 之间全部 17 个正式版本——
  1.21 至 1.21.11 的 12 个，加上 26.1.0 / 26.1.1 / 26.1.2 / 26.2.0 / 26.3.0；每个游戏版本、
  每个加载器各一个 jar。26.x Fabric 行的构建方式见
  [`docs/uncovered-minecraft-versions.md`](docs/uncovered-minecraft-versions.md)
- Fabric 侧需要 Fabric API。设置界面由 Mod Menu 提供；Mod Menu 是可选依赖，没有它时配置仍然
  可用，直接编辑配置文件即可
- 运行环境：Java 21（1.21.x）或 Java 25（26.x），跟随 Minecraft 本身的要求

## 安装

1. 按加载器和游戏版本选择 jar：文件名中间两段就是加载器与 Minecraft 版本，例如
   `chunkstorageoptimizer-neoforge-26.1.2-1.0.4.jar` 与
   `chunkstorageoptimizer-fabric-1.21.11-1.0.4.jar`。放入 `mods/` 目录即可。
2. 每个 jar 只适用于其对应的 Minecraft 版本。NeoForge 侧要求加载器不低于该版本的下限：下限取
   该 loader 线最老的稳定版，整条线都没有稳定版时取最新 beta，逐版列在
   `supported-versions.csv` 中；加载器版本不足时会在启动时直接拒绝加载。Minecraft 26.1.2 对应的
   下限是 NeoForge 26.1.2.71。Fabric 侧需要同一行的 Fabric API（同样列在
   `supported-versions.csv`）。
3. 启动游戏后，配置文件按加载器生成：NeoForge 写
   `config/chunkstorageoptimizer-common.toml`，Fabric 写
   `config/chunkstorageoptimizer.properties`。两者的键完全同名。

与 C2ME 同时安装时，本 mod 会自动停用并退回原版存储，同时在游戏中给出提示。不兼容的原因见
[注意事项](#注意事项)。

本 mod 只需要安装在世界所在的一侧：不注册任何网络通道，客户端侧代码只有配置界面，改动的只是
磁盘上的区块存储。专用服务器安装服务端即可；联机时房主安装，房客无需安装，房客单独安装也不
要求房主安装。前提是同一个存档只能由安装了 mod 的一侧读写（见开头「使用前须知」）。

## 开始使用

### 新世界

直接开始游戏。所有区块数据都会写入 `.cso` 文件，不会产生任何 `.mca`。

### 已有世界

本 mod 采用渐进迁移：`.cso` 中没有的区块会自动回退读取同名的 `.mca`，因此启用后已有的存档
数据不会丢失，新的写入会逐步迁移到新格式。

随着区块被修改，旧数据仍留在 `.mca` 中，新版本写入 `.cso`，两份数据会同时占用磁盘。可执行
一次完整迁移：

```
/cso convert cso prune
```

该命令把当前世界各维度、各目录的 `.mca` 全部转成 `.cso`，验证后删除原文件。执行前会先运行
`save-all flush`，转换在主线程上同步完成。转换的执行细节与删除的安全机制见
[游戏内命令](#游戏内命令)。

## 配置

配置文件的键在两个加载器之间完全同名，仅容器格式不同：NeoForge 使用
`config/chunkstorageoptimizer-common.toml`，Fabric 使用
`config/chunkstorageoptimizer.properties`。

NeoForge 生成的 toml 文件中没有注释：NeoForge 只写入代码中声明的 comment，而本 mod 的选项说明
按语言各提供一份，存放在语言文件里。各键含义见下表，或游戏内 Mods → 选中本 mod → Config 界面
（英 / 简中 / 繁中 TW / 繁中 HK / 日 / 韩均有）。两个加载器
的界面读取同一批语言键，Mod Menu 的列表摘要、名称与 NeoForge Mods 界面的描述也使用同一批键。
Fabric 的 properties 文件自带取值范围注释，手动修改后重启生效。

| 键 | 默认 | 说明 |
|---|---|---|
| `enabled` | `true` | 总开关。关闭后使用原版 Anvil 存储，不会改动任何已有文件 |
| `grid` | `16` | bucket 网格边长（1/2/4/8/16/32）。见下方说明 |
| `compression` | `zstd` | 压缩算法，`zstd` 或 `none` |
| `zstdLevel` | `3` | zstd 等级 1–22。等级越高压缩越好、写入越慢 |
| `cachedBuckets` | `64` | 每个 region 文件缓存的解压 bucket 数；另有每文件 8 MB 的字节上限兜底 |
| `verifyCrc` | `true` | 读取时校验 CRC32。关闭可获得少量性能，但失去损坏检测 |
| `fallbackToMca` | `true` | `.cso` 中没有的区块回退读取 `.mca`。**不要关闭** |
| `compactionMinBytes` | `4194304` | 触发空间整理的最小浪费字节数 |
| `compactionRatio` | `0.25` | 触发整理的浪费/占用比 |
| `batchMaxChunks` | `16` | 攒够多少个区块变更后提前写入。设为 1 可关闭批处理 |
| `batchMaxDelayMs` | `5000` | 暂存写入的最长停留时间，超时即落盘 |

升级本 mod 后，已存在的配置文件不会被新的默认值覆盖；`grid` 等配置项仍为旧值时需要手动修改。

### grid 的取值

grid 决定压缩率与写入代价的平衡。在 889 MB 城市存档上实测：

| grid | 每 bucket 区块数 | 体积 | 写入代价 |
|---|---|---|---|
| 1 | 1024 | 最优（省 59.3%） | 最高，是 grid=16 的约 15 倍 |
| 8 | 16 | 省 58.5% | 约为 grid=16 的 2 倍 |
| **16** | **4** | **省 55.4%** | **最低** |
| 32 | 1 | 更差 | 反而上升（失去跨区块共享上下文） |

- 活跃世界（持续写入）使用默认的 `16`，写入量与耗时最低。
- 归档或极少改动的世界用 `8` 或更小，体积优先。
- 区块稀疏且单区块数据较大的世界或维度（例如只加载了少量区块的下界）建议调到 `1` 或 `2`，
  详见[性能](#性能)。

## 游戏内命令

需要管理员权限。

| 命令 | 作用 |
|---|---|
| `/cso stats` | 累计统计：读写区块数、压缩/解压耗时、缓存命中率、实际压缩比，外加**延迟分布**（批写入 / 压缩 / 解压的 p50、p95、max）和**每个存储一行**（哪个维度的 region/poi/entities 各读了多少、flush 多久） |
| `/cso reset` | 清零统计 |
| `/cso compact` | 对所有已打开的 region 文件执行一次空间整理 |
| `/cso report [files]` | 抽样本存档各维度目录，按 grid 1/8/16/32 报出"同样的区块会占多大"，用来决定 grid 该调成几。`files` 是每个目录抽几个文件（默认 2，最多 8）；在后台线程跑，跑完再发到聊天 |
| `/cso convert cso [prune]` | 把 `.mca` 转成 `.cso`；同名 `.cso` 已存在时两侧取并集（`.cso` 的版本优先，与读取顺序一致）。加 `prune` 会在验证后删除 `.mca` |
| `/cso convert mca [prune]` | 把 `.cso` 转回 `.mca`，并在世界目录写下 `cso.disabled`——**只有这个世界**退出本 mod，重启后仍认。加 `prune` 会删除 `.cso` |

`/cso convert mca` 之后，世界目录里会多出一个与 `level.dat` 同级的 `cso.disabled`。该文件记录
这个世界已退回原版存储，重启后仍然生效；配置文件中的 `enabled` 不必改动，同一目录下的其它世界
也不受影响。要重新启用，删除该文件、重进世界，再执行 `/cso convert cso prune`。不重进世界时
转换会被拒绝：这个世界已打开的 `.mca` 句柄仍在写入，此时转换出的 `.cso` 会覆盖后续写入的
数据。

`convert` 为同步执行，流程是：先运行 `save-all flush`，等待区块 IO 线程清空队列（包括游戏自身
未落盘的区块和本 mod 的暂存桶）；然后关闭所有文件句柄；最后占住服务器主线程完成搬运。区块保存
只从主线程排队，因此移动文件期间不会有新的写入，读取到半写文件的可能被排除。代价是阻塞时长
与搬运量成正比，大存档上为秒级。

`/cso report` 在后台线程采样，不写入任何数据；遇到正在写入的文件时打印 `CSO report failed`，
重新执行即可。report 的百分比相对抽样文件当前的磁盘占用：源为 `.mca` 时即「相比原版能省多少」，
源已是 `.cso` 时即「相比现在重写一遍能省多少」。分位数来自对数分桶直方图（桶宽为 2 倍关系），
是近似值，可用于判断是否存在延迟问题，不用于精确测量。

删除操作不可逆。每个文件都经过「转换 → 读回校验 → 删除」的流程，校验不一致时立即中止，不会
删除任何文件。转换前会先检查 `.mca` 中是否存在本构建无法读取的槽位（外部 `.mcc`、未知压缩号、
解压失败）：只要有一处，整个文件即被跳过，既不转换也不删除，结果中会报出跳过的文件数与
区块数。这些槽位是真实区块，只按可读取的数量校验会使 `prune` 在校验通过的表象下删除包含它们
的唯一文件。执行转换前仍建议备份存档。

## 离线工具

不启动游戏即可对磁盘上的存档操作。工具只搬运 NBT 字节，不解析它们。

```bash
# 只报告体积对比，不改动任何文件
./gradlew :core:csoTool -PcsoArgs="bench --grid 16 --level 3" -PcsoDir="<存档>/dimensions/minecraft/overworld/region"

# 转换（原文件保留）
./gradlew :core:csoTool -PcsoArgs="convert --to cso --grid 16" -PcsoDir="<目录>"

# 反向转换：.cso -> .mca
./gradlew :core:csoTool -PcsoArgs="convert --to mca" -PcsoDir="<目录>"

# 读写速度对比（多轮取中位数）
./gradlew :core:csoTool -PcsoArgs="ab --grid 16 --level 3" -PcsoDir="<目录>"

# 各 grid 的写入量对比
./gradlew :core:csoTool -PcsoArgs="amp --level 3" -PcsoDir="<目录>"

# 预写日志带来的写入开销
./gradlew :core:csoTool -PcsoArgs="walcost --level 3" -PcsoDir="<目录>"

# 统计目录内的区块总数与平均区块大小（只读，常用于对照实验前确认两边一致）
./gradlew :core:csoTool -PcsoArgs="count" -PcsoDir="<目录>"
```

`bench` / `ab` / `amp` / `walcost` 读取目录中现有的格式：有 `.mca` 时使用 `.mca`（原版真实写出
的字节），只剩 `.cso` 时自动改用 `.cso`，已转换完成的存档仍可测试。此时 anvil 一行是本工具
重写出的估算值，输出中会标注 `[rebuilt by this tool — an estimate]`。两种格式同时存在（渐进
迁移的常态）时，可用 `--from cso` 或 `--from mca` 强制指定读取格式。

对比两个存档的体积时，先用 `count` 确认两边的区块数一致；区块数不同时，体积差包含内容差异，
数据没有可比性。

路径包含空格时必须使用 `-PcsoDir` 传入目录，不要放入 `-PcsoArgs`（参数按空格拆分）。

### 存档目录结构

目录布局取决于游戏版本：1.21 全线（含 1.21.11）使用老布局，26.x 起维度被移入
`dimensions/<namespace>/<维度>/`：

```
<saves>/<世界名>/                     # 1.21 线（实测 1.21.1 与 1.21.11 一致）
├── level.dat
├── {region,poi,entities}             # 主世界直接在存档根下
├── DIM-1/{region,poi,entities}       # 下界
└── DIM1/{region,poi,entities}        # 末地

<saves>/<世界名>/                     # 26.x
├── level.dat
└── dimensions/minecraft/
    ├── overworld/{region,poi,entities}
    ├── the_nether/{region,poi,entities}
    └── the_end/{region,poi,entities}
```

`.cso` 始终写在游戏提供给 mod 的目录中，与同名的 `.mca` 平级。1.21.11 的 dev 服务器实测落在
`<世界>/region/r.0.0.cso`、`<世界>/poi/…`、`<世界>/entities/…`，26.1.2 落在
`<世界>/dimensions/minecraft/overworld/region/…`。`region`、`poi`、`entities` 三种目录是同一种
region 文件，同一套命令都能处理。

## 性能

数据来自一个 889 MB 的真实城市存档（Los Perrito，201,321 个区块），grid=16、zstd L3。

### 体积与速度

| 指标 | 原版 Anvil | 本 mod |
|---|---|---|
| region 总大小 | 889.23 MB | 396.47 MB（省 55.4%） |
| 平均每区块 | 4,631 B | 1,920 B |
| 写入 8,192 区块 | 3,722 ms | 457 ms（快 8.1x） |
| 读取 8,192 区块 | 753 ms | 247 ms（快 3.1x） |

同一存档的其他目录：

| 目录 | 原版 | 本 mod | 节省 |
|---|---|---|---|
| region | 889.23 MB | 396.47 MB | 55.4% |
| poi | 5.46 MB | 978 KB | 82.5% |
| entities | 5.74 MB | 1.80 MB | 68.6% |

数据越稀疏、单块越小，收益越大：原版为每个区块预留 4 KiB 扇区，这类目录的浪费占比最高。

### 同种子对照

两个存档均为原版地形生成，使用同一种子、同一视距，原地不动直到加载完成后退出：

| | 文件 | 区块数 | 大小 | 平均每区块 |
|---|---|---|---|---|
| 原版 `.mca` | 16 | **8,281** | 54.44 MB | 6,893 B |
| 本 mod `.cso` | 16 | **8,281** | 36.48 MB | 4,619 B |

两者区块数完全相同，因此 33.0% 的差距完全来自格式，不含内容差异。同一对照中 entities 省
61.3%，poi 省 20.9%。

### 收益随单区块大小变化

| 存档 | 原版平均每区块 | 节省 |
|---|---|---|
| 城市存档（密集建筑） | 4.6 KB | 55.4% |
| 原版地形（上表） | 6.89 KB | 33.0% |
| 多人存档（见下节） | 7.62 KB | 38.4% |
| 史诗地形（复杂地形） | 9.15 KB | 29.6% |

单区块数据越大、内容越复杂，收益越小：区块接近或超过 4 KiB 时，原版扇区对齐的浪费本就不多，
可压缩的空间也随之减少。

### 写入量随 grid 的变化

同一 region 文件（1,024 区块），每轮重写 64 个、共 6 轮：

| grid | 实际落盘 | 最终大小 | 耗时 |
|---|---|---|---|
| 1 | 14.46 MB | 9.64 MB | 362 ms |
| 8 | 1.73 MB | 2.59 MB | 67 ms |
| **16** | **0.95 MB** | 2.66 MB | **33 ms** |
| 32 | 1.14 MB | 3.25 MB | 42 ms |

### 维度差异

| 维度 | 原版 | 本 mod | 节省 |
|---|---|---|---|
| overworld | 2.15 MB | 266 KB | 87.9% |
| the_end | 164 KB | 98 KB | 40.6% |
| the_nether | 318 KB | 325 KB | **-2.0%** |

下界的体积增加了 2%。体积是否缩小取决于该维度的区块密度：区块接近或超过 4 KiB 且分布稀疏时，
原版扇区对齐的浪费本就有限，而 bucket 表有固定开销（grid=16 时每个 region 文件 16,512 B），
两者相抵后可能出现净增加。该存档的下界只生成了少量通道区块，属于这种情形。

同一目录在下界被密集挖掘（隧道网、大型采掘场）时收益为正：下面多人存档的 `the_nether` 为
49.7%。目录越稀疏、单区块越小，bucket 表的固定开销占比越高，此时应把 `grid` 调小；实测数据
见下节。

### 真实多人存档（1.73 GB）

一个多人生存存档：1.21.1 原版 + 优化 mod（carpet 农场、精致存储等），主世界 170,217 个区块、
平均每块 7.6 KB。用离线工具 `bench --grid 16` 在它现有的 Anvil 字节上实测：

| 存储 | 原版 | 本 mod | 节省 |
|---|---|---|---|
| overworld region | 1.21 GB | 761.7 MB | 38.4% |
| the_nether region | 110.0 MB | 55.4 MB | 49.7% |
| the_end region | 239.2 MB | 19.0 MB | **92.1%** |
| overworld entities | 48.7 MB | 8.3 MB | 83.0% |
| overworld poi | 6.4 MB | 2.4 MB | 63.1% |
| the_nether entities | 3.68 MB | 654 KB | 82.7% |
| the_nether poi | 652 KB | 372 KB | 42.9% |
| the_end entities | 1.07 MB | 273 KB | 75.1% |
| the_end poi | 36 KB | 49 KB | **-35.5%** |
| **合计** | **1.727 GB** | **889 MB** | **48.5%** |

末地的 92.1% 来自「已生成但内容稀疏」的情形：岛之间的大片区块，每个在原版都要占满一个
4 KiB 扇区。

上表最后一行是唯一体积增加的目录，也是上述规律的极端样本：整个末地 poi 只有 3 个区块，分布在
81 个 region 文件里，每文件 16,512 B 的桶表成为主要成本。同一批字节，只改 `grid`：

| grid | 大小 | 节省 |
|---|---|---|
| 16 | 48.8 KB | **-35.5%** |
| 4 | 3.80 KB | 89.4% |
| 2 | 1.55 KB | 95.7% |
| 1 | 1,014 B | **97.2%** |

从体积增加到节省 97%，差异全部来自桶表的固定开销。`grid` 只影响新建文件，已有文件保持创建时
的值，因此可以按存档或维度调整。

### 崩溃保护的代价

预写日志带来的额外开销：每批 6 ms → 8 ms（约 +22%）。粒度是一批而非单个 bucket，因此单次
区块保存不会强制刷盘。

## 文件格式

```
[FileHeader 128 B][BucketTable A][BucketTable B][压缩数据块]
```

- region 按 `grid × grid` 划分 bucket，每个 bucket 是一条独立的 zstd 流
- bucket 内为 `ChunkEntry[K]` 索引加上紧凑排列的区块字节，不使用扇区对齐
- 空闲空间由 bucket 表反推，文件中不存 free list
- bucket 表存两份，每项自带序号与自检 CRC，崩溃后可按 bucket 回退
- 一批写入先写入预写日志（`.wal`），完成后删除
- 空闲超过阈值时自动整理；整理只搬移已压缩的字节，不重新压缩；检查发生在保存节点，单个区块
  的保存不会触发整文件重写

完整规范见 [`docs/FORMAT.md`](docs/FORMAT.md)，前期调研见
[`docs/chunk-storage-research.md`](docs/chunk-storage-research.md)。

## 注意事项

1. 存档不再兼容原版，详见开头「使用前须知」。
2. 不要将 `fallbackToMca` 设为 `false`。关闭后，`.cso` 中没有的区块会被视为未生成，地形会被
   静默重新生成。
3. 区块稀疏且单块较大的场景下收益可能为负，见[性能](#性能)中的维度差异。
4. 与 C2ME 不兼容。检测到 C2ME 时本 mod 自动停用并退回原版存储，日志中有提示。两者都会改写
   区块 IO，同时使用会让同一个世界存在两种格式的数据。
5. zstd 不可用时自动降级：native 库加载失败时本 mod 退回原版 Anvil 存储，不影响世界启动。

## 从源码构建

```bash
./gradlew :neoforge:build          # 产物在 neoforge/build/libs/
./gradlew :fabric:build            # 产物在 fabric/build/libs/
./gradlew :core:test               # 只跑格式层，不需要 Minecraft
./gradlew :neoforge:runServer      # 开发用服务端，工作目录 neoforge/run/
./gradlew :fabric:runServer        # 同上，工作目录 fabric/run/
```

多版本构建、发布流程、各版本的实测状态与两个加载器之间的差异见
[`docs/DEVELOPMENT.md`](docs/DEVELOPMENT.md)。
