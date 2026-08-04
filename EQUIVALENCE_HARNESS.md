# Equivalence harness

The before/after testing contract for the `oc3d-core` migration. Shared by
`3D Objects Counter+` and `3D Objects Counter - StarDist`.

**Rule: nothing ships until the outputs match.** This document defines what
"match" means, because a naive reading of it is not achievable and pretending
otherwise would hide real regressions.

---

## 1. The problem with "the same numbers"

Two facts make a single global reference impossible.

**(a) Plus already has two engines that disagree with each other.**
`OC3DPlusRunner:311` routes between them:

```java
DetectionResult detected = canUseClassicCounter(channelImage, safe)
        ? detectWithClassicCounter(...)   // sc.fiji:3D_Objects_Counter
        : detectWithNativeCounter(...);   // mcib3d
```

`canUseClassicCounter` returns true only for 8/16-bit, single channel, single
frame. So "the current output" is engine-dependent, and the two engines do not
produce identical surface or shape values. The reference must be pinned per case.

**(b) Some columns cannot be identical by construction.**
`LabelFeatureAccumulator` computes Feret from **13 fixed directions** and says so
in the source: *"Bounded Feret estimate: fixed directional extrema, not exact
pairwise boundary distance."* mcib3d computes it exactly. These will differ, and
no amount of testing changes that — it is an algorithm choice that must be made
deliberately, not discovered during release.

Hence the three-tier contract below.

---

## 2. Reference matrix — what "before" means

| Case | Input | Current engine | Pinned reference |
|---|---|---|---|
| **A** | 8/16-bit, 1 channel, 1 frame | classic `Counter3D` | **classic path** |
| **B** | 32-bit, multichannel, or hyperstack | mcib3d | **mcib3d path** |
| **C** | label image (`fromLabelImage`) | mcib3d | **mcib3d path** |

Case A is what the overwhelming majority of users see today. It is the one that
must not move.

**Cross-check available for free:** `3D Objects Counter - StarDist` already
measures with its own `LabelMeasurements` (the same Lindblad-corrected surface
definition, no mcib3d — see its pom comment). After migration, Plus and StarDist
should agree **exactly** on the same label image, because both run the same
measurement code. Any disagreement is a bug in the extraction. Add this as a
standing test.

---

## 3. Column contract

### Tier 1 — bit-identical. No exceptions, no tolerance.

| Column | Why it must be exact |
|---|---|
| Object count | Integer. A change here means connectivity changed |
| `Nb of obj. voxels` | Integer voxel count |
| `Volume (unit)` | voxels × calibration — pure multiplication |
| `BX` `BY` `BZ` `B-width` `B-height` `B-depth` | Integer bounding box |
| `X` `Y` `Z` | Geometric centroid: mean of integer coordinates |
| `Min` `Max` | Integer selection from the intensity image |
| `IntDen` | Sum — exact for integer inputs |
| `Mean` `StdDev` `Median` `XM` `YM` `ZM` | Deterministic given fixed accumulation order (see below) |

**Design constraint this imposes:** the new labeller **must preserve z → y → x
accumulation order**, matching `LabelFeatureAccumulator.scan()` lines 60-84.
Floating-point summation is not associative; changing traversal order changes the
last bits of `Mean` and `StdDev` and turns a clean pass into a noisy diff. Do not
parallelise the accumulation pass without a deterministic reduction.

### Tier 2 — within stated tolerance, deltas documented.

| Column | Expected behaviour |
|---|---|
| `Surface (unit)` | Classic and Lindblad-corrected definitions differ. Case A **will** move |
| `Morph_Sphericity` | Derived from surface — inherits the difference |
| `Morph_Compactness` | Derived from surface — inherits the difference |
| `Morph_Elongation` | Moment-tensor eigenvalues; should be very close (≤1e-9 relative) |

Tolerance is not a number to be chosen conveniently after seeing the results.
Set it **before** running, per column, and justify it. Any object exceeding it
gets inspected individually, not averaged away.

Produce a delta table: per column, min / median / p95 / max relative difference
across the whole corpus, plus the count of objects outside tolerance.

### Tier 3 — known algorithmic difference. Requires written sign-off.

| Column | Difference |
|---|---|
| `Feret` / `feret_diameter_max` | 13-direction bounded estimate vs mcib3d's exact pairwise computation |
| Object set under **"exclude objects on edges"** | The classic path can keep an object that touches an edge — see below |

Two acceptable resolutions for **Feret**, decided explicitly:

