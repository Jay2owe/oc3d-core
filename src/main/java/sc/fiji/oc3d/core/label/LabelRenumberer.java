package sc.fiji.oc3d.core.label;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ImageProcessor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Renumbers a label image so surviving objects carry contiguous labels
 * {@code 1..N} in a deterministic order.
 *
 * <p><b>Why this exists.</b> A detector's own numbering is an internal detail
 * and it leaks. TrackMate's {@code LabelImgExporter} paints {@code trackId + 1},
 * and track IDs are neither contiguous nor stable: filtering objects out leaves
 * holes, and the same object can carry a different number between two runs of
 * the same image with different parameters. Every 3D object counter numbers
 * objects {@code 1..N}, users join tables to maps on that number, and "object 6"
 * has to mean something. So the detector's numbering is replaced, and a caller
 * that needs to trace a result back to the detector keeps the original in its
 * own column via {@link Result#newToOld()}.
 *
 * <p>{@link sc.fiji.oc3d.core.label.StreamingLabeller} already emits dense
 * {@code 1..N} and does not need this. It is for engines whose output numbering
 * comes from somewhere else - a detector, an imported label image, a watershed.
 *
 * <p><b>The order</b> is first slice of appearance, then centroid Y, then
 * centroid X, then original label as a final tie-break. Reading down the stack
 * and across the image is the order a person would count in, and specifying it
 * exactly is what makes two runs comparable.
 */
public final class LabelRenumberer {

    private LabelRenumberer() {
    }

    /** The outcome of a renumbering pass. */
    public static final class Result {

        private final Map<Integer, Integer> oldToNew;
        private final Map<Integer, Integer> newToOld;
        private final int objectCount;

        Result(Map<Integer, Integer> oldToNew, Map<Integer, Integer> newToOld, int objectCount) {
            this.oldToNew = oldToNew;
            this.newToOld = newToOld;
            this.objectCount = objectCount;
        }

        /** Maps a pre-renumber label to its new {@code 1..N} label. */
        public Map<Integer, Integer> oldToNew() {
            return Collections.unmodifiableMap(oldToNew);
        }

        /** Maps a new {@code 1..N} label back to the label the engine produced. */
        public Map<Integer, Integer> newToOld() {
            return Collections.unmodifiableMap(newToOld);
        }

        /** Number of objects surviving in the renumbered image. */
        public int objectCount() {
            return objectCount;
        }
    }

    /**
     * Renumbers every positive label in the image.
     *
     * @param labelImage label image, modified in place
     * @return the label mapping and the surviving object count
     */
    public static Result renumber(ImagePlus labelImage) {
        return renumber(labelImage, null);
    }

    /**
     * Renumbers the labels in {@code keep} and erases every other object.
     *
     * <p>Erasing rather than renumbering around is the point: after this call
     * the image contains exactly the kept objects, densely numbered, so a
     * downstream measurement pass needs no filter of its own.
     *
     * @param labelImage label image, modified in place. A {@code null} image, or
     *                   one with no stack, yields an empty result rather than
     *                   throwing - a run that detected nothing is not an error
     * @param keep       labels to retain, or {@code null} to keep everything
     * @return the label mapping and the surviving object count
     */
    public static Result renumber(ImagePlus labelImage, Set<Integer> keep) {
        if (labelImage == null || labelImage.getStack() == null) {
            return new Result(new HashMap<Integer, Integer>(), new HashMap<Integer, Integer>(), 0);
        }

        ImageStack stack = labelImage.getStack();
        int nSlices = stack.getSize();
        int width = labelImage.getWidth();

        // Pass 1 - accumulate the ordering key for every surviving label.
        Map<Integer, Accumulator> accumulators = new HashMap<Integer, Accumulator>();
        for (int slice = 1; slice <= nSlices; slice++) {
            ImageProcessor ip = stack.getProcessor(slice);
            if (ip == null) continue;
            int pixels = ip.getPixelCount();
            for (int i = 0; i < pixels; i++) {
                int label = labelOf(ip.getf(i));
                if (label <= 0) continue;
                if (keep != null && !keep.contains(Integer.valueOf(label))) continue;
                Accumulator acc = accumulators.get(Integer.valueOf(label));
                if (acc == null) {
                    acc = new Accumulator(label);
                    accumulators.put(Integer.valueOf(label), acc);
                }
                acc.add(i % width, i / width, slice);
            }
        }

        // Sort by first slice, then centroid Y, then centroid X, then old label.
        List<Accumulator> ordered = new ArrayList<Accumulator>(accumulators.values());
        Collections.sort(ordered, new Comparator<Accumulator>() {
            @Override
            public int compare(Accumulator a, Accumulator b) {
                if (a.firstSlice != b.firstSlice) return a.firstSlice < b.firstSlice ? -1 : 1;
                double ay = a.centroidY();
                double by = b.centroidY();
                if (ay != by) return ay < by ? -1 : 1;
                double ax = a.centroidX();
                double bx = b.centroidX();
                if (ax != bx) return ax < bx ? -1 : 1;
                return Integer.compare(a.label, b.label);
            }
        });

        Map<Integer, Integer> oldToNew = new HashMap<Integer, Integer>();
        Map<Integer, Integer> newToOld = new HashMap<Integer, Integer>();
        int next = 1;
        for (Accumulator acc : ordered) {
            oldToNew.put(Integer.valueOf(acc.label), Integer.valueOf(next));
            newToOld.put(Integer.valueOf(next), Integer.valueOf(acc.label));
            next++;
        }
        int objectCount = next - 1;

        // Pass 2 - rewrite. Anything not mapped becomes background, including a
        // non-integral pixel that pass 1 declined to read as a label: leaving it
        // in place would hand downstream code a value it rounds to a neighbouring
        // object's id, so the output would not be the dense 1..N label image this
        // method promises.
        for (int slice = 1; slice <= nSlices; slice++) {
            ImageProcessor ip = stack.getProcessor(slice);
            if (ip == null) continue;
            int pixels = ip.getPixelCount();
            for (int i = 0; i < pixels; i++) {
                float value = ip.getf(i);
                if (value == 0f) continue;
                int label = labelOf(value);
                Integer mapped = label <= 0 ? null : oldToNew.get(Integer.valueOf(label));
                ip.setf(i, mapped == null ? 0f : mapped.floatValue());
            }
        }
        labelImage.setDisplayRange(0, Math.max(1, objectCount));

        return new Result(oldToNew, newToOld, objectCount);
    }

    /**
     * Reads a label from a pixel value. Label images hold integers, in a 16-bit
     * or 32-bit float representation; anything non-integral means the image is
     * not a label image and is treated as background rather than silently
     * rounded into a neighbouring object's identity.
     */
    private static int labelOf(float value) {
        if (!(value > 0f) || !Float.isFinite(value)) return 0;
        int rounded = Math.round(value);
        return Math.abs(value - rounded) < 1e-4f ? rounded : 0;
    }

    private static final class Accumulator {
        private final int label;
        private long count;
        private double sumX;
        private double sumY;
        private int firstSlice = Integer.MAX_VALUE;

        Accumulator(int label) {
            this.label = label;
        }

        void add(int x, int y, int slice) {
            count++;
            sumX += x;
            sumY += y;
            if (slice < firstSlice) firstSlice = slice;
        }

        double centroidX() {
            return count == 0 ? 0 : sumX / count;
        }

        double centroidY() {
            return count == 0 ? 0 : sumY / count;
        }
    }
}
