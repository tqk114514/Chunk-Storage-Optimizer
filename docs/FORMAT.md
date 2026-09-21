# CSO Region File Format v1

Chunk Storage Optimizer 的自有区块存储格式。**不与原版 Anvil 兼容。**

- 状态：草案 v1（实现前定稿）
- 版本标识：`CSOREG01`
- 字节序：**小端（little-endian）**，全部多字节整数均为无符号语义，按 Java 有符号 int/long 存储
- 适用目录：`region/`（区块）、`entities/`（实体）、`poi/`（POI）——三者共用同一套实现

---

## 1. 目标与非目标

**目标**

1. **体积**：消除 4 KiB 扇区对齐浪费；让多个区块共享同一个压缩上下文（这是原版做不到的）；整体压缩比显著优于 Anvil+zlib。
2. **写入粒度可控**：通过可配置的 bucket 网格，在"压缩率"和"写放大"之间给用户选择权。
3. **读放大可控**：一次读解压一个 bucket，同 bucket 的后续区块命中内存缓存。
4. **绝不静默丢数据**：格式不匹配、CRC 校验失败一律**抛异常**，不返回空。

**非目标**

- 与原版 Anvil **同时**读写同一份数据。一次性转换工具已经实现（游戏内 `/cso convert`、
  离线 `csoTool convert`），但它是搬运而非运行时兼容。
- 与 MCA Selector 等外部工具兼容（本格式下它们本就不可用）。

---

## 2. 文件定位与命名

| 项 | 值 |
|---|---|
| 路径 | 与原版同目录（`region/` / `entities/` / `poi/`） |
| 文件名 | `r.<regionX>.<regionZ>.cso` |
| 扩展名 | `.cso`（**刻意不同于 `.mca`**，避免任何原版/外部代码把它当 Anvil 解析） |

---

## 3. 总体布局

```
+--------------------------+  offset 0
| FileHeader       128 B   |
+--------------------------+
| BucketTable A  N × 32 B  |  N = grid × grid
+--------------------------+
| BucketTable B  N × 32 B  |  与 A 同构的第二份副本
+--------------------------+
| Data blocks (可变)       |  bucket 0..N-1 的 zstd 压缩块，顺序不限，可有空洞
+--------------------------+
|  （空闲空间）            |  由 bucket 变更产生，compaction 时回收
+--------------------------+
```

**文件里没有 free list。** 空闲空间由 `BucketTable` 里的活动区间反推得到——元数据越少，损坏后越容易恢复。

---

## 4. FileHeader（128 B）

| 偏移 | 大小 | 字段 | 说明 |
|---|---|---|---|
| 0 | 8 | `magic` | ASCII `CSOREG01`，第 8 字节 `\0` |
| 8 | 2 | `formatVersion` | `1` |
| 10 | 2 | `grid` | bucket 网格边长，取值 1/2/4/8/16/32，且必须能整除 32 |
| 12 | 1 | `compression` | `0`=none，`1`=zstd |
| 13 | 1 | `level` | 压缩等级（zstd 1..22） |
| 14 | 2 | `flags` | v1 **恒为 0**，读取时必须忽略（`bit0` 预留给 `dataCrcEnabled`） |
| 16 | 4 | `bucketCount` | = `grid * grid` |
| 20 | 4 | `headerCrc` | 对偏移 0..20（20 字节）计算的 CRC32 |
| 24 | 4 | `regionX` | 冗余记录，用于检测文件错位 |
| 28 | 4 | `regionZ` | 同上 |
| 32 | 4 | `chunksPerBucket` | = `(32 / grid)^2` |
| 36 | 4 | `lastCompactionWasted` | 上次 compaction 时的浪费字节数（诊断用） |
| 40 | 88 | `reserved` | 置 0，为后续版本预留 |

`grid` 决定 bucket 划分：region 内 chunk 局部坐标 `(lx, lz)`，各 ∈ [0,32)，
`bucket = (lz / (32/grid)) * grid + (lx / (32/grid))`。

---

## 5. BucketTable（2 份 × N × 32 B）

bucket 表存**两份同构副本**。表 `t` 中第 `i` 项的偏移 = `128 + (t * N + i) * 32`。

