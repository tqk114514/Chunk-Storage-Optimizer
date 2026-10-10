# Changelog

How entries are written (this block never reaches Modrinth — the publish script extracts
only from a `## [version]` heading down, and a missing or empty section fails the release):

- The reader is a player deciding whether to update, not a reviewer of the code. Each entry
  says what changed and what it means for them; why a bug existed, how it was hunted down and
  what the code looked like before belong in the commit message. A handful of lines per
  release, not a page.
- Sections stay `### Added`, `### Changed`, `### Fixed`, `### Note`.
- Every release carries a `### Note` line about upgrading itself — normally "The file format
  is unchanged — a drop-in upgrade for any 1.x world." — plus any operational warning the
  player needs before running the new build (1.1.4's max-tick-time note is the example).

## [1.1.6] - 2026-10-10

### Fixed
- **Converting back to `.cso` after an opt-out crashed with `ClassCastException: Optional
  cannot be cast to RegionFile`.** Two faults met in that one crash. The visible one: the
  vanilla side's region cache changed shape between the versions this mod builds for — 1.21
  stores a bare `RegionFile`, 26.x wraps each entry in an `Optional` so a failed open is
  memoized — and the pause handler walked it with a bare cast that survived every compile
  and crashed on the first real entry. The cache walk now recognises both shapes, so a
  version that wraps its handles differently cannot turn a conversion into a crash.
- **The deeper one: storages released by an opt-out never left the registry.** The design
  said the world unload removes them; the code nulled the reference the close handler needed
  first, so every released storage of every previous session stayed in a process-wide
  registry for as long as the game ran. A singleplayer client that re-enters a world carries
  them all into the next command — and a released storage's vanilla cache is exactly the
  populated one the cast then walked. Released storages are now taken out of the registry
  when their world unloads, as the design always intended.
- **The second `/cso convert cso` in the same session no longer slips past the opt-out
  guard.** The guidance branch clears the marker — but the running session keeps its
  released storages (or, when it attached during the opt-out, no storages at all), so a
  conversion started right there would build `.cso` beside the session's own open `.mca`
  writers. That was also the crash path of 1.1.5, reachable by following the guidance
  message literally. Both shapes are now refused with the re-enter instruction until the
  world is actually re-entered.
- **The Xaero and Voxy compat mods shipped without an icon**, which the mod list reported
  as a broken icon on every launch. All three compat jars now carry the same icon as the
  main mod, through the same FML 11/12 field-name seam the main jar uses.

## [1.1.5] - 2026-10-10

### Fixed
- **A conversion that hit a failing file kept converting in the background.** The failure
  path asked the worker pool to stop but never waited for it — the workers do not check the
  interrupt flag — so the command reported failure, the world un-paused, and the workers kept
  writing region files beside the live game. Every later symptom in that player's session
  traced back to this: refused renames where the game had just re-opened the files, a chunk
  save failing on the write-ahead log, and a read racing a replacement. The failure path now
  drains the pool before anything returns (60 s bound): the world is never un-paused next to
  a writer the save does not know about.
- **One region file held open by another program no longer aborts the whole conversion.**
  The world map reading its tiles, a backup tool, a scanner — a refused rename on one file
  is now a skip with a count in the summary, not the end of the run: the file's source is
  untouched and the union read serves both formats, so closing the holder and running the
  command again converges on just those files. Renames also retry longer now (10 attempts,
  backoff up to 500 ms), and the write-ahead log's clear — which runs on the live save path —
  retries the same way instead of failing a chunk store over one refused delete.
- **The switch to vanilla storage is no longer recorded while any region was skipped.** The
  `cso.disabled` marker hands the world to vanilla, which reads only `.mca` — writing it
  with a skipped region still living on its `.cso` side would have hidden that data from the
  game. A run with skipped regions now stays on the union this mod serves and the summary
  says what to resolve; the marker lands only once every file has made it.
- **A file that could not be opened at all no longer stops a conversion's start** — same
  refusal, same skip-and-report treatment as above.

### Note
- The file format is unchanged — a drop-in upgrade for any 1.x world.

## [1.1.4] - 2026-10-10

### Added
- **A progress bar for `/cso convert`, in the vanilla style Chunky and Voxy use.** A blue
  boss bar at the top of the screen — no darkened sky, no boss music — counts region files
  as they land: "CSO: converting to .cso — 12/34 files (35%)". Skipped files move the bar
  too, the total comes from a listing taken right before the work starts, and the bar
  disappears whether the run finished, failed, or anything in between. Every player who
  could have run the conversion themselves sees it — the same permission gate the command
  requires — not just whoever started it; console-run conversions broadcast the same way.
- **Switching back after `/cso convert mca` is now two commands and zero file browsing.**
  Running `/cso convert cso` on an opted-out world clears the `cso.disabled` marker itself
  and says exactly what to do next: leave and re-enter the world, then run it again. The
  refusal used to point at the file and leave deleting it to the player.

### Changed
- **Conversions convert their region files in parallel: measured 231.6 s → 60.6 s (3.8×) on
  a real 8.11 GB world — 2,061 region files, 1.43 M chunks — with the converted content
  byte-identical to the sequential run.** The server thread still freezes for the duration
  (that freeze is what guarantees nothing writes the region folders mid-conversion) and
  every safety rule is unchanged: each file still lands atomically, the first failure still
  leaves what already landed converted and the rest untouched, and force-closing at any
  moment still loses nothing. The work list is also taken after the game's own save is
  flushed now, so region files a save creates are no longer missed by the conversion.

### Fixed
- **A conversion could die on one file with `AccessDeniedException` on Windows**: the
  freshly written temp file is briefly held by a real-time scanner (Defender, the search
  indexer) at the moment of the atomic rename, and the rename loses the race. All four
  settle-moves in the format — both region writers, the write-ahead log, compaction — now
  retry that one exception with backoff; everything else still fails exactly as it says.
- **A damaged write-ahead log now refuses the open loudly and is kept for inspection,
  instead of being silently discarded.** The log is written through a temp name and an
  atomic rename, so a WAL that exists was written whole — a bad checksum on one can only
  mean damage after the force, and the batch it describes may sit half-applied. Logs from
  the pre-atomic era, where a torn write was the ordinary crash outcome, are still discarded
  quietly, and a WAL that cannot be opened at all (a backup tool holding it, on Windows) now
  fails loudly rather than being dropped — the same rule a locked `.mca` already follows.

### Note
- **On dedicated servers, the vanilla watchdog (`max-tick-time`, 60 s by default) force-crashes
  a frozen main thread** — raise it or set `-1` before converting a large world. Even if it
  fires, the save is intact and the command simply runs again; singleplayer has no such
  watchdog.
- The file format is unchanged — a drop-in upgrade for any 1.x world.

## [1.1.3] - 2026-10-08

### Added
- **Xaero's World Map compatibility, bundled as a nested mod inside every jar ("CSO Xaero
  Compat") — Fabric and NeoForge both.** The world map keeps recording the areas you have
  explored, in a world this mod stores. The bug it fixes: in singleplayer the map lists the
  save's region folder for `r.X.Z.mca` files, a Chunk Storage Optimizer world holds its regions
  as `r.X.Z.cso`, so the scan found nothing — every session started with an empty map and
  everything recorded before came back as black fog. The compat widens that one scan to also
  accept `.cso` (the tile rebuild already worked, because it reads chunks through the ordinary
  storage path), verified in-game on both loaders. Without the map installed the compat is a
  quiet child entry and nothing else; a map update that moves a target disables the `.cso`
  support with one log line instead of crashing. The compat keeps its own version number
  (1.0.0), so it only moves when it changes.

### Changed
- **The compat mods now declare the requirements of the Minecraft they ship for, written at
  build time like the main mod's own metadata.** Previously they accepted any Minecraft and
  Java 21+; each nested jar now carries exactly the version ranges of the jar it travels in,
  so a nested entry can never claim a row its container does not.

### Fixed
- **A "uses the deprecated logoFile property" warning on NeoForge 26.3.** FancyModLoader 12
  renamed the Mods-screen icon field; the jar now writes the new `iconFile` field there while
  older NeoForge keeps reading `logoFile`, so the warning screen that stood in front of every
  launch is gone.

### Note
- The file format is unchanged — a drop-in upgrade for any 1.x world.

## [1.1.2] - 2026-10-08

### Added
- **Fabric builds for Minecraft 26.1, 26.1.1, 26.1.2, 26.2 and 26.3.** The Fabric side now covers
  every Minecraft this mod ships for, same as NeoForge. 26.x ships unobfuscated clients, so these
  rows build through loom's no-remap mode (no mappings declared at all, official names in the
  jar); the screen switch the config screen uses moved from `Minecraft.setScreen` to
  `Minecraft.gui.setScreen` in the 26.2 client, which became the third per-row compile seam.
  Dev-run verified on 26.3.0, 26.1.2 and 26.2.0: server up in ~3 s, `.cso` files written, zero
  `.mca`. The Gradle wrapper moved to 9.7.1 as part of this.
