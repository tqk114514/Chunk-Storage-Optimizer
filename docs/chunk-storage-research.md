# 区块存储优化 Mod — 技术调研报告

- 目标版本：Minecraft **26.1.2** / NeoForge **26.1.2.109** / Java 25
- 调研日期：2026-09-18
- 分析基准：本机 `~/.gradle/caches/neoformruntime` 中 26.1.2 的**反编译产物**（未经 rename，即无混淆原始类名）
- 关键环境变量：`WORLD_VERSION = 4790`（26.1.2 的 DataVersion）

---

## 0. 结论先行

1. **26.1.2 仍然是经典 Anvil 扇区格式**，没有所谓的 "linear region file format"。全量 grep `linear` 只命中 `LinearPalette` / `Strategy`（调色板层面）。linear 格式是 **xymb 的社区方案**（Purpur/Leaves/Kaiiju fork），从未并入原版。这一点直接决定：原版的碎片化与逐区块独立压缩问题**全部还在**，是 mod 的机会窗口。
2. **存在一条零兼容性代价、体积 -20%~30% 的路子**：`RegionFileVersion.VERSION_DEFLATE` 用的是 `new DeflaterOutputStream(out)`，即 zlib **默认压缩等级 6**。改成 level 9（仍带 zlib 头）后，原版 `InflaterInputStream` 完全可读。这是唯一"改了就能省空间、卸载 mod 也不损坏存档"的大幅优化，应作为第一刀。
3. **体积的真正大头不是 NBT 冗余，而是格式本身**：4 KiB 扇区对齐 + 每区块独立压缩流（无法跨区块共享字典）。NBT 层能抠出来的空 list/compound 只有几十字节/区块，占比 <1%，不值得优先做。
4. **效率瓶颈在写路径，不在读路径**：每次写区块都全量重写 8192 字节 header；`RegionFile` 的读与写都是 `synchronized`，同 region 内完全串行；`IOWorker` 每个前台任务后只写**一个** pending chunk，无法批处理。
5. **已有直接竞品且已覆盖 26.1**：LinearReader（NeoForge/Fabric/Forge，支持 26.1–26.3，zstd + linear）。但作者已声明 1.3.0 后停止开发。差异化应打"**可逆、零风险、兼容外部工具链**"，而不是再做一个更激进的格式。

---

## 1. 26.1.2 存储栈现状（源码级）

### 1.1 调用链

```
ServerChunkCache / ChunkMap (保存决策)
        ↓
SimpleRegionStorage.write(pos, Supplier<CompoundTag>)      // SimpleRegionStorage.java:44
        ↓
IOWorker.store(pos, supplier)                              // IOWorker.java:129
        ↓  PriorityConsecutiveExecutor(FOREGROUND) 入队
IOWorker.runStore → storage.write(pos, data)               // IOWorker.java:228
        ↓  PriorityConsecutiveExecutor(BACKGROUND)，每次只写一个
RegionFileStorage.write(pos, tag)                          // RegionFileStorage.java:75
        ↓  LRU 256 个 RegionFile
RegionFile.getChunkDataOutputStream(pos)                   // RegionFile.java:266
        ↓  DataOutputStream ← version.wrap(ChunkBuffer)
NbtIo.write(tag, out)  →  ChunkBuffer.close()  →  RegionFile.write(pos, ByteBuffer)
```

读取链：`IOWorker.loadAsync` → `RegionFileStorage.read` → `RegionFile.getChunkDataInputStream` → `NbtIo.read`。
扫描链（用于 isOldChunkAround 等）：`IOWorker.scanChunk` → `NbtIo.parse(stream, visitor)`，走流式 NBT，不建整棵树。

### 1.2 `RegionFile`（404 行）— 仍是扇区格式

| 项 | 值 | 源码位置 |
|---|---|---|
| 扇区大小 | 4096 B | `SECTOR_BYTES = 4096` |
| Header | 8192 B = 1024×int offset + 1024×int timestamp，direct ByteBuffer | L43-64 |
| offset 打包 | `index << 8 \| size`（扇区号 24 bit + 扇区数 8 bit） | `packSectorOffset` L202 |
| 分配器 | `RegionBitmap`（BitSet，首次适配 `nextClearBit` 扫描） | RegionBitmap.java:20 |
| 外置大区块阈值 | ≥256 扇区（约 1 MiB）→ `c.x.z.mcc` | `EXTERNAL_CHUNK_THRESHOLD = 256` |
| 区块 payload | 4B length + 1B compression id + 压缩数据，padding 到扇区边界 | L124-150 |
| 并发 | `getChunkDataInputStream` / `write` 均 `synchronized` | L113, L286 |

与 1.21.1 相比的变化：分配器从线性扫描换成 `RegionBitmap`（更快），新增 `.mcc` 外置阈值；**格式本身没变**。

