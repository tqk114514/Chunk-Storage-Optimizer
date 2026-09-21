# Chunk Storage Optimizer

> ## ⚠️ Read this before you install
>
> **1. A world this mod has written to is no longer a vanilla-readable world.** Remove the mod and
> any progress stored only in `.cso` is invisible to the game; tools like MCA Selector cannot open
> it either. To go back, run `/cso convert mca prune` first. **Back your saves up.**
>
> **2. Major versions do not read each other's files, and backwards compatibility is not the
> goal.** The rule for this project: **any format change or incompatibility jumps the major
> version** (1.0.1 → 2.0.0). Within one major line (1.0.1 → 1.4.3 → 1.100.0) the format is fully
> compatible and you just drop in the new jar. So **2.x will not read a world written by 1.x** —
> the only way across is to convert back with the old version (`/cso convert mca`), swap the mod,
> then convert again. Check `CHANGELOG.md` before upgrading.

Replace Minecraft's Anvil region files with a custom bucket-based format compressed by zstd — **smaller saves, faster chunk loading and saving**.

Measured on a real 889 MB city world (201,321 chunks):

| | vs. vanilla Anvil |
|---|---|
| **Save size** | 889 MB → 396 MB (**55% smaller**) |
| **Write speed** | **8.1× faster** |
| **Read speed** | **3.1× faster** |

### Controlled A/B: same seed, identical chunks

Two vanilla-terrain worlds generated from the same seed, same render distance, standing still
until everything loaded:

| | Files | Chunks | Size | Avg. per chunk |
|---|---|---|---|---|
| Vanilla `.mca` | 16 | **8,281** | 54.44 MB | 6,893 B |
| This mod `.cso` | 16 | **8,281** | 36.48 MB | 4,619 B |

Both hold exactly the same 8,281 chunks, so the **33.0% difference comes purely from the
format** — no content differences mixed in. (Entities: −61.3%. POI: −20.9%.)

### How much you save depends on your chunks

| World | Avg. chunk (vanilla) | Saved |
|---|---|---|
| City world (dense builds) | 4.6 KB | 55.4% |
| Vanilla terrain (above) | 6.89 KB | 33.0% |
| "Epic terrain" style world | 9.15 KB | 29.6% |

The larger and more complex each chunk is, the less there is to gain — once a chunk is well
past 4 KiB, vanilla's padding overhead is already a minor share, so there is less slack for a
better codec to recover.

---

## How it works

Vanilla stores each chunk independently, padded to a 4 KiB sector. Sparse data pays a heavy
price: a 100-byte chunk still occupies 4 KiB.

This mod instead splits every 32×32 region into `grid × grid` **buckets**. Each bucket is
stored as a single zstd stream containing all of its chunks:

```
[FileHeader 128 B][BucketTable A][BucketTable B][compressed blocks]
```

- **No sector padding** — blocks are packed contiguously.
- **No free list** — free space is derived from the bucket table, so there is less metadata to corrupt.
- **One compression context per bucket** — chunks share context instead of compressing in isolation.
- `grid` is configurable: larger values mean smaller buckets and cheaper writes.

## Features

- **Smaller saves.** Up to 82% on sparse data (POI), 55% on dense city worlds.
- **Faster IO.** Fewer bytes on disk and fewer decompression calls per chunk served.
- **Crash safe.** Two copies of the bucket table (each entry carries a sequence number and its
  own CRC) plus a write-ahead log. The file always stays readable, and a completed batch is
  never lost.
- **Batched writes.** Multiple chunk changes in the same bucket cost one compression instead of many.
- **Gradual migration.** Chunks missing from the new format fall back to the original `.mca`,
  so enabling this on an existing world loses nothing.
- **Two-way conversion.** Convert in-game or offline, with optional deletion of the originals.

## Requirements

- Minecraft **26.1.2**
- NeoForge **26.1.2.71** or newer
- Java 25

## Installation

1. Install NeoForge 26.1.2.71+.
2. Drop the jar into your `mods/` folder.
3. Start the game; the config is generated at `config/chunkstorageoptimizer-common.toml`.

## Getting started

### New worlds

Just play. Everything is written to `.cso` files; no `.mca` is created.

### Existing worlds

Existing data keeps working — chunks not yet migrated are read from the original `.mca`.
New and modified chunks are written to `.cso`.

