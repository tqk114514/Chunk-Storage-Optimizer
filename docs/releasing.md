# Releasing

Pushing a version tag publishes to Modrinth. There is no manual upload step.

## One-time setup

Two settings on GitHub (Settings → Secrets and variables → Actions). Both belong to the
**repository** scope, not the environment scope: nothing in these workflows declares an
`environment:`, so an environment secret would be invisible to them — and invisibly so, since the
only symptom is an empty token.

- **Repository secret `MODRINTH_TOKEN`** — a Modrinth personal access token with the
  `VERSION_CREATE` scope, created at <https://modrinth.com/settings/account>. It looks like
  `mrp_…` and is shown once. The name is case-sensitive and has to match exactly.
- **Repository variable `MODRINTH_PROJECT`** (on the Variables tab, not the Secrets tab) — the
  project's slug or id, i.e. the last path segment of its Modrinth URL. Optional; defaults to
  `chunk-storage-optimizer`.

Without the secret the publish job fails on every release, deliberately: a release that cannot
publish is worth shouting about rather than skipping quietly.

## Cutting a release

1. Bump `mod_version` in `gradle.properties` and date the `[Unreleased]` heading in `CHANGELOG.md`.
   That section becomes the release notes on Modrinth, lifted verbatim.
2. Commit, then tag — one tag per row of `supported-versions.csv`, all on the same commit, named
   `mc-<minecraft>-<version>`.
3. Push the commit and the tags. One of those tags builds every jar and publishes them; the other
   thirteen do nothing at all.

### Which tag does the work

A release is fourteen tags and GitHub starts a workflow run per tag, so tagging naively would build
fourteen times and publish fourteen times — 308 Modrinth versions for one release. The tag belonging
to the **first data row** of `supported-versions.csv` (today `mc-1.21-<version>`) is therefore the one
that counts as the release. It is read from the csv rather than hardcoded, so it follows the table if
the oldest supported Minecraft ever changes.

A tag whose version does not match `mod_version` fails the run outright instead of quietly doing
nothing, which is what a mistyped anchor tag would otherwise look like.

> Push tags in small batches. A single `git push --tags` carrying fourteen new tags has been killed
> mid-flight on this setup; a retry, or six at a time, goes through.

## Re-running, and putting back an older version

The upload is idempotent: before creating a version the script asks the project whether that
(minecraft, loader) pair already has this `version_number`, and skips it if so. Two things follow:

- a release that died half way can simply be re-run, and only the jars still missing go up;
- **Run workflow** on the Build action republishes anything, reading `mod_version` from the branch
  instead of from a tag. That is also how an already-tagged version gets its jars back if they have
  been deleted. It plans by default (`DRY_RUN=true`) and publishes when the box is unticked.

`FORCE=true` uploads regardless — the only way to end up with a duplicate on purpose.

## Why one Modrinth version per jar

A Modrinth version declares a list of loaders and a list of game versions, and the launcher resolves
an instance by matching those lists and then downloading that version's *primary file*. Putting both
loaders' jars in one version therefore cannot work: whichever file is primary, the other loader's
players get the wrong jar. So every jar becomes its own version with `loaders` and `game_versions`
each holding exactly one entry — the grouping JEI and AppleSkin both use.

The consequence to expect: a release is 22 Modrinth versions (10 NeoForge + 12 Fabric), all carrying
the same `version_number`.
