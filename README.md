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

- 游戏版本：Minecraft 26.1.2 / NeoForge 26.1.2.71 或更高
- 运行环境：Java 25

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

1. 确认已安装 **NeoForge 26.1.2.71 或更高**（Minecraft 26.1.2）。
2. 把 `chunkstorageoptimizer-1.0.1.jar` 放进 `mods/` 目录。
3. 启动游戏，配置文件会生成在 `config/chunkstorageoptimizer-common.toml`。

> 与 **C2ME** 同时安装时，本 mod 会自动停用并退回原版存储，同时在游戏中给出提示。
> 两者都改写了区块 IO，混用会造成存档割裂。

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
| `/cso stats` | 显示累计统计：读写区块数、压缩/解压耗时、缓存命中率、实际压缩比 |
| `/cso reset` | 清零统计 |
| `/cso compact` | 对所有已打开的 region 文件执行一次空间整理 |
| `/cso convert cso [prune]` | 把 `.mca` 转成 `.cso`。加 `prune` 会在验证后删除 `.mca` |
| `/cso convert mca [prune]` | 把 `.cso` 转回 `.mca` 并**自动停用本 mod**。加 `prune` 会删除 `.cso` |

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

### 存档目录结构（26.1.2）

维度数据位于 `<world>/dimensions/minecraft/<维度>/` 下：

```
<saves>/你的世界/
├── level.dat
└── dimensions/minecraft/
    ├── overworld/{region,poi,entities}
    ├── the_nether/{region,poi,entities}
    └── the_end/{region,poi,entities}
```

`region`、`poi`、`entities` 三个目录是同一种 region 文件，同一套命令都能处理。

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

下界略有增大（约 2%）。原因是当**单区块数据接近或超过 4 KiB 且分布稀疏**时，
原版扇区对齐的浪费本就不多，而 bucket 表有固定开销（grid=16 时每个 region 文件约 16 KB）。
这类情况把 `grid` 调小到 1 或 2 即可改善。

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