Note that old data stays in the `.mca` files, so **both copies occupy disk space** until you
migrate. To convert everything and drop the originals:

```
/cso convert cso prune
```

This converts every dimension and every store (`region`, `poi`, `entities`), verifies each
file by reading it back, and only then deletes the originals.

## Commands

Requires admin permission.

| Command | Description |
|---|---|
| `/cso stats` | Cumulative statistics: chunks read/written, compress and decompress time, cache hit rate, effective compression ratio |
| `/cso reset` | Reset statistics |
| `/cso compact` | Run space reclamation on all open region files |
| `/cso convert cso [prune]` | Convert `.mca` → `.cso`. `prune` deletes the originals after verification |
| `/cso convert mca [prune]` | Convert `.cso` → `.mca` and **disable this mod**. `prune` also deletes the `.cso` files |

Conversion runs `save-all flush` first, then closes all file handles before moving any bytes.

Two numbers in `/cso stats` are worth watching:

- **chunks per decompression** — how many chunk reads one bucket decompression serves. Higher
  means caching and batching are working.
- **ratio** — raw bytes in vs. bytes actually stored, i.e. your real compression ratio.

## Configuration

`config/chunkstorageoptimizer-common.toml`:

| Key | Default | Description |
|---|---|---|
| `enabled` | `true` | Master switch. When off, vanilla Anvil is used untouched |
| `grid` | `16` | Bucket grid edge (1/2/4/8/16/32). See below |
| `compression` | `zstd` | `zstd` or `none` |
| `zstdLevel` | `3` | 1–22. Higher compresses better and writes slower |
| `cachedBuckets` | `4` | Decompressed buckets kept in memory per region file |
| `verifyCrc` | `true` | Verify CRC32 on read. Turning it off removes corruption detection |
| `fallbackToMca` | `true` | Read missing chunks from `.mca`. **Do not disable** |
| `compactionMinBytes` | `4194304` | Minimum wasted bytes before reclaiming space |
| `compactionRatio` | `0.25` | Wasted/live ratio that triggers reclamation |
| `batchMaxChunks` | `16` | Number of pending chunk changes that triggers an early flush |
| `batchMaxDelayMs` | `5000` | Maximum time writes may stay staged |

> Upgrading does **not** overwrite an existing config file. If `grid` still shows an old value,
> edit it manually.

### Choosing `grid`

Measured on the 889 MB city world:

| grid | Chunks per bucket | Size saved | Write cost |
|---|---|---|---|
| 1 | 1024 | Best (59.3%) | Highest, ~15× that of grid 16 |
| 8 | 16 | 58.5% | ~2× that of grid 16 |
| **16** | **4** | **55.4%** | **Lowest** |
| 32 | 1 | Worse | Rises again (no context to share) |

- **Active worlds** → keep `16` (default).
- **Archives, rarely modified** → `8` or lower for a smaller file.
- **Sparse worlds where individual chunks are large** (e.g. a lightly explored Nether) → try `1` or `2`.

## Important notes

**1. Saves are no longer vanilla-readable.** After removing the mod, progress stored only in
`.cso` is invisible to vanilla, and tools like MCA Selector cannot read it. Run
`/cso convert mca prune` before uninstalling.

**2. Do not set `fallbackToMca` to `false`.** Chunks missing from the `.cso` would be treated as
ungenerated and **terrain would be silently regenerated**.

**3. Not compatible with C2ME.** If C2ME is detected the mod disables itself and falls back to
vanilla storage, logging the reason. Both rewrite chunk IO, and running them together would
split a world across two formats.

**4. Gains can be slightly negative for sparse, large chunks.** With chunks near or above 4 KiB,
vanilla's padding waste is already small while the bucket table adds fixed overhead — a Nether
sample measured −2%. Lowering `grid` to 1 or 2 fixes this.

**5. Automatic fallback if zstd is unavailable.** If the native library cannot load, the mod
falls back to vanilla Anvil instead of failing to start.

## Advanced: offline tools

A CLI can convert and benchmark saves without launching the game. It operates on raw NBT bytes
and never parses them. See the project README for full usage.

It also ships a read-only `count` command that reports how many chunks a directory holds. Use
it before comparing two saves by size — if the chunk counts differ, the size difference is not
measuring the format.

## Links

- Source & issues: https://github.com/tqk114514/Chunk-Storage-Optimizer
- Format specification: `docs/FORMAT.md` in the repository