- **Voxy world-import compatibility, bundled as a nested mod inside every Fabric jar ("CSO Voxy
  Compat").** With Voxy installed, `/voxy import world` and `/voxy import current` also read the
  `.cso` region files this mod writes, not just vanilla `.mca` — the importer's own parsing
  handles them, so nothing changes about how imports run. Players without Voxy get a quiet child
  entry in Mod Menu and nothing else; a Voxy update that changes the importer's shape disables
  the `.cso` support with a log line instead of crashing. The compat keeps its own version
  number (1.0.0), so it only moves when it changes.

### Changed
- **Localization trimmed from twelve languages to six: English, Simplified Chinese, Traditional
  Chinese (TW and HK), Japanese and Korean.** The config screen, the Mod Menu entry and every
  description now fall back to English in the retired locales. Maintaining twelve full
  translations per release did not pay for itself.

### Note
- The mod's homepage link (Mod Menu, the NeoForge Mods screen, crash reports) now points at the
  Modrinth page instead of the repository.
- NeoForge builds for Minecraft 26.3.0 now require NeoForge 26.3.0.57-beta or newer (was
  26.3.0.51-beta). The 26.3 line still has no stable build, so its floor keeps tracking the
  newest beta.
- The file format is unchanged — a drop-in upgrade for any 1.x world.

## [1.1.1] - 2026-10-05

### Added
- **12-language localization.** The config screen now speaks English, Simplified Chinese,
  Traditional Chinese (TW and HK), Japanese, Korean, Russian, German, French, Spanish,
  Brazilian Portuguese and Italian — every label and tooltip translated in full. Mod Menu's
  list follows suit: the mod's name, the summary line under it and the description on the
  metadata screen localize through Mod Menu's own translation keys (its
  translate-descriptions option is on by default). The NeoForge Mods screen description
  localizes on both loader generations — FML 11 (Minecraft 1.21–26.2) and FML 12 (26.3+)
  each look up their own description key — so the translated description shows on every
  supported NeoForge version, not just the newest.