1. **Accept** — document the change in `CHANGELOG.md` and the README, state that
   Feret is a bounded estimate and in which direction it errs (it can only
   under-estimate), and note that macro filters using `feret_diameter_max` may
   admit slightly different object sets.
2. **Match** — extend the direction set or implement exact pairwise Feret over
   the convex hull so the values converge, then Feret moves to Tier 2.

Shipping without choosing is the failure mode to avoid.

**Exclude-on-edges is a deliberate correction, not a regression** (established
2026-08-03, `Counter3DOracleTest`). `Counter3D.findObjects()` records edge contact
against whichever provisional id a voxel carries when its second pass reaches it,
and `replaceID` does not carry that flag across a later merge. So an object whose
only edge contact is labelled under an id that is merged away afterwards keeps no
flag and survives a filter it should fail.

Reproduced on a deliberately built shape — a single object whose left column
touches x=0 and whose two fragments join late, at a position touching no edge.
`Counter3D` keeps it; `StreamingLabeller` drops it, which is what the option
documents. On 23 randomised volumes the two agreed, so this is a narrow case, not
a routine one — but "narrow" is not "absent" and it must not be asserted away.

**Consequence for the gate:** with `excludeOnEdges` off — the default, and what
nearly every user runs — Tier 1 stands unchanged and exact. With it on, the
object set is Tier 3: sign off the correction and put it in the CHANGELOG.

---

## 4. Outputs to diff — not just the statistics table

Every user-visible artifact is in scope.

| Output | How to compare |
|---|---|
| Statistics `ResultsTable` | Full column diff, tiered as above |
| Object count / summary log line | Exact string match after normalising version and timing |
| **Objects map** | **Compare as a partition, not by pixel value** — see §5 |
| Surfaces map | Same partition comparison |
| Centroids map / Centers of mass map | Point sets, exact |
| `batch_manifest.csv` | Exact, excluding `BatchRunId`, timestamps, `PluginVersion` |
| `batch_objects.csv` | Tiered, after canonical row ordering |
| `batch_scores.csv` | Tiered. **Highest-sensitivity output** — see §5 |
| Extended columns (`Morph_Fractal*`, composites, Sholl, skeleton) | Exact — these run downstream of the label map, so identical labels must give identical values. A diff here means the labelling changed |
| Macro option round-trip | Every option in the README table parses to the same parameters |
| Headless run | Produces byte-identical CSVs to the GUI run |

---

## 5. Two comparison traps

**Label numbering will change — except where it provably does not.** A different
labelling algorithm normally assigns different integer IDs to the same objects,
and comparing object maps pixel-by-pixel then produces thousands of spurious
failures.

For **Case A this trap does not apply**, verified rather than assumed
(2026-08-03, `Counter3DOracleTest`): `Counter3D` renumbers by ascending
provisional id and hands provisional ids out in z → y → x scan order, so its
object 1 is the object whose first voxel is scanned first. `StreamingLabeller`
holds the same invariant by construction — a component's root is the smallest
fragment id in it. The two produce **byte-identical label images**, numbering
included, across the whole synthetic corpus. So Case A is compared by exact
equality; weakening it to a partition comparison would throw away the strongest
evidence available.

For **Cases B and C** the trap is real and the tools below apply:

- Compare object maps as **partitions**: the set of voxel-sets must be identical,
  regardless of which integer names them.
- Compare stats rows after **canonical sorting** — by `Z`, then `Y`, then `X`,
  then `Nb of obj. voxels`. Only then compare row-wise.
- Assert separately that labels are dense and 1..N with no gaps.

**`batch_scores.csv` amplifies everything.** Within-batch z-scores and
percentiles are computed against the whole population, so a single object
appearing, disappearing, or changing volume shifts the mean and SD and therefore
**every** score row in the batch. Treat any diff here as a Tier 1 failure and
trace it back to the object that caused it — do not tolerance it away.

---

## 6. Corpus

Synthetic cases live in the repo as generated fixtures (small, deterministic, no
binaries committed where a generator will do). Real cases live outside the repo
with a manifest of paths and checksums.

### Connectivity discriminators — run these first

Connectivity is the single highest-risk decision in the project. Get it wrong and
every object count changes, which is a Tier 1 failure everywhere at once.

| Fixture | 6-connectivity | 26-connectivity |
|---|---|---|
| Two cubes sharing a **face** | 1 object | 1 object |
| Two cubes sharing an **edge** only | 2 objects | 1 object |
| Two cubes sharing a **corner** only | 2 objects | 1 object |
| Diagonal voxel chain through z | N objects | 1 object |

