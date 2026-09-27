# Changelog

## [1.0.3] - 2026-09-27

### Added
- **Minecraft 1.21 through 1.21.11.** Eight more game versions are supported next to 26.1.2 and
  26.2.0: 1.21, 1.21.1, 1.21.3, 1.21.4, 1.21.5, 1.21.8, 1.21.10 and 1.21.11. Nothing about the
  on-disk format changed, so a world is not touched differently than it was in 1.0.2.
  (Minecraft 26.1, 1.21.2, 1.21.6, 1.21.7 and 1.21.9 have never had a stable NeoForge release, so
  there is no jar to install on them.)
- **An author and a licence in the Mods screen.** The mod entry now names `tqk114514` and reports
  the project's licence as MIT, which is what the repository has carried all along.

### Changed
- **One jar per Minecraft version, named after it.** The file is now
  `chunkstorageoptimizer-<minecraft>-<version>.jar`, because a single release produces ten of them
  and they were indistinguishable once downloaded. Pick the one matching your game; the loader
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