- **Homepage and issue links.** The NeoForge 26.3 Mods screen ships a Homepage and an Issues
  button for every mod, greyed out when the metadata carries no URL — this mod had neither
  filled in. Both now point at the GitHub repository, which also lights up the Website link
  in older NeoForge's mod info panel, puts the issue URL into every crash report ("Mod
  issues URL", previously "<No issues URL found>"), and gives Mod Menu's Links section
  something to show on Fabric.

### Note
- The `cachedBuckets` tooltip now mentions the 8 MB per-file cache budget added in 1.1.0;
  the option's meaning is unchanged. Every other tooltip was reviewed against the recent
  changes and left alone — none had gone stale.
- NeoForge builds for Minecraft 26.3.0 now require NeoForge 26.3.0.51-beta or newer (was
  26.3.0.48-beta). The 26.3 line still has no stable build, so its floor keeps tracking the
  newest beta.
- The file format is unchanged — a drop-in upgrade for any 1.x world.

## [1.1.0] - 2026-10-05

### Changed
- **The bucket cache now defaults to 64 buckets per region file (was 4), with a new 8 MB
  per-file byte ceiling.** A player's working set at the default grid is dozens of buckets —
  render distance 12 covers roughly 150 of them — and a cache miss costs a full bucket
  decompress per chunk read; measured on a roaming-shaped workload, the new default reads
  5.9x faster than the old one. The byte ceiling is what makes raising the count safe: at
  small grids one bucket holds a whole region's worth of chunks, so a count alone could pin
  gigabytes of heap. The ceiling evicts by bytes there, and a single payload larger than the
  whole budget is not cached at all; at the default grid the count knob stays the effective
  limit. **Existing config files are not rewritten** — raise `cachedBuckets` by hand to get
  this.