### 1.3 `RegionFileVersion`（114 行）— 压缩注册表

| id | 名称 | 实现 |
|---|---|---|
| 1 | gzip | `GZIPOutputStream` |
| 2 | **deflate（默认）** | `new DeflaterOutputStream(out)` ← **等级 6** |
| 3 | none | 直通 |
| 4 | lz4 | `LZ4BlockOutputStream` |
| 127 | custom | 跟着一个命名空间 ID；**原版读取直接报错返回 null** |

- 由 server.properties 的 `region-file-compression` 选择（`configure()`，L77）。
- `DEFAULT = VERSION_DEFLATE`，`selected` 是 `volatile static`。
- 构造器与 `register()` 都是 **private**，注册自定义压缩必须靠 Mixin/AW。

### 1.4 `SerializableChunkData`（614 行）— NBT 读写核心

record 字段涵盖 `containerFactory / chunkPos / minSectionY / lastUpdateTime / inhabitedTime / chunkStatus / blendingData / belowZeroRetrogen / upgradeData / carvingMask / heightmaps / packedTicks / postProcessingSections / lightCorrect / sectionData / entities / blockEntities / structureData`。

`write()`（L413-477）的行为要点：
- section 只在 `!sectionTag.isEmpty()` 时写入（L447）→ 空段已优化。
- `BlockLight` / `SkyLight` 只在非 null 时写入（L439-445）→ 空光照已优化。
- **无条件的空容器**：`block_entities`（L458-460）、`PostProcessing`（L471）、`structures`（L475）即使为空也会写入。
- `Heightmaps` 每个类型 37 个 long（296 B），通常 4–6 个类型。
- 调色板序列化走 `Codec`：`PalettedContainer.codecRW/codecRO`，`palette` 必填、`data` 可选（单值时省略）。

### 1.5 调色板位宽

`Strategy.createForBlockStates`（Strategy.java）：

```java
case 0            -> ZERO_BITS;         // 单值
case 1, 2, 3, 4   -> FOUR_BITS_LINEAR;  // ← 最小 4 bit
case 5,6,7,8      -> HASHMAP_PALETTE
default           -> Configuration.Global
```

**方块调色板写入时最小 4 bit**，即 data 数组固定 `4096 × 4 / 8 = 2048 B`。只有 2–3 种方块的段（典型：空气+水、石头+空气）理论上 1–2 bit 就够，这里浪费 1024–1536 B/段。
生物群系策略更低（`ONE_BIT` / `TWO_BIT` / `THREE_BITS_LINEAR`），已无此问题。

⚠️ **兼容性红线**：位宽是"写入时按 palette 大小算、读取时按 data 数组长度反推"的。若写入 1 bit（64 个 long），原版读取算出 bits=1 后走 `FOUR_BITS_LINEAR`，按 4 bit 解析 → 数据错位/越界。**改位宽 = 原版不可读**，与改压缩算法同级别的风险。

---

## 2. 体积从哪来（拆解与量化）

| 来源 | 量级（每 region，1024 区块） | 可优化性 |
|---|---|---|
| **扇区对齐 + 碎片** | 约 1–2 MiB（xymb 实测均值 ≈1 MiB/region） | ✅ 换格式可彻底消除 |
| **逐区块独立压缩流** | 无法跨区块共享字典，是压缩率的天花板 | ✅ 换格式（整 region 单流 / 分桶流） |
| 调色板 4 bit 下限 | 每"稀疏段"浪费 1–1.5 KiB，压缩后残留有限 | ⚠️ 有收益但**破坏兼容** |
| 光照数据 | 2048 B/段，非空时全额存；空时已省略 | ⚠️ 已是原版最优（除非改光照编码） |
| Heightmaps | 296 B × 4–6 类型 ≈ 1.2–1.8 KiB/区块 | 中等，可裁剪类型但有语义风险 |
| **空 NBT 容器**（block_entities / PostProcessing / structures） | 原始 35–60 B/区块，压缩后约 10–20 B | ⚠️ 收益 <1%，优先级最低 |

**社区实测参照（LinearReader 官方基准，Overworld 8.38 GB）**：

| 方案 | 压缩比 | 吞吐 |
|---|---|---|
| Anvil + zlib（原版） | 5.44x | 75.7 MB/s |
| zstd L4 | 7.39x | 585.5 MB/s |
| zstd L22（离线重压） | 10.71x | 4.4 MB/s |

另一份实测（WorldRegionOptimizer，仅 1.12 格式）：zlib L1→L9 可省 **20–30%**；消除碎片 5–10%；删空段 5–20%。

> 说明：以上为公开来源的实测数据，非本仓库实测。**落地前必须在真实存档上做一次 A/B**，本报告不替代实测。

---

## 3. 效率瓶颈（可直接动手的点）

