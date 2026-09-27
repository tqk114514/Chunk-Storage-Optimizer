# Chunk Storage Optimizer

> ## ⚠️ 动手前先读这两条
>
> **1. 存档一旦被本 mod 写入，就不再是原版能读的存档。** 卸载 mod 后，只存在于 `.cso` 里的
> 进度对原版不可见，MCA Selector 等外部工具也打不开。要回到原版，先执行
> `/cso convert mca prune`。**操作前请备份存档。**
>
> **2. 大版本之间格式不互通，且不打算做兼容。** 本项目的版本规则是：**只要动了文件格式或
> 任何不兼容的东西，就跳大版本**（1.0.1 → 2.0.0）；同一大版本内（1.0.1 → 1.4.3 → 1.100.0）
> 格式完全互通，直接覆盖升级即可。也就是说 **2.x 不会去读 1.x 写的存档**。跨大版本只有一条
> 路：先用旧版本 `/cso convert mca` 转回原版格式，换版本，再转回来。升级前看 `CHANGELOG.md`。

用自定义的区块存储格式替换 Minecraft 的 `.mca` region 文件：把 32×32 的 region 切成多个
bucket，每个 bucket 用一条 zstd 流整体压缩，并去掉原版按 4 KiB 扇区对齐带来的填充。
结果是**更小的存档**和**更快的读写**。

在一个 889 MB 的城市存档上实测：体积减少 55%，写入快 8.1 倍，读取快 3.1 倍。

- 游戏版本：Minecraft 1.21 / 1.21.1 / 1.21.3 / 1.21.4 / 1.21.5 / 1.21.8 / 1.21.10 / 1.21.11
  / 26.1.2 / 26.2.0，**每个游戏版本一个 jar**
- 运行环境：Java 21（1.21.x）或 Java 25（26.x），跟随 Minecraft 本身的要求

---

## 目录

