# Changelog

## [0.2.1] - 2026-09-30

Faster centroids, with every output unchanged bit for bit. No public class,
method or result changed; a plugin built against 0.2.0 compiles and runs
unchanged.

### Performance

- **`CentroidScan` without boxing each voxel's label.** Every labelled voxel
  was looked up in a hash map under a boxed key. The scan now keeps the last
  object's totals at hand, holds labels below 65,536 in a plain array, and
  reads 8- and 16-bit in-memory stacks straight from their pixel arrays (32-bit
  labels go through the same float-to-label rule as before; virtual stacks and
  other types still use `getProcessor`). Each object's sums are added in the
  same voxel order, and centroids are still returned in ascending label order.

Measured through Object Colocalization Suite's release benchmark (median of 3;
the machine was already fully loaded by other work, so single-processor runs
are the steadier figure):

| Case | Setting | 0.2.0 | 0.2.1 | Factor |
|---|---|---|---|---|
| A: Quick look + 200-shuffle chance test, 256 x 256 x 8 | one processor | 5.9 s | 3.6 s | 1.6x |
| B: object methods + 50-shuffle chance test, 4 channels, 512 x 512 x 13 | one processor | 67.1 s | 48.3 s | 1.4x |
| A, with volcoloc-core 0.2.0 already in | one processor | 2.2 s | 1.5 s | 1.5x |
| B, with volcoloc-core 0.2.0 already in | one processor | 25.5 s | 16.0 s | 1.6x |

Once volcoloc-core 0.2.0 has removed its own per-voxel lookups, the centroid
scan is a larger share of what is left, so the two together give more than
either alone: 4.2x on case B with one processor.

**Evidence that nothing moved:** the 0.2.0 scan is kept in the test sources as
`ReferenceCentroidScan`, and `CentroidScanEquivalenceTest` compares the two
over 400 random 8-, 16- and 32-bit label stacks with and without intensity
images, and over edge values (negative zero, fractions either side of a half,
NaN, infinities, 65,535 to 65,536, labels beyond the integer range, empty and
single-label images). cpc-core's 35 tests pass against this build, and every
Object Colocalization Suite benchmark case's full output has the same SHA-256
as on 0.2.0.

## [0.2.0] - 2026-08-14

Shared composite-shape inputs, corrected bounded Feret directions, object-map
display fixes and red object-number overlays. See the GitHub release.

## [0.1.1] - 2026-08-06

Maintenance release on the 0.1.x measurement contract: object maps whose
low-numbered labels appeared black or on one Z slice beside a high label are
fixed; label IDs and every measurement are unchanged.

## [0.1.0] - 2026-08-06

First release of the shared chassis.