| 偏移 | 大小 | 字段 | 说明 |
|---|---|---|---|
| 0 | 4 | `entryCrc` | CRC32，覆盖本项的 4..31 字节（自检） |
| 4 | 8 | `offset` | 压缩块在文件中的字节偏移；`0` 表示该 bucket 尚未写入 |
| 12 | 4 | `compressedLength` | 压缩后字节数 |
| 16 | 4 | `rawLength` | 解压后字节数（用于预分配缓冲区） |
| 20 | 4 | `crc32` | 对**解压后**数据计算的 CRC32 |
| 24 | 4 | `chunkCount` | 该 bucket 内实际存在的区块数（`0` 表示空 bucket） |
| 28 | 4 | `sequence` | 单调递增的写入序号 |

**为什么是 32 字节**：32 整除 512（磁盘扇区），单项永不会跨扇区撕裂。多出的 8 字节换来
「序号 + 自检 CRC」，这是崩溃恢复得以成立的前提。

**为什么两份**：写入总是落进「当前不是最新」的那一份，写完再翻转 `lastTable`。因此任一时刻
至少有一份完整可校验。恢复时逐 bucket 比较两份，取自检通过且 `sequence` 更大者。

### 崩溃语义

写入顺序被严格固定为 **数据块 → 表项**：

- 崩在写表项之前 → 两份表都还指向旧块，旧块**完好**（见 §8：不复用旧空间）→ 读到上一版本
- 崩在写表项中途 → 该份表项自检失败 → 回退到另一份 → 读到上一版本
- 写表项完成 → 读到新版本

结论：**双表保证文件始终可解析**，已落盘的最后一批由 §13 的 WAL 保证不丢。仍在内存暂存队列
里的区块（最多 `batchMaxChunks` 个或 `batchMaxDelayMs` 毫秒）崩溃即丢——这是刻意的选择，
宁可丢几秒进度，也不要一个打不开的世界。

---

## 6. Bucket payload（解压后）

```
+-------------------------------+
| ChunkEntry × K                |  K = chunksPerBucket = (32/grid)^2
+-------------------------------+
| Chunk data (顺序拼接)          |  每个区块的原版 NBT 序列化字节（未压缩）
+-------------------------------+
```

### ChunkEntry（12 B × K）

| 偏移 | 大小 | 字段 | 说明 |
|---|---|---|---|
| 0 | 4 | `offset` | 区块数据在 payload 内的偏移（相对 payload 起点）；`length=0` 时无意义 |
| 4 | 4 | `length` | 区块 NBT 字节数；**`0` = 该位置无区块** |
| 8 | 4 | `timestamp` | Unix 秒；`length=0` 时为 0 |

区块在 bucket 内的序号：`idx = (lz % (32/grid)) * (32/grid) + (lx % (32/grid))`。

**不变式**：payload 内区块数据始终**紧凑排列、无空洞**。因为每次修改 bucket 都是"解压 → 修改 → 重排 → 重压"的整块重写。因此 bucket 内部**永不产生碎片**，碎片只存在于文件层级。

---

## 7. 读写流程

### 读一个区块

1. 由 `(lx, lz)` 算 bucket 序号 `b`。
2. 查 bucket 缓存（LRU，按 `(regionFile, b)` 键）。命中则跳到 5。
3. 读 `BucketTable[b]`：若 `offset == 0` 或 `chunkCount == 0` → 区块不存在。
4. 从文件读 `compressedLength` 字节 → zstd 解压 → 校验 `rawLength` 与 CRC32 → 存入缓存。
5. 读 `ChunkEntry[idx]`：`length == 0` → 区块不存在。
6. 切出 `[offset, offset+length)` 字节 → `NbtIo.read`。

### 写一个区块

1. 序列化 NBT → 字节数组。
2. 算 bucket 序号 `b`，取 bucket（命中缓存则直接用，否则读+解压）。
3. 替换 `ChunkEntry[idx]` 与数据区，**重排为紧凑布局**，更新 `chunkCount`。
4. zstd 压缩整块，算 CRC32。
5. 分配文件空间（见 §8），写入，更新 `BucketTable[b]` 的 24 B（定点写）。
6. 更新缓存。

