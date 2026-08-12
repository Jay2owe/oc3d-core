# oc3d-core

Shared engine for the 3D Objects Counter plugin family.

**Status (2026-08-04): the chassis is complete.** Every package in the layout
below is built and tested. What remains is migrating the three plugins onto it —
see `../../3DObjectsCounterPlus/docs/OC3D_CORE_MIGRATION_PLAN.md`, stages 3-8.

**First consumer adopted (2026-08-05): Volumetric Colocalization**, for
`ingest`, `ui/ToggleSwitch` and macro tokenising. That migration found the
chassis was the *weaker* of the two implementations in two places, and both
were fixed here rather than worked around in the plugin:

- **`ingest`** — an ROI positioned on a slice beyond the reference stack was
  smeared through the whole volume instead of being refused, so its object
  measured many times its true size. Four further rules added alongside it
  (line/polyline/angle/point selections, ROIs outside the reference, degenerate
  polygons, null entries). All in `RoiLabelImages`, the validating front door;
  `LabelUtils` stays permissive for internal callers.
- **`macro`** — `MacroOptions.strictTokens` refuses unclosed brackets, stray
  closing brackets and line breaks inside values, all of which `tokens`
  silently mis-parses. Added alongside rather than replacing, so plugins that
  have not migrated keep their current behaviour until they choose otherwise.

Expect the same on each remaining migration: **diff before deleting, and the
stricter rule wins.**

| Package | Contents | State |
|---|---|---|
| Maven module, BSD-3, `net.imagej:ij` only | — | built — `mvn package` green |
| `api/` | `MorphPredicate`, `OC3DParameters`, `OC3DResult`, `WarningSink` | built |
| `label/` | `Connectivity`, `LabelParameters`, `LabelResult`, `LabelImages`, `StreamingLabeller`, `StreamingLabelEngine`, `LabelRenumberer` | built |
| `measure/` | `LabelFeatureAccumulator` — the single measurement path | built |
| `map/` | `ObjectMapBuilder` — objects, surfaces, centroids, centres of mass, with every positive label visibly rendered | built |
| `io/` | `CsvWriter`, `BatchFileDiscovery`, `WithinBatchScorer`, `ScoreFeatureCatalog`, `SummaryReporter` | built |
| `macro/` | `MacroOptions`, `MacroFilters` | built |
| `ui/` | `DialogModel`, `FilterRowsPanel`, `CollapsiblePane`, `DialogDefaults` | built |
| `ingest/` | `LabelUtils`, `RoiLabelImages` — ROI sets to label images | built; strictness rules promoted from Volumetric Colocalization 2026-08-05 |
| `image/` | `ImageOps` — thread-safe duplication and thresholding | built |
| `progress/` | `ProgressListener`, `StatusBarProgress` | built |
| `spi/` | `LabelEngine` | built |

**259 tests green**, including `PipelineIntegrationTest`, which walks one image
through macro string → engine → measurement → filtering → maps → summary → CSV
and asserts the pieces agree with each other.

Build and test:

```
mvn package             # BSD-3 only; no GPL artifact on any classpath
mvn -Poracle test       # adds sc.fiji:3D_Objects_Counter, test-scoped, to diff
                        # StreamingLabeller against the code it replaces
```

---

## What this is

A build-time library holding the code currently duplicated across
`3D Objects Counter+` and `3D Objects Counter - StarDist`, plus a new
dependency-free labeller that replaces mcib3d and the classic `Counter3D`.

**It is never shipped as a jar.** Each plugin shades a privately-relocated copy
into its own artifact, so every plugin remains a single self-contained file that
installs on a bare Fiji with no prerequisites.

## Why

Three problems, one fix.

**1. Duplication.** Plus and StarDist share 13 files / 3,760 lines that differ by
11 lines total — 78% of the StarDist variant. `DialogModel` (514 lines) and
`MacroOptionsParser` (404 lines) are byte-identical.

The StarDist variant is **complete**, not partial: `src/main` contains
`OC3DSDDialog`, `OC3DSDRunner`, both entry classes, its own `MacroOptionsParser`,
and full `api` / `batch` / `ui` packages. So this is a genuine de-duplication —
deleting code that already exists — not an opportunity to avoid writing it. The
cost has been paid once; extraction stops it being paid a third time by the next
variant.

**2. Dependencies.** `3D Objects Counter+` links two GPLv3 libraries:

