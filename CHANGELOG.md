# Changelog

## [1.0.9] - 2026-10-03

### Added
- **NeoForge builds for Minecraft 1.21.2, 1.21.6, 1.21.7 and 1.21.9.** Those four never received a
  stable NeoForge release — the loader closes a version line as soon as the next Minecraft is out, and
  each of these lived about a week, so its line ends on a beta. The mod now builds against the last
  beta of each line, which is what a player on those versions has available anyway.
- **NeoForge builds for Minecraft 26.1, 26.1.1 and 26.3.** 26.3 moved to FancyModLoader 12, which
  renamed the config type this mod registers under; both names are handled now, and the config file
  keeps its existing name and location, so an existing config keeps being read.

### Fixed
- **Changing `compression` in the config could make existing worlds unreadable.** The codec came from
  the config rather than from each file's own header, so a world written with zstd was decoded as raw
  bytes after the setting changed. That reads as corruption, and the game regenerates the chunks it
  cannot read. The header wins now, exactly as the bucket grid already did.
- **A failed compaction could aim the next write at the only intact copy of the bucket table.** The
  table pointer was moved before the swap had committed, so a crash in that window left no redundancy
  at all and one torn entry was enough to lose the file. It moves only after the swap succeeds now.
- **A rejected region file leaked a file descriptor**, one per attempt — and on Windows also held a
  lock on the file.
- **A corrupt chunk index could crash the write path** with an unchecked array-index error instead of
  failing as corruption. The same for an out-of-range entry in a write-ahead log.
- **`/cso convert` could silently drop chunks** when merging into an existing `.mca` that held slots it
  could not decode. It skips such a file and says so now, matching the offline converter.
- **`/cso convert <target>` with a mistyped second word ran without pruning**, leaving the original
  files behind while the player believed they were gone. An unrecognised word is an error now.
- **Short writes while writing `.mca` files** are retried rather than silently truncating a block.
- **The read path no longer creates an empty region file** for every region it merely reads — about
  16 KB of litter per region, and a hard failure on read-only storage.
- **The compaction threshold no longer counts the free space at the end of the file**, which the
  allocator reuses anyway. It was making compaction trigger far more often than needed.

### Note
Fabric builds are unchanged: still the whole 1.21 line, still nothing for 26.x. That gap is a build
toolchain one, not a missing Fabric release — the mod will gain those builds when loom can target a
Minecraft that ships without mappings.

## [1.0.8] - 2026-10-03

### Fixed
- **A damaged chunk in the old `.mca` could stop the world from saving at all.** Reading a chunk the
  legacy file cannot serve throws rather than coming back empty: vanilla's reader answers null only
  for the damage it can see in the header, and decompression is lazy, so a corrupt stream fails at
  the moment the bytes are actually read. Nothing caught that, and a batch that throws stays staged
  and is retried on a timer — so a single unreadable chunk would have kept the same batch failing
  every half second, indefinitely. The read is now caught where it happens and the chunk left out of
  the seed, so the rest of the batch still reaches disk.
- **A passing disk hiccup could quietly regenerate a chunk.** That catch cannot tell a damaged chunk
  from a disk that failed for a moment — the exception types are identical — and a chunk left out of
  the seed leaves its slot empty in a bucket that then becomes authoritative, after which the game
  reads the chunk as absent and generates fresh terrain over it. The read is now retried before
  anything is given up on, and the warning reports what was seen rather than asserting that the file
  is at fault.

## [1.0.7] - 2026-10-03

### Note
**This is the first release since 1.0.4.** 1.0.5 and 1.0.6 were tagged but never published: each
carried a defect that could destroy a world part-way through migration, so both are superseded here
and their notes are kept below for the record. Everything they fixed arrives in this jar, so an
upgrade straight from 1.0.4 gets all of it:

- **1.0.5** — `/cso convert cso prune` could delete a `.mca` while silently leaving some of its
  chunks behind; a chunk deleted from a world could come back; a write batch ignored its own
  timeout; a bucket whose two table copies were both unreadable was treated as empty; a failed
  compaction left the region file unusable until restart; `/cso compact` ran against storages the
  game was still writing to.
