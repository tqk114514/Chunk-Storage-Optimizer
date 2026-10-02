# Releasing

A version tag publishes to Modrinth. Everything else is automatic — no manual upload.

## One-time setup

Two settings on GitHub (Settings → Secrets and variables → Actions):

- **Secret `MODRINTH_TOKEN`** — a Modrinth personal access token with the `VERSION_CREATE` scope,
  created at <https://modrinth.com/settings/account>. It looks like `mrp_…` and is shown once.
- **Variable `MODRINTH_PROJECT`** — the project's slug or id. Optional; defaults to
  `chunk-storage-optimizer`.

Without the secret the publish job fails on every tag, deliberately: a tag that cannot publish is
worth shouting about rather than skipping quietly.

## Cutting a release

1. Bump `mod_version` in `gradle.properties` and date the `[Unreleased]` heading in `CHANGELOG.md`.
   That section becomes the release notes on Modrinth, lifted verbatim.
2. Commit, then tag — one tag per row of `supported-versions.csv`, all on the same commit, named
   `mc-<minecraft>-<version>`. The tag's version must match `mod_version`; the publish job refuses
   to run if it does not.
3. Push the commit and the tags. The matrix builds every jar, then the `publish` job creates one
   Modrinth version per jar.

> Push tags in small batches. A single `git push --tags` carrying fourteen new tags has been killed
> mid-flight on this setup; a retry, or six at a time, goes through.

## Why one Modrinth version per jar

A Modrinth version declares a list of loaders and a list of game versions, and the launcher resolves
an instance by matching those lists and then downloading that version's *primary file*. Putting both
loaders' jars in one version therefore cannot work: whichever file is primary, the other loader's
players get the wrong jar. So every jar becomes its own version with `loaders` and `game_versions`
each holding exactly one entry — the grouping JEI and AppleSkin both use.

The consequence to expect: a release is 22 Modrinth versions (10 NeoForge + 12 Fabric), all carrying
the same `version_number`.

## Testing without cutting a tag

Run the **Build** workflow manually (Actions → Build → Run workflow). It builds the whole matrix and
then runs the upload path with `DRY_RUN=true` by default, printing exactly what it would publish
without calling Modrinth. Untick the box to publish for real.

That manual path is also how an already-tagged version gets uploaded again if its jars are gone: it
reads `mod_version` from the checked-out branch instead of from a tag.