1. **每次写都全量重写 8 KiB header**（`writeHeader()`，RegionFile.java:337）。改一个区块只需更新 4 B offset + 4 B timestamp，却写了 8192 B，且写在 synchronized 块内。→ 改为定点 4+4 B 写入。
2. **`RegionFile` 读写全程 synchronized**（L113 / L286）。同 region 内所有 IO 串行；`RegionFileStorage` 只按 region 分桶（LRU 256），热区争锁明显。
3. **读路径每次分配 `numSectors × 4096` 的堆 ByteBuffer**（L121）。典型 8–32 KiB/次，高频分配 → GC 压力。可复用直接缓冲区池。
4. **写路径三跳拷贝**：`ChunkBuffer(ByteArrayOutputStream)` → `ByteBuffer.wrap` → `FileChannel.write`；压缩后又整块落盘。可压缩到直接内存后再写。
5. **`IOWorker` 不批处理**：每个前台任务后 `tellStorePending()` 调度一个 BACKGROUND 任务，`storePendingChunk()` 只 `pollFirstEntry()` **一个**区块（L216-222）。同 region 的多个待写区块无法合并，header 重写次数 = 写区块次数。
6. **`flush()` / `close()` 调用 `file.force(true)`**（L270, L360）→ 全量 fsync。开启 `sync-chunk-writes` 时构造参数带 `StandardOpenOption.DSYNC`（L66），写入性能数量级下降（默认关闭）。
7. **`close()` 时 `padToFullSector()`**（L367）→ 每次 region 淘汰出 LRU 都有一次补齐写。

---

## 4. 技术路线对比

| 方案 | 体积收益 | 速度收益 | 兼容性代价 | 实施难度 |
|---|---|---|---|---|
| **A. deflate 等级/策略调优**（level 6→9，仍带 zlib 头） | **-20%~30%** | 压缩略慢、解压略快 | **零**（原版 Inflater 可读） | 低 |
| **B. IO 路径优化**（header 增量写、缓冲池、批量写、锁粒度） | ~0（碎片略降） | **显著** | 零 | 中 |
| **C. 分配器优化**（best-fit / 按大小分桶，减少碎片） | -5%~10% | 略降写放大 | 零 | 中 |
| **D. 换压缩算法**（zstd via id 127 或改写 id 2 语义） | -10%~25%（无跨区块字典） | **快很多** | **高**：127 原版直接报"Unrecognized custom compression"→ 区块丢失 | 中 |
| **E. 自定义 whole-region 容器格式**（整 region zstd、无扇区对齐） | **-30%~50%** | 显著 | **极高**：MCA Selector 等工具失效、必须提供双向转换、卸载前必须导出 | 高 |
| **F. NBT 层精简**（空容器消除、裁剪 heightmap） | <3% | 略 | 低（但要保证 DFU 与 record 完整性） | 低 |

**建议分层推进**：A+B+C 是同一层（零风险，可打包为默认档，卸载不留痕）→ D/E 是第二层（可选开关 + 强制备份 + 双向转换工具 + 启动自检）。

---

## 5. 落地路径（NeoForge 26.1.2）

### 5.1 介入点

- **NeoForge 内置 Mixin，无需额外插件**。声明方式：`neoforge.mods.toml` 的 `[[mixins]]` 段（`config` 必填，另有 `behaviorVersion`，必须在 NeoForge 默认行为版本与运行时 Fabric Mixin 版本之间）。项目模板里已经预留了注释块。
- **MixinExtras 自 NeoForge 20.2.84+ 已内置**，26.1 可直接用，无需 jarJar。
- **无官方扩展点可替换存储实现**。查 NeoForge `26.1.x` 分支 `patches/net/minecraft/world/level/chunk/storage/`，只有 `EntityStorage` / `SectionStorage` / `SerializableChunkData` 三个 patch，**没有** `RegionFile` / `RegionFileStorage` / `IOWorker` / `RegionFileVersion` 的 patch。
- 可用事件只有 NBT 层：`ChunkEvent.Load/Unload`、`ChunkDataEvent.Load/Save`（**不可取消**，Save 中改 data 不会回写已序列化数据）→ **不足以做存储层替换，必须 Mixin**。
- 可行的注入点（与社区同类一致）：`RegionFileVersion`（注册/改默认压缩）、`RegionFile`（write / getChunkDataInputStream / writeHeader / usedSectors）、`RegionFileStorage`（整类替换，XuanRikka/linear 即由此切入）、`IOWorker`（批处理）。

### 5.2 冲突与红线