| Artifact | Licence | Where users get it |
|---|---|---|
| `sc.fiji:3D_Objects_Counter:2.0.1` | GPLv3 | Base Fiji ✅ |
| `org.framagit.mcib3d:mcib3d-core:4.1.7b` | GPLv3 | **3D ImageJ Suite update site** ❌ |

Because of these, the distributed Plus artifact is currently GPL-3.0-or-later
(correctly documented in its `LICENSING.md`: BSD-3 source, GPL combined work).
Removing both dependencies is what allows the distributed artifact to return to
plain BSD-3-Clause — see "Licence outcome" below.

Verified on a live install: `mcib3d-core-4.1.7b.jar` exists only in
`Fiji.app/plugins/mcib3d-suite/`, never in `jars/`. The README's claim that both
are "provided by Fiji's core update sites" is wrong. Users without the 3D ImageJ
Suite enabled hit `requireMcib3dAvailable` exceptions on 32-bit images,
multichannel images, hyperstacks, and label-image input — with no message telling
them why.

`mcib3d-core` is also on no Maven repository (404 on Maven Central and
`maven.scijava.org`), so the build needs a manual `mvn install:install-file` and
CI is impossible.

**3. Memory.** The classic path allocates whole-volume parallel arrays plus
per-voxel object storage. For a 1024×1024×50 stack at 10% foreground that is
roughly 985 MB against a 105 MB image — about 9× overhead — and
`int length = width*height*nbSlices` overflows above 2³¹ voxels, which is the
documented failure over 2048 MB.

## Design

### One measurement path

`LabelFeatureAccumulator` already computes the entire statistics table in pure
Java — imports are `ij.*` and `java.util.*` only. It streams: one slice at a
time, accumulating running sums per label, never storing a voxel list. Memory is
O(objects), not O(voxels).

`ObjectsCounter3DWrapper.buildNativeStatisticsTable` (the mcib3d path) is a
second, complete implementation of the same thing. It is deleted.

### Streaming slice-pair union-find labeller (new)

The classic code flattens the stack into one array. An ImageJ stack is already a
list of per-slice `ImageProcessor`s, so that is unnecessary.

```
for each slice z:
    label connected runs within the plane   → provisional labels
    compare against slice z-1's label plane
    where foreground overlaps              → union(a, b)
    discard slice z-1
finally:
    find() resolves provisional → final labels
    feed straight into LabelFeatureAccumulator
```

Only two label planes are ever held. The implementation reads the source twice —
pass 1 assigns fragments and unions them, pass 2 writes final labels — so the
output bit depth can be chosen from the final object count rather than from a
provisional maximum nobody knows until pass 1 ends.

**Measured**, 2026-08-03, on 1024×1024×50 (52.4M voxels, 1.84M foreground, 2262
objects), fresh JVM per run, bisecting `-Xmx` for the smallest heap that
completes:

| | Minimum heap | Wall clock | Objects |
|---|---|---|---|
| Classic `Counter3D` | **768 MB** | **107 s** | 2262 |
| **Streaming slice-pair union-find** | **384 MB** | **1.1 s** | 2262 |

Identical object counts, ~2× less memory, and **roughly 100× faster** — the speed
comes from replacing `Counter3D.replaceID`, which rewrites the whole volume array
on every merge, with union-find.

**Correcting an earlier estimate in this document:** the "~20 MB" figure was the
labeller's own working set and it is about right — two `int` planes are 8 MB at
this size. But it is not the number a user feels. The output label image is a
full-volume allocation that cannot be avoided, because the plugin needs it for
maps, filtering and measurement; at 2262 objects it is 16-bit, 105 MB. Minimum
heap is the honest measure, and it is 2×, not 50×. The 50× claim would have been
wrong in the direction that matters.

imglib2 was considered and rejected: it needs three extra jars
(`imglib2`, `imglib2-algorithm`, `imglib2-ij`), imports a `RandomAccessibleInterval`
type system foreign to the rest of the engine, and — decisively — writes labels
into a whole-volume output image, which is the exact allocation the streaming
design exists to remove. It also does not lift the 2³¹ cap.

**Connectivity was the highest-risk decision in this whole project. It is
settled: 26, and both existing paths agree.**

- `Utilities.Counter3D.minAntTag` scans the 13 anterior members of the
  26-neighbourhood; `findObjects` then sweeps the full 3×3×3.
- `mcib3d.image3d.ImageLabeller.getLabels(ImageHandler)` passes `false` for
  `connectivity6`, dispatching to `labelSpots26`.