- **1.0.6** — progressive migration could overwrite the chunks it had not migrated yet; an `.mca`
  holding only external `.mcc` chunks could still be deleted by `--prune`; a crash during a bucket's
  first write made the region file refuse to open.

### Changed
- **The `.mca` reader now proves it accounted for every slot the header names**, and reports that
  count on `ReadResult` beside the chunks it read and the ones it could not. The three always add
  up — every path past the "unwritten slot" test either decodes a chunk or counts it unreadable —
  but the check guards the failure that started this whole thread: a slot dropped without being
  counted, which reads as a smaller world rather than as an error. Taking the count during the pass
  the reader already makes over the header also retired a second file open that only the tests used.

## [1.0.6] - 2026-10-03 — unreleased

> **Never published.** Superseded by 1.0.7; see its notes for why, and for a summary of these.

### Fixed
- **Progressive migration could overwrite the chunks it had not migrated yet.** A bucket's first
  write recorded only the chunks of that one save, and the read path treats every written bucket as
  authoritative — so the bucket-mates still living in the `.mca` read back as absent, which the game
  answers by regenerating terrain over them. A bucket is now seeded from the `.mca` before its first
  write, with the save's own changes winning (a deletion included, so nothing comes back), and the
  seed goes into the write-ahead log with the rest of the batch. Only worlds being migrated by the
  mod's own read-through were affected; a world converted up front with `/cso convert cso` was not.
- **An `.mca` whose only content was external `.mcc` chunks could still be deleted by `--prune`.**
  Vanilla writes an oversized chunk's stub as a length of 1 with no payload, which is the same
  length an empty slot has. The reader judged the length before the external flag, so a real chunk
  looked like "nothing here": it was not counted as a loss and did not stop the file from being
  deleted. The flag is now read first, as vanilla's own reader does, and an allocated slot with no
  payload at all counts as damage rather than an absence.
- **A crash during a bucket's first write made the region file refuse to open.** Writes alternate
  between the two table copies and start at table 1, so table 0 is still blank until a first write
  has completed — a shape the old reader reported as damage, on the assumption that the two copies
  are only ever blank together, which is not true. A blank table 0 now reads as "no write ever
  finished", which is safe to act on while the write-ahead log is still on disk to supply the
  content; with no log the damage stays fatal, because nothing would then explain it.

## [1.0.5] - 2026-10-02 — unreleased

> **Never published.** Superseded by 1.0.7; see its notes for why, and for a summary of these.

### Fixed
- **`/cso convert cso prune` could delete a `.mca` while silently leaving some of its chunks behind.**
  A header slot pointing at an external `.mcc`, an unknown compression id, or a stream that fails to
  inflate is still a chunk, but the reader counted only the slots it could decode. Conversion then
  compared "wrote 3, read back 3" and deleted the file — the only copy of the other two. Any `.mca`
  holding such a slot is now skipped outright: neither converted nor deleted, and the count is
  reported. An `.mca` whose every chunk was undecodable used to be deleted by the empty-file branch,
  which is the same loss in its starkest form.
- **Chunks deleted from a world could come back.** Vanilla clears an emptied chunk by writing `null`,
  which lands as a deletion in the `.cso` file — but a read that found nothing there fell back to the
  old `.mca` and answered with the pre-deletion data. Emptied entity chunks were the usual source.
  The fallback now applies only when the chunk's whole bucket has never been written; once a bucket
  holds anything, its empty slots are deletions and are served as such.
- **The last batch of chunk writes did not respect its own timeout.** `batchMaxDelayMs` was only
  checked when the next chunk write arrived, so a server that went quiet — a quiet autosave does not
  flush the region storage — kept that batch in memory until shutdown. A background timer now imposes
  the delay on its own.
- **A bucket whose two table copies were both unreadable was treated as an empty bucket**, dropping
  its chunks without a word. It now fails loudly, which is the whole point of the double table: only
  an all-zero entry (a bucket nobody wrote) is skipped quietly.