- **C2ME 的 `ioSystem.replaceImpl` 与线性/自定义格式直接冲突**：若 C2ME 绕过 mod 直接读写 `.mca`，已转换的存档会被当空白 → **地形静默重新生成**。LinearReader 要求把 `replaceImpl` 设为 `false`；XuanRikka/linear 写了 `checkC2meCompat()` 启动守卫（fail-closed）。**我们也要做同样的启动自检。**
- 只读 `.mca` 的生态（MCA Selector、Region Fixer、备份脚本、外部地图工具）在换格式后全部失效。MCA Selector 至今不支持 linear（issue #489 仍开放）。
- 切换压缩算法**不会**自动重压旧区块 → 新旧格式长期混存，需要显式的"全量重压"命令与进度反馈。
- 卸载 mod 前必须能导出回原版格式，否则原版打不开。

---

## 6. 竞品与差异化

| 项目 | 平台/版本 | 做法 | 状态 |
|---|---|---|---|
| **LinearReader**（Bugfunbug） | Fabric/Forge/**NeoForge**，1.20.x–**26.3** | linear + zstd；热写低等级、空闲时 L22 重压；带备份/缓存/裁剪 | MIT，**作者称 1.3.0 后停止开发** |
| XuanRikka/linear | Fabric 26.2 | LinearV2 / BufferedLinearV3，经 `RegionFileStorage` mixin 切入 | 活跃 |
| LinearRegionFileFormatTools（xymb） | Purpur/Leaves/Kaiiju fork | linear v1/v2 + zstd，附 Python 转换器 | 停更（2024-08） |
| Luminol | Paper fork，含 26.1.x 分支 | LINEAR_V2 / B_LINEAR（16 bucket 懒加载 + 后台 flush） | 活跃 |
| C2ME | Fabric/NeoForge | `ioSystem.replaceImpl` 重写 chunk IO 线程模型 | 活跃 |
| Lithium | Fabric/NeoForge | chunk 序列化与 palette 紧凑化 | 活跃 |
| Cesium Storage Format | Fabric | LMDB 后端替代 Anvil | 高度实验 |

**差异化定位**：现有方案几乎全部是"换格式换压缩"的单点激进路线，且都要求用户接受不可逆转换。可打的差异化是：

1. **可逆优先**：默认档（deflate L9 + IO 优化 + 分配器）100% 兼容，卸载即还原；激进档必须显式开启 + 自动备份 + 一键导出。
2. **可观测**：内置体积/耗时统计（原版已有 `JvmProfiler.onRegionFileRead/Write` 钩子，可直接接 JFR 事件复用），让用户看到省了多少、快了多少。
3. **启动自检**：检测 C2ME / 其它 IO mod 并 fail-closed，避免静默存档损坏。

---

## 7. 下一步

1. **先做实测基线**（不写业务代码）：写一个离线分析工具，扫描一个真实存档的 `region/*.mca`，统计——平均每区块压缩后大小、扇区碎片率（分配扇区数 vs 实际占用）、调色板位宽分布、光照/heightmap 占比、各类型空容器占比。**没有这份数据，后面的优化都是拍脑袋。**
2. **A 方案 POC**：Mixin `RegionFileVersion.VERSION_DEFLATE` 的 `outputWrapper`，改为 `new DeflaterOutputStream(out, new Deflater(9))`，在同一存档上对比体积与保存耗时，验证"20–30%"这个区间。
3. **B 方案 POC**：`writeHeader()` 改为定点写，压测写吞吐。
4. 基于 1–3 的结果再决定是否上 D/E（换格式），以及要不要做双向转换工具。
5. 清理项目模板：`ChunkStorageOptimizer.java` 里的 `EXAMPLE_BLOCK` / `EXAMPLE_ITEM` / `EXAMPLE_TAB`、`Config.java` 里的 `LOG_DIRT_BLOCK` / `MAGIC_NUMBER` 都是 MDK 示例，应尽早移除，避免与真实配置混淆。

---

## 附：来源

- 26.1 移除混淆：https://zh.minecraft.wiki/w/混淆映射表
- Region 文件格式（含压缩 id 表）：https://minecraft.wiki/w/Region_file_format
- Chunk format（NBT 结构）：https://minecraft.wiki/w/Chunk_format
- linear 格式规范（xymb）：https://github.com/xymb-endcrystalme/LinearRegionFileFormatTools
- LinearReader 基准与兼容说明：https://modrinth.com/mod/linearreader
- XuanRikka/linear（RegionFileStorage 切入点）：https://github.com/XuanRikka/linear
- C2ME NeoForge 端口：https://github.com/RelativityMC/C2ME-neoforge
- NeoForge mod 文件与 `[[mixins]]`：https://docs.neoforged.net/docs/gettingstarted/modfiles/
- MCA Selector linear 支持 issue：https://github.com/Querz/mcaselector/issues/489
- 26.1 源码（本机）：`~/.gradle/caches/neoformruntime/intermediate_results/decompile_2586a26bc86465b9cb86a212df20b5ce5519d099_output.jar`
