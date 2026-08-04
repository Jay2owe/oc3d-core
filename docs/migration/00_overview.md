# oc3d-core migration — program overview

Master index for the two migration plans. Split from:

- `../../../../3DObjectsCounterPlus/docs/OC3D_CORE_MIGRATION_PLAN.md`
- `../../../../3DObjectsCounter-StarDist/docs/OC3D_CORE_MIGRATION_PLAN.md`

Those two documents remain the **statement of intent**. This folder is the
**execution split**: one file per stage, each sized for a single agent or a
single sitting, each independently verifiable.

**Ship gate for both plugins:** `../../EQUIVALENCE_HARNESS.md` §9. Not restated
here, not relaxed here.

---

## Verified state, 2026-08-04

Measured, not assumed. Every line below was checked by building or reading.

| Repo | Builds? | Tests | Migration state |
|---|---|---|---|
| `oc3d-core` | ✅ `BUILD SUCCESS` | **250 pass** | Built, installed to `~/.m2` |
| `3DObjectsCounter-StarDist` | ✅ `BUILD SUCCESS` | **53 pass** | Stage 0 — not started |
| `3DObjectsCounterPlus` | ❌ **cannot compile** | — | Stage 0 — **blocked**, see P0 |

### oc3d-core is complete and healthy

Confirmed. 30 main classes across `api`, `image`, `ingest`, `io`, `label`,
`macro`, `map`, `measure`, `progress`, `spi`, `ui`; 250 tests green; jar,
sources jar and tests jar all build.

Two corrections to the assumption that it is "fully built":

1. **It was not installed anywhere.** `~/.m2/.../oc3d-core/` did not exist, so
   neither plugin could have depended on it even if their poms named it.
   Fixed 2026-08-04 — `mvn install` run, now resolvable offline.
2. **Nothing consumes it.** Neither plugin pom references it. Core being green
   proves the module works; it proves nothing about equivalence, because no
   plugin output has yet been produced through it. The harness is what closes
   that gap, and the harness does not exist yet in either repo.

Its coordinates, needed verbatim by both migrations:

```xml
<dependency>
    <groupId>io.github.jay2owe</groupId>
    <artifactId>oc3d-core</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

`io.github.jay2owe` is **correct and deliberate** — it is the group id across the
whole family (`CPC` 1.4.0, `3D_Objects_Counter_Plus`,
`3D_Objects_Counter_StarDist`). `sc.fiji` is the SciJava org's own namespace on
maven.scijava.org, and this module is explicitly never published as a
user-facing jar. Do not "fix" it.

### Group id and package are orthogonal — shading needs both

Neither plan document states a group id; both only ever name the *package*
`sc.fiji.oc3d.core`. That silence invites the wrong guess, and the shade config
needs the two as **different strings**:

```xml
<artifactSet>
  <includes>
    <include>io.github.jay2owe:oc3d-core</include>   <!-- Maven COORDINATE -->
  </includes>
</artifactSet>
<relocations>
  <relocation>
    <pattern>sc.fiji.oc3d.core</pattern>             <!-- Java PACKAGE -->
    <shadedPattern>sc.fiji.oc3dplus.internal.core</shadedPattern>
  </relocation>