Since they agree, both Case A and Case B references can be honoured — the
scenario the migration plan flagged as a possible pre-existing bug did not
materialise. 26 is the default in `Connectivity`, asserted in
`ConnectivityDiscriminatorTest`, exposed as an option, and carried on
`LabelResult` for CSV headers.

### Two defects found in the code being replaced

Both surfaced by `Counter3DOracleTest`, both fixed by construction rather than by
patching, and both belong in the CHANGELOG as **fixes**, not silent changes.

**1. `Counter3D` throws on a legal image.** `findObjects()` sizes `IDcount` as
`new int[tag]`, and `tag` is bumped at the *start* of the next voxel's iteration.
If the final voxel of the volume — bottom-right of the last slice — is foreground
and has no foreground anterior neighbour, it consumes the highest label with no
iteration left to bump `tag`, and the tally loop indexes one past the end. The
user gets an `ArrayIndexOutOfBoundsException`, not a message. Minimal reproducer:
a single voxel in the far corner of the last slice. `StreamingLabeller` has no
such array.

**2. "Exclude objects on edges" can keep an edge-touching object.** Edge contact
is recorded against whichever provisional id a voxel carries when the second pass
reaches it, and `replaceID` does not carry that flag across a later merge. An
object whose only edge contact is labelled under an id that is merged away
afterwards survives a filter it should fail. Reproduced on a built shape; not
seen on 23 randomised volumes, so it is narrow rather than routine.
`StreamingLabeller` ORs the flag into the component root. This makes the object
set **Tier 3** when the option is on — see `EQUIVALENCE_HARNESS.md` §3.

### Dependencies

```xml
<dependency>
  <groupId>net.imagej</groupId>
  <artifactId>ij</artifactId>
  <scope>provided</scope>
</dependency>
```

That is the whole list. `net.imagej:ij` is ImageJ itself — it is what Fiji *is*,
it is on Maven Central, and no user ever installs it. This matches CPC, which
already ships on exactly this footprint.

No mcib3d. No `sc.fiji:3D_Objects_Counter`. No imglib2. No GPL.

### Layout, as built

```
oc3d-core                       BSD-3, one dependency, no dialog on the headless path
  api/        MorphPredicate, OC3DParameters, OC3DResult, WarningSink
  label/      StreamingLabeller       <- NEW: slice-pair union-find
              LabelRenumberer         <- lifted from the StarDist variant
              Connectivity, LabelParameters, LabelResult, LabelImages
  measure/    LabelFeatureAccumulator <- THE single measurement implementation
  map/        ObjectMapBuilder
  io/         CsvWriter, BatchFileDiscovery, WithinBatchScorer,
              ScoreFeatureCatalog, SummaryReporter
  macro/      MacroOptions, MacroFilters
  ui/         DialogModel, FilterRowsPanel, CollapsiblePane, DialogDefaults
  ingest/     LabelUtils, RoiLabelImages   <- lifted from CPC
  image/      ImageOps                     <- thread-safe duplication
  progress/   ProgressListener, StatusBarProgress
  spi/        interface LabelEngine { LabelResult label(ImagePlus, LabelParameters, ProgressListener); }
```

Four points where the extraction is not a straight copy, each deliberate:

- **`macro/` is split in two.** `MacroOptions` holds the tokeniser and the number
  parsing; `MacroFilters` holds direct-predicate parsing against a
  caller-supplied feature set. The old `MacroOptionsParser.Parsed` bundle stayed
  behind in each plugin, because its fields (`measureFractalXY`, `probability`)
  are the one part that genuinely differs between variants.
- **`ui/DialogModel` is a base class, not a copy.** It carries what every variant
  has — size limits, edge exclusion, output toggles, redirect, the filter table —
  and four hooks (`appendEngineMacroOptions`, `appendExtraMacroFlags`,
  `activeAdditionalRanges`, `copyAdditionalFrom`) for what they do not. Growing
  one class a `threshold` StarDist cannot use and a `probability` the threshold
  engine cannot use is the thing being avoided.
- **`api/MorphPredicate` and `io/ScoreFeatureCatalog` take registrations.** A
  variant adds its extra feature names at startup rather than the core importing
  a catalogue it cannot see. Additive only: a filter that stopped filtering
  halfway through a batch would change results without changing inputs.
- **`measure/LabelFeatureAccumulator` computes sphericity and compactness.** Plus
  wrote `NaN` into those columns from this accumulator and filled them from
  mcib3d; with mcib3d gone there is nothing else to fill them, and the StarDist
  variant already computed them here. Both are Tier 2 in the harness, which
  anticipates exactly this.