- **Chunk reads no longer copy the chunk out of the cached bucket before parsing.** The
  parser now reads straight out of the cached payload, which is never mutated in place,
  removing one allocation and one copy per chunk load — the storage layer's warm-path read
  drops by 93% (measured 675 → 45 ns). Small per read, but it is the only avoidable part of
  the hottest path.
- **NBT parsing and serialization moved outside the storage lock.** Parsing a real chunk's
  NBT costs ~29 µs — two orders of magnitude more than the locked lookup it was holding the
  lock through — so every concurrent thread (other readers, the scan pool, the batch timer)
  queued behind work that protects nothing: the bytes being parsed are immutable once
  published. Measured on real chunk data with two readers and a writer: concurrent read
  throughput up to 2x, p99 read latency down 2-3x, and a writer that the old code starved
  under read load (484 writes in 8 s) now sustains 9,704 while reads are also faster.

### Note
- NeoForge builds for Minecraft 26.3.0 now require NeoForge 26.3.0.48-beta or newer (was
  26.3.0.43-beta). The 26.3 line has not shipped a stable build yet, so its floor follows
  the newest beta — the same policy every closed beta-only line already had.
- The file format is unchanged — every .cso written by any 1.x version stays readable, so
  this is a drop-in upgrade.

## [1.0.10] - 2026-10-05

### Fixed
- **One failed bucket-table write could permanently destroy a bucket.** The write path committed
  its in-memory entry before the durable table-entry write, so a failure between the two — a
  transient I/O error, a full disk after the data block landed — let the automatic batch retry
  (500 ms later) reuse the old block's space and overwrite the only intact copy a valid table
  entry still pointed at. The region then refused every chunk of that bucket with a corruption
  error. The write path now defers the in-memory commit until the table entry has landed, the
  same rule compaction already follows since 1.0.9.
- **A chunk larger than ~1 MiB compressed corrupted the .mca written for it.** The Anvil header
  stores the sector count in eight bits; the writer let a count of 256 or more wrap into the
  sector field, producing a file not even this mod's own reader could read back. Vanilla moves
  such chunks to an external .mcc; this writer has no external path, so it now refuses the
  write before touching the file. .mca writes also go through a temp file and an atomic move
  now, so a mid-write I/O error can no longer replace a whole region with a half-written one;
  the offline converter skips a refused file, keeps the original, and says why.
- **A corrupt chunk index could crash the read path with an unchecked exception** instead of the
  documented corruption error: the bounds check added the offset and length as ints, so a pair
  that wrapped the int slipped past it. The write path's copy of the same check was hardened
  with a long cast in 1.0.9; the read path now matches.
- **A locked or read-only .mca silently regenerated the world over it.** A legacy file that
  exists but cannot be opened — a read-only attribute, a backup tool's exclusive lock — was
  answered exactly like a missing one: seeding skipped it, the first bucket write became
  authoritative over chunks still living only in there, and the game regenerated terrain over
  them without one log line. Measured on a live save: all 900 slots the two formats shared had
  silently diverged. An unopenable .mca now fails loudly — the batch about to write its first
  bucket stays staged and is retried on the timer, so saving stalls visibly until the file
  opens again, and a failed read reaches the game the same way vanilla reports its own
  unreadable region files. Re-run after the fix: the locked window left the disk untouched,
  and the original chunks came back byte-identical.
- **A failed `/cso convert mca` had already switched the world to vanilla storage.** The
  cso.disabled marker was written before the conversion loop, so a mid-loop failure left the
  save claiming a conversion that never finished: on restart the converted half served its
  .mca files while the rest of the world lived only in .cso, and the error message said
  nothing about it. The marker is now written only after every file made it; a failed run
  leaves the world exactly as it was and says so.
- The mod's warnings now reach the game log. CsoStorage and CsoCommands logged through
  System.Logger, which no loader environment routes to the log file — including the
  "timed flush failed" warning that had existed all along. Both now use the same slf4j setup
  as the rest of the mod.

### Note
The file format is unchanged — every .cso written by any 1.x version stays readable, so this is
a drop-in upgrade. Existing config files are not rewritten, and the bucket-cache default
(cachedBuckets=4) is unchanged.

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