- **A compaction that could not replace its file left the region file unusable until restart.** The
  rewrite closes the live handle before swapping the file in, so a failed swap left a closed channel
  behind every later write. The handle is now reopened before the failure propagates.
- **`/cso compact` ran on the server thread against storages the game was still writing to.**
  A storage's state is now guarded by one lock shared with the chunk IO thread.

## [1.0.4] - 2026-09-28

### Added
- **Fabric, for the whole Minecraft 1.21 line** — 1.21 through 1.21.11, twelve versions, including
  1.21.2 / 1.21.6 / 1.21.7 / 1.21.9 that never had a stable NeoForge release. Fabric needs Fabric
  API. The options are reachable through Mod Menu's Config screen, in the same three Chinese
  translations and English as NeoForge; Mod Menu is optional, and without it the config stays the
  plain file it is.
- **A loader token in every file name**: `chunkstorageoptimizer-neoforge-1.21.11-1.0.4.jar`,
  `chunkstorageoptimizer-fabric-1.21.11-1.0.4.jar`. On Fabric the settings live in
  `config/chunkstorageoptimizer.properties`, whose keys are identical to the NeoForge toml's.

### Changed
- **`/cso convert cso` refuses to run on a world the mod is not serving.** Region files that session
  already has open would keep being written to `.mca` behind the freshly converted `.cso` ones.
  Delete `cso.disabled`, re-enter the world, then convert.

### Fixed
- **Converting a world back to vanilla storage now sticks.** It was a decision for the running game
  only: the next launch took the world back over and started writing `.cso` files alongside the ones
  just converted, which is the split that command exists to prevent. The choice now travels with the
  save as `cso.disabled`, next to its `level.dat`, and it applies to that world alone — the `enabled`
  config key and every other save are left as they are. Delete the file and re-enter the world to
  switch back.
- **No more half-converted worlds inside one session.** Chunks saved after `/cso convert mca` used to
  go on landing in new `.cso` files until the game restarted; they now go to `.mca`, which is what
  the conversion promised.
- **Converting onto a file that already existed could leave part of the old file behind.** In a
  half-migrated world, where one region lives in both formats, the result either kept stale bytes
  readable as extra chunks or lost the chunks only one of the two files had. The two are now merged
  before anything is deleted — the `.cso` copy winning, exactly as the game reads them. The offline
  converter had the same problem.
- **No particular Fabric Loader version is required.** The jar used to ask for the one it was built
  with (0.19.5), which rejected setups that run it fine — it was measured working on 0.15.11. The
  only hard requirement is Fabric API for the same Minecraft, and that carries its own loader floor.
- **`/cso stats` says which world is off, and why**: `this world opted out (…\cso.disabled)`,
  `disabled by config`, or the conflict it refused to run beside.

## [1.0.3] - 2026-09-27

### Added
- **Minecraft 1.21 through 1.21.11.** Eight more game versions are supported next to 26.1.2 and
  26.2.0: 1.21, 1.21.1, 1.21.3, 1.21.4, 1.21.5, 1.21.8, 1.21.10 and 1.21.11. Nothing about the
  on-disk format changed, so a world is not touched differently than it was in 1.0.2.
  (Minecraft 26.1, 1.21.2, 1.21.6, 1.21.7 and 1.21.9 have never had a stable NeoForge release, so
  there is no jar to install on them.)
- **Author and licence in the Mods screen.** The mod entry now names `tqk114514`, and the licence is
  reported as MIT.

### Changed
- **One jar per Minecraft version, named after it.** The file is now
  `chunkstorageoptimizer-<minecraft>-<version>.jar`, because a single release produces ten of them
  and they were indistinguishable once downloaded. Select the file matching the game version; the
  loader
  requirement is the first stable NeoForge build for that Minecraft.

## [1.0.2] - 2026-09-22

### Added
- **`/cso report [files]`** samples your own world and prints what the same chunks would weigh at
  bucket grids 1 / 8 / 16 / 32, so choosing `grid` stops being guesswork. It works on a world that
  has already been converted, runs in the background, and posts the table when it finishes.
