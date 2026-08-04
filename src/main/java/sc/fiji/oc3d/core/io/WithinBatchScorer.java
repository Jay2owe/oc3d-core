package sc.fiji.oc3d.core.io;

import java.util.Arrays;

/**
 * Scores each object against every other object in the same batch.
 *
 * <p>This is what turns "object 47 has a sphericity of 0.31" into "object 47 is
 * in the 4th percentile for sphericity in this experiment", which is the form a
 * biologist can act on. Both scores are computed against the whole batch
 * population, so <b>a single object appearing, disappearing or changing shifts
 * every score in the batch</b>. That sensitivity is the point - it also means a
 * diff in a scores file is never "just one object", and tracing it back to the
 * object that caused it is the only correct response.
 */
public final class WithinBatchScorer {

    private WithinBatchScorer() {
    }

    /** Immutable scoring result, aligned index-for-index with the input array. */
    public static final class Result {
        private final double[] zScores;
        private final double[] percentiles;
        private final int finiteCount;

        private Result(double[] zScores, double[] percentiles, int finiteCount) {
            this.zScores = zScores.clone();
            this.percentiles = percentiles.clone();
            this.finiteCount = finiteCount;
        }

        /** Population z-scores; unavailable entries are {@link Double#NaN}. */
        public double[] zScores() {
            return zScores.clone();
        }

        /** Empirical midrank percentiles; unavailable entries are {@link Double#NaN}. */
        public double[] percentiles() {
            return percentiles.clone();
        }

        /** Size of the reference population, i.e. how many inputs were finite. */
        public int finiteCount() {
            return finiteCount;
        }
    }

    /**
     * Scores each finite input against all finite values in the same array.
     *
     * <p>The z-score uses the <b>population</b> standard deviation (variance
     * denominator {@code N}, not {@code N-1}): the batch is the population being
     * described, not a sample drawn from a larger one, so there is nothing to
     * correct for.
     *
     * <p>The percentile is {@code 100 * (less + 0.5 * equal) / N} - the midrank
     * convention, which puts a value tied with everything else at the 50th
     * percentile rather than the 0th or the 100th.
     *
     * <p>Non-finite inputs are excluded from the reference population and score
     * {@code NaN}; they are not treated as zero, which would drag the mean. Fewer
     * than three finite values makes both scores unavailable, because a
     * percentile over two objects says nothing. A constant population has valid
     * percentiles but unavailable z-scores, since the standard deviation is zero.
     *
     * @param values values to score
     * @return scores aligned with {@code values}
     * @throws IllegalArgumentException if {@code values} is null
     */
    public static Result score(double[] values) {
        if (values == null) {
            throw new IllegalArgumentException("values must not be null (values=null).");
        }

        double[] zScores = nanArray(values.length);
        double[] percentiles = nanArray(values.length);
        int finiteCount = countFinite(values);
        if (finiteCount < 3) {
            return new Result(zScores, percentiles, finiteCount);
        }

        double[] sorted = new double[finiteCount];
        double mean = 0.0;
        int position = 0;
        for (double value : values) {
            if (!Double.isFinite(value)) continue;
            double normalized = normalizeZero(value);
            sorted[position] = normalized;
            position++;
            mean += (normalized - mean) / position;
        }
        Arrays.sort(sorted);

        double sumSquaredDeviations = 0.0;
        for (double value : sorted) {
            double difference = value - mean;
            sumSquaredDeviations += difference * difference;
        }
        double standardDeviation = Math.sqrt(sumSquaredDeviations / finiteCount);
        boolean zScoreAvailable = Double.isFinite(mean)
                && Double.isFinite(standardDeviation)
                && standardDeviation > 0.0;

        for (int i = 0; i < values.length; i++) {
            double value = values[i];
            if (!Double.isFinite(value)) continue;
            double normalized = normalizeZero(value);
            int less = lowerBound(sorted, normalized);
            int atMost = upperBound(sorted, normalized);
            int equal = atMost - less;
            percentiles[i] = 100.0 * (less + 0.5 * equal) / finiteCount;
            if (zScoreAvailable) {
                zScores[i] = (normalized - mean) / standardDeviation;
            }
        }
        return new Result(zScores, percentiles, finiteCount);
    }

    /** Population mean and standard deviation over the finite values only. */
    public static double[] meanAndStandardDeviation(double[] values) {
        if (values == null) return new double[] {Double.NaN, Double.NaN};
        int count = 0;
        double mean = 0.0;
        for (int i = 0; i < values.length; i++) {
            if (!Double.isFinite(values[i])) continue;
            count++;
            mean += (values[i] - mean) / count;
        }
        if (count == 0) return new double[] {Double.NaN, Double.NaN};
        double sumSquares = 0.0;
        for (int i = 0; i < values.length; i++) {
            if (!Double.isFinite(values[i])) continue;
            double difference = values[i] - mean;
            sumSquares += difference * difference;
        }
        return new double[] {mean, Math.sqrt(sumSquares / count)};
    }

    private static int countFinite(double[] values) {
        int count = 0;
        for (double value : values) {
            if (Double.isFinite(value)) count++;
        }
        return count;
    }

    private static double[] nanArray(int length) {
        double[] values = new double[length];
        Arrays.fill(values, Double.NaN);
        return values;
    }

    /**
     * Collapses {@code -0.0} onto {@code 0.0}.
     *
     * <p>They compare equal with {@code ==} but sort apart under
     * {@link Arrays#sort(double[])}, which would put a {@code -0.0} on the wrong
     * side of the tie group and shift its percentile.
     */
    private static double normalizeZero(double value) {
        return value == 0.0 ? 0.0 : value;
    }

    private static int lowerBound(double[] sorted, double target) {
        int low = 0;
        int high = sorted.length;
        while (low < high) {
            int middle = low + (high - low) / 2;
            if (sorted[middle] < target) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low;
    }

    private static int upperBound(double[] sorted, double target) {
        int low = 0;
        int high = sorted.length;
        while (low < high) {
            int middle = low + (high - low) / 2;
            if (sorted[middle] <= target) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low;
    }
}