</relocations>
```

**Why this is worth a callout:** a wrong group id in `<artifactSet>` does not
error. It matches nothing, the build succeeds, and the jar ships without the
core classes — surfacing as `NoClassDefFoundError` in Fiji at runtime. It is the
one place in the shading stages where a wrong string fails silently.

The package/group-id mismatch is legal and costs nothing at runtime, since the
package is relocated at shade time and no user ever sees it.

**Note for the shading stages:** CPC has no shade configuration, and `cpc-core`
and `volcoloc-core` are still README-only stubs. `oc3d-core` is the **first
shading exercise in the family** — there is no working in-house example to copy,
and no prior art to inherit a mistake from either.

---

## P0 — the blocker, before anything else

**`3DObjectsCounterPlus` cannot be compiled or version-controlled on this
machine.** Dropbox has dehydrated it to online-only placeholders that fail to
rehydrate.

Evidence:

- **51 of 72** files under `src/` carry `Offline, ReparsePoint` attributes.
  This includes **every test file**, the whole `batch/` package, and the whole
  `engine/extended/` arborization/Sholl/fractal subsystem.
- `mvn clean compile` fails with
  `error reading …BinaryMaskOps.java: The file cannot be accessed by the system`
  across ~10 files before aborting.
- **256 of 921** files in `.git` are dehydrated, including loose objects.
  `git log`, `git status` and `git rev-parse HEAD` all fail with
  `unable to open loose object … Function not implemented` → `fatal: bad object HEAD`.
- Dropbox.exe **is running**. Forced reads of all 256 objects: **0 hydrated, 256
  failed**. `attrib +P -U` on a source file did not hydrate it either.

**Why this stops the whole Plus plan, not just one stage.** Harness §7 step 1 is
"check out the current build, record the git SHA"; step 2 captures goldens *from
that build*. With no buildable current build there is no reference, and with no
readable git history there is no SHA to name it under. Stage 0 is unreachable,
and every later stage is gated on Stage 0.

It also means the Plus source on this machine is **not currently backed by a
readable history**. Treat that as a data-integrity issue in its own right.

Handled in `../../../../3DObjectsCounterPlus/docs/migration/00_P0_unblock_repo.md`.

---

## Two corrections to the StarDist plan

Both were checked by diffing the actual files. Both change how Stages 1–2 must
be executed, so they are recorded here rather than left to be rediscovered.

### 1. "Byte-identical to Plus" is false for the two largest classes

The plan's *What moves* table is right about the small leaves and wrong about
the big ones. Measured, after normalising package and class-name prefixes:

| Class | Plan claims | Actual Plus↔SD diff |
|---|---|---|
| `ui/CollapsiblePane` | "Identical" | **1 line** ✅ |
| `ui/FilterRowsPanel` | "1 line differs" | **1 line** ✅ |
| `engine/ImageOps` | "1 line differs" | **1 line** ✅ |
| `engine/ObjectMapBuilder` | "1 line differs" | **1 line** ✅ |
| `api/MorphPredicate` | "Byte-identical" | **9 lines** ⚠️ |
| `MacroOptionsParser` | "404 lines, byte-identical" | **134 lines** ❌ |
| `ui/OC3DSDDialogModel` | "514 lines, byte-identical" | **309 lines** ❌ |
| `engine/LabelMeasurements` | "3 lines differ" | **39 lines** ❌ |

The two "byte-identical" claims are not near-misses. SD's `OC3DSDDialogModel` is
473 lines, not 514 — 514 is *Plus's* count, so the plan appears to have measured
Plus's file and labelled it SD's. SD's copy imports `StarDistLinkingParams` and
`ModelResolver`, and its own class comment says: *"The one section that differs
is detection, where a threshold is replaced by a model plus the detection and
linking settings."* These are deliberately divergent files.

### 2. The pair the plan tells you to diff is the wrong pair

Stage 1 says *"diff this repo's copy against Plus's before deleting."* That
answers "have Plus and SD drifted?" — but the class being adopted is **core's**,
which is a generalised third implementation, not a copy of either parent:

| Class | Plus↔SD | Plus↔core | SD↔core |
|---|---|---|---|
| `CollapsiblePane` | 1 | **26** | **27** |
| `ImageOps` | 1 | **49** | **50** |
| `ObjectMapBuilder` | 1 | **117** | **118** |
| `MorphPredicate` | 9 | **100** | **101** |
| `LabelFeatureAccumulator` | 39 | **368** | **373** |
| `DialogModel` | 309 | **474** | **463** |

Core's `LabelFeatureAccumulator` is 1056 lines against SD's 861 and Plus's ~890.
Core's `MorphPredicate` is 234 against SD's 161.

**Consequence:** there is no "lowest-risk subset with no behavioural surface of
its own". Every adoption is a behaviour review against core, including the ones
the plan calls leaves. Stage 1 stays first because its classes are the *least*
likely to move numbers — not because they are free.

This does not indict core. Generalising during extraction is what the module is
for. It does mean the harness is load-bearing for **every** stage, and that
Stage 1's exit gate must be the full harness, exactly as written.

---

## Sequencing — reversed from the original plans

The StarDist plan states: *"this plan runs after the Plus migration, which is
where the new labeller and the core module are built and proven."*

**That ordering no longer holds, for three reasons:**

1. The labeller and the core module **are** built and proven — `oc3d-core` is
   green, and `Counter3DOracleTest` already validates `StreamingLabeller`
   voxel-for-voxel against the GPL `Counter3D`. The premise is satisfied.
2. StarDist **does not use `StreamingLabeller` at all** — its own plan §2 says
   so. It never thresholds and never runs connected components.
3. Plus is blocked on P0 and StarDist is not.

**Run StarDist first.** It is the lower-risk repo (no GPL removal, no licence
flip, no memory rewrite, no engine collapse) and it exercises the core adoption
path end to end, so it de-risks Plus rather than depending on it.

The one genuine cross-dependency is harness §2's standing cross-check —
*Plus and StarDist must agree exactly on the same label image*. That is a
**Stage 2 exit-gate item in the SD plan that cannot be satisfied until Plus is
migrated**. It is deferred, not dropped: see `SD/03` and `PLUS/05`.

### Execution order

```
P0   Unblock the Plus repo (Dropbox + git)      ← blocks everything in PLUS/
 │
 ├── SD/01 … SD/06   StarDist  (runnable NOW, independent of P0)
 │
 └── PLUS/01 … PLUS/07   Plus  (needs P0)
             │
             └── SD/03 deferred cross-check closes here