- [安装](#安装)
- [开始使用](#开始使用)
- [配置](#配置)
- [游戏内命令](#游戏内命令)
- [离线工具](#离线工具)
- [性能](#性能)
- [文件格式](#文件格式)
- [注意事项](#注意事项)
- [从源码构建](#从源码构建)

---

## 安装

1. 按游戏版本挑 jar：文件名中间那段就是 Minecraft 版本，例如
   `chunkstorageoptimizer-26.1.2-1.0.2.jar`。放进 `mods/` 目录即可。
2. 每个 jar 只认自己那个 Minecraft 版本，并要求 NeoForge 不低于**该版本的第一个稳定构建**——
   逐版列在 `supported-versions.csv` 里，加载器也会在版本不够时直接拒绝启动而不是崩在半路。
   Minecraft 26.1.2 对应的下限是 NeoForge 26.1.2.71。
3. 启动游戏，配置文件会生成在 `config/chunkstorageoptimizer-common.toml`。

> 与 **C2ME** 同时安装时，本 mod 会自动停用并退回原版存储，同时在游戏中给出提示。
> 两者都改写了区块 IO，混用会造成存档割裂。

> **只需要装在世界所在的那一侧。** 这个 mod 不注册任何网络通道，客户端侧的代码只有配置界面，
> 改的纯粹是磁盘上的区块存储。所以专用服务器只装服务端就行；联机时房主装、房客什么都不用装；
> 房客装了而房主没装也不会变成"必须一致"。唯一的前提是同一个存档只能由装了 mod 的一侧读写
> （见开头第一条警告）。

---

## 开始使用

### 新世界

直接开始游戏即可。所有区块数据都会写入 `.cso` 文件，不会产生任何 `.mca`。

### 已有世界

本 mod 采用**渐进迁移**：`.cso` 里没有的区块会自动回退读取同名的 `.mca`，所以启用后
已有的存档数据不会丢失，新的写入会逐步迁移到新格式。

需要注意：随着区块被修改，旧数据仍留在 `.mca` 中，而新版本写入 `.cso`，
**两份数据会同时占用磁盘**。推荐做一次完整迁移：

```
/cso convert cso prune
```

这会把当前世界各维度、各目录的 `.mca` 全部转成 `.cso`，**验证后删除原文件**，
避免双份占用。转换前会自动执行一次 `save-all flush`。

---

## 配置

`config/chunkstorageoptimizer-common.toml`：

> 生成的 toml 里**没有注释**：NeoForge 只会把代码里的 comment 写进文件，而选项说明需要按语言
> 各出一份，所以它们放在语言文件里。含义见下表，或游戏内 **Mods → 选中本 mod → Config**
> 界面（简中 / 繁中 TW / 繁中 HK 均有）。

| 键 | 默认 | 说明 |
|---|---|---|
| `enabled` | `true` | 总开关。关闭后使用原版 Anvil 存储，不会改动任何已有文件 |
| `grid` | `16` | bucket 网格边长（1/2/4/8/16/32）。见下方说明 |
| `compression` | `zstd` | 压缩算法，`zstd` 或 `none` |
| `zstdLevel` | `3` | zstd 等级 1–22。等级越高压缩越好、写入越慢 |
| `cachedBuckets` | `4` | 每个 region 文件缓存的解压 bucket 数 |
| `verifyCrc` | `true` | 读取时校验 CRC32。关闭可获得少量性能，但失去损坏检测 |
| `fallbackToMca` | `true` | `.cso` 中没有的区块回退读取 `.mca`。**不要关闭** |
| `compactionMinBytes` | `4194304` | 触发空间整理的最小浪费字节数 |
| `compactionRatio` | `0.25` | 触发整理的浪费/占用比 |
| `batchMaxChunks` | `16` | 攒够多少个区块变更后提前写入。设为 1 可关闭批处理 |
| `batchMaxDelayMs` | `5000` | 暂存写入的最长停留时间，超时即落盘 |

> 升级本 mod 后，已存在的配置文件**不会**被新的默认值覆盖。
> 若 `grid` 等项仍是旧值，需要手动修改。

### grid 怎么选

grid 决定「压缩率 ↔ 写入代价」的平衡。在 889 MB 城市存档上实测：

| grid | 每 bucket 区块数 | 体积 | 写入代价 |
|---|---|---|---|
| 1 | 1024 | 最优（省 59.3%） | 最高，是 grid=16 的约 15 倍 |
| 8 | 16 | 省 58.5% | 约为 grid=16 的 2 倍 |
| **16** | **4** | **省 55.4%** | **最低** |
| 32 | 1 | 更差 | 反而上升（失去跨区块共享上下文） |

- **活跃世界**（持续写入）用默认的 `16`，写入量与耗时最低。
- **归档或极少改动**用 `8` 或更小，体积优先。
- **区块稀疏且单区块数据较大的世界/维度**（例如只加载了少量区块的下界），
  建议调到 `1` 或 `2`。详见[性能](#性能)中的说明。

---

## 游戏内命令

需要管理员权限。

| 命令 | 作用 |
|---|---|
| `/cso stats` | 累计统计：读写区块数、压缩/解压耗时、缓存命中率、实际压缩比，外加**延迟分布**（批写入 / 压缩 / 解压的 p50、p95、max）和**每个存储一行**（哪个维度的 region/poi/entities 各读了多少、flush 多久） |
| `/cso reset` | 清零统计 |
| `/cso compact` | 对所有已打开的 region 文件执行一次空间整理 |
| `/cso report [files]` | 抽样本存档各维度目录，按 grid 1/8/16/32 报出"同样的区块会占多大"，用来决定 grid 该调成几。`files` 是每个目录抽几个文件（默认 2，最多 8）；在后台线程跑，跑完再发到聊天 |
| `/cso convert cso [prune]` | 把 `.mca` 转成 `.cso`。加 `prune` 会在验证后删除 `.mca` |
| `/cso convert mca [prune]` | 把 `.cso` 转回 `.mca` 并**自动停用本 mod**。加 `prune` 会删除 `.cso` |

`report` 的百分比是**相对抽样文件当前磁盘占用**：源是 `.mca` 时就是"相比原版能省多少"，源已是
`.cso` 时是"相比现在重写一遍能省多少"。分位数来自对数分桶直方图（桶宽是 2 倍关系），所以是
近似值——够用来判断"有没有卡"，不够用来报精确延迟。

`convert` 会先执行 `save-all flush`（游戏自身还有一份未落盘的区块队列），再关闭所有
文件句柄，然后才开始搬运字节。

**删除操作不可逆。** 每个文件都是「转换 → 读回校验 → 才删除」，校验不一致时立即中止，
不会删掉任何东西。仍建议先备份存档。

---

## 离线工具

不启动游戏即可对磁盘上的存档操作。工具只搬运 NBT 字节、不解析它们。

```bash
# 只报告体积对比，不改动任何文件
./gradlew csoTool -PcsoArgs="bench --grid 16 --level 3" -PcsoDir="<存档>/dimensions/minecraft/overworld/region"

# 转换（原文件保留）
./gradlew csoTool -PcsoArgs="convert --to cso --grid 16" -PcsoDir="<目录>"

# 反向转换：.cso -> .mca
./gradlew csoTool -PcsoArgs="convert --to mca" -PcsoDir="<目录>"

# 读写速度对比（多轮取中位数）
./gradlew csoTool -PcsoArgs="ab --grid 16 --level 3" -PcsoDir="<目录>"

# 各 grid 的写入量对比
./gradlew csoTool -PcsoArgs="amp --level 3" -PcsoDir="<目录>"

# 预写日志带来的写入开销
./gradlew csoTool -PcsoArgs="walcost --level 3" -PcsoDir="<目录>"

# 统计目录内的区块总数与平均区块大小（只读，常用于对照实验前确认两边一致）
./gradlew csoTool -PcsoArgs="count" -PcsoDir="<目录>"
```

`bench`/`ab`/`amp`/`walcost` 读目录里现成的格式：有 `.mca` 就用它（那是原版真实写出的字节），
只剩 `.cso` 时自动改用 `.cso`——**已经转换完的存档照样能测**。此时 anvil 那一行是本工具重写出
的估算值，输出里会标注 `[rebuilt by this tool — an estimate]`。两种格式同时存在（渐进迁移的
常态）而你想强制读某一侧，加 `--from cso` 或 `--from mca`。

> 做「两个存档比大小」这类对照时，务必先用 `count` 确认两边区块数一致。
> 区块数不同的话，体积差里混着内容差异，数字没有意义。

**路径含空格时必须用 `-PcsoDir`**，不要放进 `-PcsoArgs`（会被按空格拆成两个参数）。

### 存档目录结构

两种布局，取决于游戏版本——1.21 全线（含 1.21.11）还是老布局，26.x 起维度被挪进
`dimensions/<namespace>/<维度>/`：

```
<saves>/你的世界/                     # 1.21 线（实测 1.21.1 与 1.21.11 一致）
├── level.dat
├── {region,poi,entities}             # 主世界直接在存档根下
├── DIM-1/{region,poi,entities}       # 下界
└── DIM1/{region,poi,entities}        # 末地

<saves>/你的世界/                     # 26.x
├── level.dat
└── dimensions/minecraft/
    ├── overworld/{region,poi,entities}
    ├── the_nether/{region,poi,entities}
    └── the_end/{region,poi,entities}
```

`.cso` 永远写在游戏递给 mod 的那个目录里、和同名 `.mca` 平级，所以两种布局都不需要特别处理：
1.21.11 的 dev 服务器实测落在 `<世界>/region/r.0.0.cso`、`<世界>/poi/…`、`<世界>/entities/…`，
26.1.2 落在 `<世界>/dimensions/minecraft/overworld/region/…`。`region`、`poi`、`entities`
三种目录是同一种 region 文件，同一套命令都能处理。

---

## 性能

数据来自一个 889 MB 的真实城市存档（Los Perrito，201,321 个区块），
grid=16、zstd L3。

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

**数据越稀疏、单块越小，收益越大**——原版为每个区块预留 4 KiB 扇区，这类目录正是浪费的重灾区。

### 同种子对照

两个存档均为原版地形生成，使用同一种子、同一视距，原地不动直到加载完成后退出：

| | 文件 | 区块数 | 大小 | 平均每区块 |
|---|---|---|---|---|
| 原版 `.mca` | 16 | **8,281** | 54.44 MB | 6,893 B |
| 本 mod `.cso` | 16 | **8,281** | 36.48 MB | 4,619 B |

两者区块数完全相同，因此 **33.0% 的差距完全来自格式**，不含任何内容差异。
同一对照中 entities 省 61.3%，poi 省 20.9%。

### 收益随单区块大小变化

| 存档 | 原版平均每区块 | 节省 |
|---|---|---|
| 城市存档（密集建筑） | 4.6 KB | 55.4% |
| 原版地形（上表） | 6.89 KB | 33.0% |
| 多人存档（见下节） | 7.62 KB | 38.4% |
| 史诗地形（复杂地形） | 9.15 KB | 29.6% |

单区块数据越大、内容越复杂，收益越小。区块接近或超过 4 KiB 时，原版扇区对齐的浪费
本就不多，能压缩的空间也随之减少。

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

下界这里**反而大了 2%**。决定符号的不是维度，是**那个维度的活动密度**：当区块
接近或超过 4 KiB **且分布稀疏**时，原版扇区对齐的浪费本就不多，而 bucket 表有固定开销
（grid=16 时每个 region 文件 16,512 B），两头一抵就可能倒贴。这个存档的下界只浅浅探了几条
通道，正是这种画像。

同一个目录下界如果被密集挖掘（隧道网、大型采掘场），收益就会翻回来——见下面那个多人存档的
`the_nether`：**49.7%**。所以别把这张表读成"下界没用"，要读成"你在那个维度铺得越开、
每格越空，越要把 `grid` 调小"。稀疏目录的实际效果见下节的实测曲线。

### 真实多人存档（1.73 GB）

朋友一起玩的存档：1.21.1 原版 + 优化 mod（carpet 农场、精致存储等），主世界 170,217 个区块、
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

末地那 92.1% 是"已生成但内容稀疏"的典型：岛之间大片区块，每个在原版都要占满一个 4 KiB 扇区。

最后一行是**唯一倒贴的目录**，也是上面那条规律的极端样本：整个末地 poi 只有 3 个区块，摊在
81 个 region 文件里，于是每文件 16,512 B 的桶表成了主要成本。同一批字节，只改 `grid`：

| grid | 大小 | 节省 |
|---|---|---|
| 16 | 48.8 KB | **-35.5%** |
| 4 | 3.80 KB | 89.4% |
| 2 | 1.55 KB | 95.7% |
| 1 | 1,014 B | **97.2%** |

从倒贴 35% 到省 97%，差额全在桶表的固定开销上。这就是 `grid` 值得按存档/维度调一次的原因
（`grid` 只影响新建文件，已有文件保持自己出生时的值）。

### 崩溃保护的代价

预写日志带来的额外开销：每批 **6 ms → 8 ms（约 +22%）**。
粒度是一批而非单个 bucket，因此不会让每次区块保存都强制刷盘。

---

## 文件格式

```
[FileHeader 128 B][BucketTable A][BucketTable B][压缩数据块]
```

- region 按 `grid × grid` 划分 bucket，每个 bucket 是一条独立 zstd 流
- bucket 内为 `ChunkEntry[K]` 索引 + 紧凑排列的区块字节，**不使用扇区对齐**
- 空闲空间由 bucket 表反推，文件中不存 free list
- bucket 表存**两份**，每项自带序号与自检 CRC —— 崩溃时可逐 bucket 回退
- 一批写入先写**预写日志**（`.wal`），完成后才删除
- 空闲超过阈值时自动整理，整理只搬移已压缩的字节，不重新压缩；检查发生在保存节点，不会让某个区块的写入突然背上整文件重写的开销

完整规范见 [`docs/FORMAT.md`](docs/FORMAT.md)，前期调研见
[`docs/chunk-storage-research.md`](docs/chunk-storage-research.md)。

---

## 注意事项

**1. 存档不再兼容原版。** 卸载本 mod 后，只存在于 `.cso` 中的进度对原版不可见，
MCA Selector 等外部工具也无法读取。卸载前请执行 `/cso convert mca prune`。

**2. `fallbackToMca` 不要设为 `false`。** 关闭后，`.cso` 中没有的区块会被当作未生成，
**地形会被静默重新生成**。

**3. 区块稀疏且单块较大的场景下收益可能为负。** 见[维度差异](#维度差异)。

**4. 与 C2ME 不兼容。** 检测到 C2ME 时本 mod 会自动停用并退回原版存储，日志中有提示。
两者都改写了区块 IO，同时启用会让一个世界被写成两套格式，导致存档割裂。

**5. zstd 不可用时会自动降级。** 若 native 库加载失败，本 mod 会退回原版 Anvil 存储，
不会导致世界无法启动。

---

## 从源码构建

```bash
./gradlew build
```

构建产物在 `build/libs/`。单元测试覆盖格式层，该层不依赖 Minecraft，可独立运行：

```bash
./gradlew test
```

启动开发服务器：

```bash
./gradlew runServer
```

### 多版本构建

`minecraft_version` 是唯一的版本开关。一个 Minecraft 对应哪一行的 NeoForge 下限、写进 mod 元数据
的版本范围、Java 级别、命令权限 API 走哪套，全部由 `supported-versions.csv` 里的那一行决定：

```bash
./gradlew build -Pminecraft_version=1.21.11     # csv 里列出的版本都能直接编
```

同一份 csv 也是 CI 矩阵的来源（workflow 读第一列），所以"本机编得动的版本"和"会被发布的版本"
不可能各说各话。加一个新版本只要两步：

```bash
tools/oldest-stable-neoforge.sh 26.3.0
# ① 把脚本打出来的行填进 supported-versions.csv：<mc>, <最老稳定版>, <java>, <legacy|modern>
#    java 看 Mojang 版本清单的 javaVersion，family 看这个 MC 有没有 net.minecraft.server.permissions
# ② ./gradlew build -Pminecraft_version=26.3.0 验证
```

下限只取**稳定版**：带 `-beta` / `-alpha` 后缀的构建玩家装不到，拿它当下限等于承诺一个不存在的地盘。
顺带两个事实——**MC 26.1** 的 NeoForge 只出到 beta 就被 26.1.2 取代了；**1.21.2 / 1.21.6 / 1.21.7 /
1.21.9** 同样只有 beta，所以这四个版本没有 jar 可发。

版本范围是**精确单版本**（`[26.1.2]`），不是 `>=`。因为 `mixins.json` 里 `defaultRequire: 1`，
签名一漂移就是启动崩溃；精确范围让没测过的版本在加载前就被干净拒绝，而不是崩在玩家机器上。

### 跨版本的编译缝

1.21 到 26.x 之间真正对不上的 API 只有一处：命令权限。`src/version/legacy/java` 和
`src/version/modern/java` 各放一个**同签名**的 `CsoPermissions.operatorOnly()`，构建时按 csv 里的
family 只把其中一个放进编译路径——运行期既没有 if/else 也没有反射，两边都是 op 等级 3 这道杠。

另一处曾经的差异是 26.1 把 `ChunkPos` 改成了 record（`pack`/`unpack`/`x()`/`z()`）。等待编译的
存档映射键本来就不该借游戏的类，所以 `CsoStorage` 改用自己的坐标打包，这条差异就此消失。
除此之外区块存储的入口 `RegionFileStorage` 签名在整个范围内一字未动，这也是只需要一个缝的原因。

### 十行版本各自的实测状态

| Minecraft | 加载器接受 jar | mixin 注入 | `/cso` 可执行 | 写成 `.cso` |
|---|---|---|---|---|
| 1.21 | ✅ | ✅ | ✅ | ✅ 真实服务端实测 |
| 1.21.1 / 1.21.3 / 1.21.4 / 1.21.5 / 1.21.8 | ✅ | ✅ | 未单独测 | ✅ 同上（同一代加载器） |
| 1.21.10 / 1.21.11 | ✅ | ✅ | ✅ | ✅ dev 实测（5 个 / 9 个文件，0 个 `.mca`） |
| 26.1.2 / 26.2.0 | ✅ | ✅ | ✅ | ✅ dev 实测 |

"真实服务端实测"是拿发行路径跑的：在临时目录用 `neoforge-21.0.143-installer.jar --installServer`
装一个正经的 1.21 服务端，把 `build/libs/chunkstorageoptimizer-1.21-1.0.2.jar` 丢进 `mods/`，
RCON 打 `cso stats` 与 `save-all flush`。结果：mod 加载、mixin 注入、**zstd 从嵌套 jar 里正常取出**，
`<世界>/region/*.cso` 与 `<世界>/entities/*.cso` 各就位、一个 `.mca` 都没留，
`/cso stats` 报 4,986 区块读取 / 88 写入、6.0 MB 原始 → 963.6 KB 落盘。1.21.1～1.21.8 与它同代
加载器（FML 4.x～6.x），机制上没有差别，所以只测了这一行最老的。

一处 **dev 专属的不对称**要记下来，免得有人以为格式在老版本上不行：`./gradlew runServer` 在
21.8 及更早的构建上看不到 zstd（`NoClassDefFoundError` → 按设计退回原版 Anvil），21.10 起正常。
两边的 `build/moddev/serverLegacyClasspath.txt` 都不含 zstd，差异在老几代 FML 不把启动 classpath
上的第三方库暴露给 mod 模块层；发行路径走嵌套 jar，与这条无关，已按上一段实测。

顺带一条实测：整个 1.21 线（1.21.1、1.21.11 与真实 1.21 服务端都看过）仍是老存档布局
`<世界>/{region,poi,entities}` + `DIM-1/` + `DIM1/`，26.x 才换成
`<世界>/dimensions/minecraft/<维度>/…`。`.cso` 跟着游戏给的目录走，两种布局的落盘位置都实测到了。

### 移植到其他加载器（未做，缝已留好）

按实际 import 关系，换加载器需要重写的只有 4 个文件：`ChunkStorageOptimizer`（mod 入口）、
`ChunkStorageOptimizerClient`（客户端配置界面）、`Config`（`ModConfigSpec`）、
`commands/CsoCommands`（事件注册与权限声明）。`storage/CsoStorage` 只依赖 Minecraft 类，
而 `format` / `metrics` / `tools` 既不依赖 Minecraft 也不依赖任何加载器——格式层能脱离游戏
独立跑测试就是因为这条边界。所以换加载器不必动格式，也不必动存档语义。