### 删除一个区块

同写流程，只是把 `ChunkEntry[idx].length` 置 0 并移除数据。`chunkCount == 0` 时保留空 bucket（不回收，避免频繁分配）。

---

## 8. 空间分配与 compaction

### 分配

1. 由 `BucketTable` 收集所有活动区间 `[offset, offset + compressedLength)`，加上 header 与两份 bucket 表占据的 `[0, dataStart)`。
2. 排序求补集 → 空闲区间列表。
3. **best-fit**：选能容纳新块的最小空闲区间。
4. 无合适区间 → 追加到文件末尾。

**正在被重写的 bucket，其旧区间仍算「已占用」，不会被复用。** 复用它会更快回收空间，但会
在「写完新块、还没改表」这个窗口里毁掉唯一一份完好的旧数据。回收交给 compaction。

每次分配是 `O(N log N)`，N = `bucketCount`（grid=4 时 N=16，可忽略；grid=32 时 N=1024，约 10 μs 级，仍可接受）。

### compaction

触发条件（任一满足）：

- `wasted > max(4 MiB, used * 0.25)`
- 手动命令触发

过程：

1. 新建临时文件 `<name>.cso.tmp`。
2. 写出 header 与占位 bucket 表。
3. 按 bucket 顺序（保持顺序以提升读取局部性）依次读入已有压缩块（**不解压**，直接搬字节），写入新文件，记录新 offset。
4. 原子替换原文件（`Files.move` + `REPLACE_EXISTING`）。
5. 原地更新内存中的 bucket 表与缓存。

**搬字节而非重压缩**是刻意选择：compaction 本身不应带来 CPU 尖峰。

---

## 9. 缓存

| 缓存 | 内容 | 默认容量 |
|---|---|---|
| bucket 解压缓存 | 解压后的 bucket payload | LRU 4 个/region 文件（可配） |
| region 文件句柄 | `CsoRegionFile` 实例 | LRU 256（对齐原版 `RegionFileStorage`） |

bucket 解压后可能达数 MB，缓存容量必须设上限并在配置中可调。缓存未命中时的写操作会产生一次"读+解压"的读放大——这是分桶方案的主要代价。

---

## 10. 迁移与回退

启用 mod 后：

- **读**：先查内存暂存队列，再查 `.cso`；若该 region 无 `.cso` 文件或其中无该区块，
  **回退读同名 `.mca`**（原版解析器）。
- **写**：始终写 `.cso`。
- **删除**：只清 `.cso` 中的条目，不动 `.mca`。被删除的区块若在同名 `.mca` 里还留着旧字节，
  读回退会让它重新出现——所以一旦开始用本 mod，建议尽快跑一次 `/cso convert cso prune`。

效果：已探索区域的数据不会丢（仍从 `.mca` 读），新产生的写入走新格式。不做任何操作即可完成渐进迁移；要立刻收尾用 `/cso convert cso prune`。

⚠️ **卸载语义**：卸载 mod 后，`.cso` 里的新增进度对原版不可见。这是激进路线的固有代价，必须在 README 与首次启动日志中明示。

---

## 11. 失败处理原则

以下情况**一律抛异常并中止**，绝不返回空、绝不静默重建：

- magic 不匹配、formatVersion 不支持
- header CRC 校验失败
- header 记录的 `regionX`/`regionZ` 与文件名不符（文件被改名或复制错位）
- bucket CRC 或 `rawLength` 校验失败
- `grid` 非法（非 2 的幂、不能整除 32）
- 解析出的 offset/length 越界

理由：区块存储层的静默失败 = 地形被重新生成 = 不可逆的存档损坏。**响亮地失败比安静地出错好。**

**唯一的例外是启动阶段的环境不可用**：检测到 C2ME、或 zstd native 库加载失败、或存储层初始化抛异常时，
本 mod 通过 `CsoRuntime.disable()` 停用自有格式、整个会话退回原版 Anvil，并在日志里说明原因。
这些情况都不涉及已写入的数据，退回是安全的；而 C2ME 之所以要拦，是它的 `ioSystem.replaceImpl`
会绕过本 mod 直接读写 `.mca`，与本 mod 写出的 `.cso` 混用造成存档割裂。要同时使用两者，需自行把
`config/c2me.toml` 的 `ioSystem.replaceImpl` 设为 `false` 并承担风险。