```

---

## File index

### `3DObjectsCounter-StarDist/docs/migration/` — start here

| File | Stage | Risk |
|---|---|---|
| `01_harness_and_goldens.md` | Plan Stage 0 | Determinism unknown — answer first |
| `02_leaf_classes.md` | Plan Stage 1 | Low, but not zero — see correction 2 |
| `03_measurement_and_maps.md` | Plan Stage 2 | **Highest — this moves numbers** |
| `04_batch_runtime_renumberer.md` | Plan Stage 3 | Medium — CSV schema is user-visible |
| `05_shade_and_package.md` | Plan Stage 4 | Medium — reflection in `runtime/` |
| `06_docs_and_update_site.md` | Plan Stage 5 | Low — no licence change |

### `3DObjectsCounterPlus/docs/migration/`

| File | Stage | Risk |
|---|---|---|
| `00_P0_unblock_repo.md` | — | **Blocker. Nothing else runs.** |
| `01_harness_and_goldens.md` | Plan Stage 0 | Needs real-data corpus |
| `02_rewire_fromLabelImage.md` | Plan Stage 3 | Low — smallest mcib3d removal |
| `03_rewire_main_detection.md` | Plan Stage 4 | **Highest in the program** |
| `04_delete_dependencies.md` | Plan Stage 5 | Medium |
| `05_extract_core_and_shade.md` | Plan Stage 6 | Medium |
| `06_feret_and_licence.md` | Plan Stage 7 | Decision, not code |
| `07_release.md` | Plan Stage 8 | Outward-facing — confirm before publishing |

---

## Standing rules

- **Goldens are immutable.** Captured once from the pre-migration build. A wrong
  golden is a bug report against the shipped plugin, fixed as its own change —
  never regenerated to make a diff go away. (Harness §7.)
- **Tolerances are declared before the run, per column, with justification.**
  Not chosen after seeing results. (Harness §3.)
- **`batch_scores.csv` diffs are Tier 1 always.** One changed object shifts every
  score row; trace it to the causing object, never tolerance it away.
- **The licence flip happens in the same commit that removes the last GPL
  dependency** — Plus only, never StarDist. (Harness §9, last item.)
- **Out of scope, both repos:** the `- Labels` / Nested / Watershed variants, and
  the TrackMate 7 → 8 migration. Do not let either creep in.