Two behaviour changes on promotion, both fixes:

- **ROI sets above 65,535 objects no longer wrap.** CPC's `roiSetToLabelImage`
  always allocated 16-bit, so ROI 65,536 became label 0 — background — with no
  error. Bit depth now follows the ROI count.
- **`ImageOps` sub-range duplication no longer clamps to slice 1.**
  `ImagePlus.getStackIndex()` does not call `verifyDimensions()`, so on an image
  built straight from an `ImageStack` and never displayed — every headless run —
  it still saw `nSlices=1`. Regression test:
  `ImageOpsTest.anImageBuiltStraightFromAStackStillIndexesCorrectly`.

### What each plugin keeps

Only its entry class, its `plugins.config`, its own `api` package (the documented
public Java API), and its engine adapter:

| Plugin | Its own code |
|---|---|
| `3D Objects Counter+` | threshold engine adapter, `sc.fiji.oc3dplus.api` |
| `3D Objects Counter - StarDist` | `StarDistTrackMateRunner`, `StarDistLinkingParams`, `StarDistPostFilters` |
| `3D Objects Counter - Labels` | label/ROI source adapter (thin — core does the work) |

## Packaging: shade with relocation

Each plugin jar carries its own privately-named copy:

```
3D_Objects_Counter_Plus.jar     → sc.fiji.oc3dplus.internal.core.*
3D_Objects_Counter_Labels.jar   → sc.fiji.oc3dlabels.internal.core.*
3D_Objects_Counter_StarDist.jar → sc.fiji.oc3dsd.internal.core.*
```

No two jars contain the same fully-qualified class name, so Fiji's flat
classloader cannot pick the wrong one. Self-contained *and* collision-free.

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-shade-plugin</artifactId>
  <configuration>
    <relocations>
      <relocation>
        <pattern>sc.fiji.oc3d.core</pattern>
        <shadedPattern>sc.fiji.oc3dplus.internal.core</shadedPattern>
      </relocation>
    </relocations>
    <createDependencyReducedPom>true</createDependencyReducedPom>
  </configuration>