**Settled 2026-08-03: both existing paths use 26-connectivity, and they agree.**

- `Utilities.Counter3D.minAntTag` scans the full 3×3 neighbourhood on slice z-1,
  the full previous row on slice z, and the voxel to the left — the 13 anterior
  members of the 26-neighbourhood — and `findObjects` then sweeps the complete
  3×3×3 neighbourhood in its second pass.
- `mcib3d.image3d.ImageLabeller.getLabels(ImageHandler)` passes `false` for its
  `connectivity6` flag, dispatching to `labelSpots26`.

Because they agree, no pre-existing disagreement has to be adjudicated and both
Case A and Case B references can be honoured. 26 is pinned as the default in
`Connectivity`, asserted in `ConnectivityDiscriminatorTest`, and carried on
`LabelResult` so it can be written into CSV headers.

### Geometry and edge cases

- empty image; all-foreground image
- single voxel; single voxel at each of the 8 corners; voxels on every border face
- solid sphere; hollow spherical shell (surface-area sensitive)
- U-shaped object whose arms are separated in one plane but joined in another
- object spanning the full stack depth
- object clipped by the top and bottom slices
- two objects touching only on the last slice

### Bit-depth and count ladder

- 8-bit, 16-bit, 32-bit
- exactly 254 / 255 / 256 objects (`ByteProcessor` → `ShortProcessor` boundary)
- exactly 65,534 / 65,535 / 65,536 objects (`ShortProcessor` → `FloatProcessor`)
- \>16.7M labels is out of scope — document the float32 exactness limit instead

### Calibration

- uncalibrated
- isotropic µm
- anisotropic, z = 5× xy (exercises surface weighting and Feret)
- calibration present on the intensity image but not the label image

### Configuration sweep

For each fixture, sweep: threshold values spanning empty → all-foreground;
`min`/`max` size; edge exclusion on/off; redirect on/off; each extended
measurement group on/off; each map on/off.

### Real data

At least 20 stacks drawn from live projects — Amyloid, Microglia, IHF Pipeline,
Thick Sections — chosen to include the largest stacks routinely processed, so the
memory improvement is measured on real inputs rather than synthetic ones.

---

## 7. Mechanics

```
1. Check out the current build. Record the git SHA.
2. Run the harness over the full corpus × configuration sweep.
   Write every output to golden/<sha>/<fixture>/<config>/.
3. Commit the goldens (synthetic) / archive them (real).
4. Do the migration.
5. Re-run identically into candidate/<sha>/.
6. Diff. Apply the tier contract.
```

The golden set is captured **once, from the pre-migration build**, and is
immutable. If a golden is later found to be wrong, that is a bug report against
the current shipped plugin — fix it as its own change with its own release note,
never by quietly regenerating goldens to make a diff go away.

Suggested location: `src/test/java/sc/fiji/oc3dplus/equivalence/`, with the
generator, the runner, the differ, and the tier definitions as code so the
contract is executable rather than prose.

---

## 8. Performance, recorded alongside correctness

Not a gate, but capture it — it is the headline claim for the release.

For each real stack: peak heap, wall-clock, and whether the run completes at
default Fiji memory settings.

Expected direction (1024×1024×50, ~10% foreground):

| | Peak working set |
|---|---|
| Before | ~985 MB |
| After | ~20 MB |

Include at least one stack that **fails today** with
`NegativeArraySizeException` above 2³¹ voxels and succeeds after. That single
before/after is the most persuasive line in the release notes.

---

## 9. Ship gate

All of the following, in writing, before any jar is published:

- [ ] Connectivity determined empirically, pinned, and recorded in CSV headers
- [ ] Tier 1: **zero** differences across the entire corpus and configuration sweep
- [ ] Tier 2: all columns within pre-declared tolerance; delta table produced and reviewed
- [ ] Tier 3: Feret resolution chosen (accept or match), signed off, CHANGELOG written
- [ ] Object maps identical as partitions; labels dense 1..N
- [ ] `batch_scores.csv` diff empty
- [ ] Extended measurement columns unchanged
- [ ] Headless output byte-identical to GUI output
- [ ] Plus and StarDist agree exactly on the same label image
- [ ] Macro option round-trip unchanged for every option in the README table
- [ ] Performance table captured, including one previously-failing large stack
- [ ] Licence flip applied **in the same commit** that removes the last GPL dependency — not before