---

## 12. 配置项（NeoForge COMMON config）

键名与默认值以 `Config.java` 为准：

| 键 | 默认 | 说明 |
|---|---|---|
| `enabled` | `true` | 总开关 |
| `grid` | `16` | bucket 网格边长，1–32 的 2 的幂（非法值向下取整）。只对新建文件生效，已有文件保持自己的 grid |
| `compression` | `zstd` | `zstd` / `none` |
| `zstdLevel` | `3` | 1..22，热写入路径等级 |
| `cachedBuckets` | `4` | 每个 region 文件缓存的解压 bucket 数（0..64）。越大 = 读放大越低、堆占用越高 |
| `verifyCrc` | `true` | 读取时校验 CRC32 |
| `fallbackToMca` | `true` | `.cso` 中无该区块时回退读同名 `.mca` |
| `compactionMinBytes` | `4194304` | 触发 compaction 的最小浪费字节数 |
| `compactionRatio` | `0.25` | 触发 compaction 的浪费/存活比 |
| `batchMaxChunks` | `16` | 攒够多少个区块变更后提前落盘，`1` 关闭批处理 |
| `batchMaxDelayMs` | `5000` | 暂存写入的最长停留时间，超时即落盘 |

> 已存在的配置文件**不会**被新默认值覆盖，升级 mod 后需手动改。

---

## 13. WAL（预写日志）

文件：`r.X.Z.cso.wal`，与主文件同目录，**仅在一次批处理进行期间存在**。

```
magic "CSOWAL\0"    7 B
formatVersion       u16
bucketCount         u32
per bucket:
    bucket          u32
    entryCount      u32
    per entry:
        slot        u32
        len         u32      -1 表示删除该 slot
        data        len 字节
crc32               u32      覆盖前面所有字节（小端）
```

### 三阶段写入

1. 写 WAL 并 force
2. 应用全部变更（每个 bucket 一次压缩 + 一次写）
3. force 数据，**然后**删除 WAL

第 3 步的顺序不能颠倒：先删 WAL 再 force 的话，日志已消失而数据可能还在页缓存里，
崩溃后就两头都没有了。

### 恢复

重开文件时：WAL 存在且 CRC 有效 → 重放（幂等，重放多次结果相同）；CRC 无效（撕裂）
→ 丢弃，回落到 bucket 表描述的最后一个一致状态。

### 为什么粒度是「一批」而不是单个 bucket

每次强制刷盘都有代价。按 bucket 记 WAL 会让**每次区块保存**都要强制刷盘，把这套格式
赖以存在的速度优势全部赔掉。按批处理则每批只多一次刷盘。

实测（32 区块/批，889 MB 城市存档）：每批 6 ms → 8 ms，**慢约 22%**。

## 14. 已知限制

- **写放大**：写 1 个区块需要重写整个 bucket。实测（同一 region 文件每轮重写 64 个区块、共 6 轮）
  落盘量 grid=16 为 0.95 MB、grid=8 为 1.73 MB、grid=1 为 14.46 MB，所以默认值取写放最低的 16。
  再配合 `CsoStorage` 的按 bucket 批处理，同一 bucket 一批只压缩一次。
- **崩溃一致性**：双表 + 表项自检 + 不复用旧空间保证文件**始终可解析**，§13 的 WAL 保证
  已提交落盘的批次不丢。仍会丢的是**尚未 flush 的内存暂存队列**——上限为 `batchMaxChunks`
  个区块或 `batchMaxDelayMs` 毫秒。要把这一层也保住就得每次区块保存都强制刷盘，代价见 §13。
- **稀疏 region**：`grid=1` 时单个 bucket 达 1024 个区块，重写代价极高，只适合冷存档/归档场景。
- 未实现：后台重压（冷 bucket 用高等级重压）、zstd 字典训练。