</plugin>
```

**Do not relocate the public API.** `sc.fiji.oc3dplus.api.OC3DPlus` is documented
in the README as a Java entry point; relocating it breaks callers. Relocate the
internal `core` package only. Each plugin's own `api` package already lives under
its own namespace and never collided.

**Grep for `Class.forName` and reflection before enabling shading.** Relocation
rewrites bytecode references but cannot rewrite strings.
`ObjectsCounter3DWrapper` currently does
`Class.forName("mcib3d.image3d.ImageLabeller")` — that pattern breaks under
relocation. It disappears with mcib3d, but confirm nothing else does it.

### The trade being made

A core bugfix means rebuilding and re-releasing **every** variant jar rather than
shipping one shared file. That is maintainer effort traded for user clicks, taken
deliberately: the install must stay one file, no prerequisites. Reversible later
if the family outgrows it.

## Licence outcome

`oc3d-core` itself is **BSD-3-Clause** — see `LICENSE`, with attribution in
`NOTICE` — and links nothing but `net.imagej:ij` (ImageJ 1.x, public domain).
But the consumers do **not** all end up in the same place, because the refactor
removes mcib3d — not TrackMate.

| Plugin | GPL deps after migration | Distributed artifact |
|---|---|---|
| `3D Objects Counter+` | none | **BSD-3-Clause** ✅ changes |
| `3D Objects Counter - Labels` / `- Nested` / `- Watershed` | none | **BSD-3-Clause** |
| `3D Objects Counter - StarDist` | `sc.fiji:TrackMate` (GPLv3), `sc.fiji:TrackMate-StarDist` (GPLv3+) | **GPL-3.0-or-later** ❌ unchanged |

The StarDist variant's engine *is* TrackMate. No amount of core extraction
removes it, and its pom already says so:

> "TrackMate is still GPL, so the combined distributed work remains
> GPL-3.0-or-later — see LICENSING.md."

So its current GPL-3.0-or-later declaration is correct and must stay. Its source
remains BSD-3-Clause under the same dual structure Plus uses today.

**Timing matters.** Plus must not be relicensed back to BSD-3 until the commit
that actually removes both GPL dependencies. Flipping it early re-introduces the
exact inaccuracy the dual-licence structure was created to fix. The flip is a
gated step in the migration plan, not a preparatory edit.

## Install story

| | Today | After |
|---|---|---|
| Prerequisites | Base Fiji **+ 3D ImageJ Suite (undocumented)** | Base Fiji |
| Update site | Enable → restart | Enable → restart |
| Manual | 1 jar | 1 jar |
| 32-bit / multichannel / hyperstack | Throws without mcib3d | Works |
| Label-image input | Throws without mcib3d | Works |
| Headless / CI | Blocked (manual jar install) | Works |

## Ship gate

**Nothing ships until outputs match.** See `EQUIVALENCE_HARNESS.md` for the
golden-master corpus, the three-tier column contract, and the sign-off rules.

Summary of the gate:

- **Tier 1** — object counts, voxel counts, volume, bounding box, centroids,
  intensity statistics: **bit-identical**, no exceptions.
- **Tier 2** — surface, sphericity, compactness, elongation: within a stated
  tolerance of the pinned reference, deltas documented.
- **Tier 3** — Feret: known algorithmic difference (13-direction bounded estimate
  vs mcib3d's exact computation). Requires explicit written sign-off and a
  CHANGELOG entry, or the algorithm is changed to match.

## Order of work

1. ~~Determine and pin connectivity empirically.~~ **Done** — 26, both paths agree.
2. Build the equivalence harness and capture golden output from the **current**
   build, tagged with its git SHA. **Still outstanding**, and it gates step 5.
3. ~~Write `StreamingLabeller`.~~ **Done** — verified voxel-for-voxel against
   `Counter3D` by `Counter3DOracleTest`.
4. ~~Extract shared classes into `oc3d-core`.~~ **Done** — see the layout above.
5. Migrate `3D Objects Counter+` — see its `docs/OC3D_CORE_MIGRATION_PLAN.md`.
6. Migrate `3D Objects Counter - StarDist` — see its plan.
7. Only then: new variants (Labels, Nested, Watershed).

**Step 2 has not been done.** The core's own tests pin its behaviour against
hand-derived expectations and, for labelling, against the GPL reference. They do
not establish that a migrated plugin produces the same numbers as the shipped
one — that is what the harness is for, and it must be captured from the
pre-migration build before step 5 starts.

## The module family

`oc3d-core` is the chassis. Each plugin additionally has its own engine core.
See `../PLUGIN_CORE_PATTERN.md` for the standing rule.

```
oc3d-core         chassis: labelling, measurement, maps, batch, macro parsing,
                  UI widgets, label/ROI ingest
   ↑         ↑          ↑
cpc-core   volcoloc-core   <next>-core        engine cores, one per plugin
   ↑         ↑          ↑
plugins    3D Objects Counter+ · - StarDist · - Labels · CPC ·
           Volumetric Colocalization · Colocalization Suite · …
```

Engine cores may depend on `oc3d-core`. **They never depend on each other** — if
two need the same thing, it moves down into `oc3d-core`.

No core is ever shipped as a jar. Each plugin shades in exactly what it needs,
relocated into its own namespace. A user who wants coincidence columns inside
3D Objects Counter+ downloads nothing extra and never learns that CPC or any
core exists.

## Citation

This module is archived on Zenodo so that plugins which compile it in can be
rebuilt from their own archives alone, without depending on GitHub staying
reachable.

| | DOI |
| --- | --- |
| Concept (always resolves to the latest release) | [`10.5281/zenodo.21822701`](https://doi.org/10.5281/zenodo.21822701) |
| v0.1.0 | [`10.5281/zenodo.21822702`](https://doi.org/10.5281/zenodo.21822702) |
| v0.2.0 | [`10.5281/zenodo.21823678`](https://doi.org/10.5281/zenodo.21823678) |

Cite the **version** DOI when reproducibility is the point — the concept DOI
follows this module forward to releases a given plugin was never built against.

Most users should not cite this directly. It is a build-time library that is
never shipped as a jar and never appears on an update site; cite the plugin
that embeds it. CPC 1.5.0 embeds v0.1.0 via `cpc-core` 0.1.0
([`10.5281/zenodo.21812272`](https://doi.org/10.5281/zenodo.21812272)).

## Related

- `EQUIVALENCE_HARNESS.md` — the shared before/after testing contract
- `../3DObjectsCounter-Variants-Brainstorm-2026-08-03.md` — why this is the
  first piece of work, and what it unlocks
- CPC (`../../CPC`) — the architectural model: BSD-3, pom-scijava 43.0.0, one
  dependency. Its `LabelUtils` and `CPCLabelImages` are lifted into
  `oc3d-core/ingest`.