- **`/cso stats` now shows latency, not just totals** — p50 / p95 / max for batch writes,
  compression and decompression, plus one line per store so you can see which dimension is doing
  what. Totals could say the mod was fast on average; these tell you whether a single save spiked.

### Changed
- **Reclaiming space no longer happens in the middle of saving chunks.** It used to trigger the
  moment a bucket landed, so saving one chunk could suddenly mean rewriting the whole region file.
  It now runs at flush time, at most one file per flush, starting with the coldest.
- **Autosave no longer re-syncs files it did not touch.** Every open region file used to be forced
  to disk on each flush, whether or not it had changed. On a world with a couple of hundred region
  files open that is roughly a 127 ms stall, now about 3 ms on the machine we measured.
- **A little faster chunk loading.** Opening a region file now reads its header once instead of
  twice through two separate handles — about 10% off the read benchmark on a real city save.

## [1.0.1] - 2026-09-22

### Added
- **Chinese translations for the in-game config screen** (Mods → Chunk Storage Optimizer →
  Config): Simplified, Traditional (TW) and Traditional (HK). Both the option names and the
  descriptions shown when you hover over them are translated.
- **A logo and a real description** for the Mods screen entry, which previously carried the
  modding-template placeholder text.

### Fixed
- **The `compression` option accepted any text.** Typing anything else saved without complaint and
  silently behaved as `zstd`. It is now restricted to `zstd` and `none`.
- **A misplaced region file is rejected instead of silently misread.** If a `.cso` file is renamed
  or copied so that its name no longer matches the region it holds, the game now refuses to open it
  and says why. Previously its chunks answered for the wrong coordinates, which looks like a
  corrupted world but is very hard to notice.
- **The offline tool's default bucket grid now matches the mod's** (16). Running `bench` or `ab`
  without `--grid` measured grid 8, so those numbers did not describe what an active world writes.
- **The client no longer writes the player name to the log at startup.**

### Changed
- **The generated config file no longer carries `#` comments.** The option descriptions moved into
  the language files so the in-game config screen can show them in your language — a code comment
  has to pick exactly one. Read what an option does in **Mods → Config** (hover an entry for the
  full text) or in the README table. **If you edit `chunkstorageoptimizer-common.toml` by hand,
  the hints beside each key are gone**; the key names, defaults and allowed values are unchanged.

## [1.0.0] - 2026-09-20

### What's New
- **Initial release.** Chunk Storage Optimizer replaces Minecraft's Anvil `.mca` region storage with
  a compact custom format: neighbouring chunks are compressed together and the 4 KiB sector padding
  is gone, so the same terrain takes a fraction of the disk and reads back faster.
- **Smaller saves**: 55% smaller on an 889 MB city save (201,321 chunks); 33% smaller on a
  same-seed vanilla comparison where both saves hold exactly the same chunks. Sparse data wins the
  most — POI 82%, entities 69%.
- **Faster saves**: writes 8.1x faster, reads 3.1x faster on the same city save.
- **One knob to choose**: `grid` trades compression ratio against write cost. The default 16 is best
  for a world you keep playing in; 8 or lower suits an archive you rarely touch.
- **Safe to enable on an existing world**: any chunk not yet in `.cso` is still read from the old
  `.mca`, so nothing already saved is lost. `/cso convert cso prune` finishes the migration in place
  and removes the leftovers once the result has been verified.
- **Crash protection**: a crash loses at most the writes still queued in memory — bounded by
  `batchMaxChunks` / `batchMaxDelayMs` — and never leaves a file the game cannot open.
- **Admin commands**: `/cso stats`, `/cso reset`, `/cso compact`,
  `/cso convert cso|mca [prune]`.
- **Needs Minecraft 26.1.2 with NeoForge 26.1.2.71 or newer, on Java 25.** The mod stands down
  automatically when C2ME is installed, and falls back to vanilla storage if zstd cannot load.
