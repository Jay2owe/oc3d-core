package sc.fiji.oc3d.core.label;

/**
 * Voxel adjacency rule used when deciding whether two foreground voxels belong
 * to the same object.
 *
 * <p><b>{@link #TWENTY_SIX} is the pinned default for the 3D Objects Counter
 * family.</b> That is not a preference; it is what the two implementations
 * being replaced already do, verified in their shipped bytecode:
 *
 * <ul>
 *   <li>{@code Utilities.Counter3D} (sc.fiji:3D_Objects_Counter 2.0.1) scans the
 *       full 3x3 neighbourhood on slice z-1, the full previous row on slice z,
 *       and the voxel to the left - the 13 anterior members of the 26-
 *       neighbourhood - in {@code minAntTag}, then sweeps the complete 3x3x3
 *       neighbourhood in its second pass.</li>
 *   <li>{@code mcib3d.image3d.ImageLabeller.getLabels(ImageHandler)} passes
 *       {@code false} for its {@code connectivity6} flag, dispatching to
 *       {@code labelSpots26}.</li>
 * </ul>
 *
 * <p>The two agree, so no pre-existing disagreement has to be adjudicated. If
 * they had disagreed, that would have been a bug in the shipped plugin rather
 * than a choice available to this module.
 *
 * <p>Changing this value changes every object count in every output. It is an
 * option because future variants (watershed seeds, thin structures) have a
 * legitimate reason to want 6-connectivity, not because the default is open.
 */
public enum Connectivity {

    /** Face neighbours only: 3 anterior, 6 in total. */
    SIX(6),

    /** Face, edge and corner neighbours: 13 anterior, 26 in total. */
    TWENTY_SIX(26);

    private final int neighbourCount;

    Connectivity(int neighbourCount) {
        this.neighbourCount = neighbourCount;
    }

    /** @return 6 or 26 - the value written into CSV headers and macro options. */
    public int neighbourCount() {
        return neighbourCount;
    }

    /**
     * @param neighbourCount 6 or 26
     * @return the matching constant
     * @throws IllegalArgumentException for any other value, naming what was given
     */
    public static Connectivity fromNeighbourCount(int neighbourCount) {
        if (neighbourCount == 6) return SIX;
        if (neighbourCount == 26) return TWENTY_SIX;
        throw new IllegalArgumentException(
                "connectivity must be 6 or 26 (connectivity=" + neighbourCount + ").");
    }
}
