package sc.fiji.oc3d.core.io;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class WithinBatchScorerTest {

    private static final double EPS = 1e-9;

    @Test
    public void zScoresUseThePopulationStandardDeviation() {
        // {1,3,5}: mean 3, sum of squared deviations 8.
        // Population SD is sqrt(8/3) = 1.6330, not the sample SD sqrt(8/2) = 2.
        double[] values = {1, 3, 5};
        double populationSd = Math.sqrt(8.0 / 3.0);
        WithinBatchScorer.Result result = WithinBatchScorer.score(values);

        double[] z = result.zScores();
        assertEquals(3, result.finiteCount());
        assertEquals(-2.0 / populationSd, z[0], 1e-12);
        assertEquals(0.0, z[1], 1e-12);
        assertEquals(2.0 / populationSd, z[2], 1e-12);
        assertTrue("the sample-SD answer would be exactly -1.0",
                Math.abs(z[0] + 1.0) > 1e-6);
    }

    @Test
    public void percentilesUseTheMidrankConvention() {
        double[] values = {10, 20, 30, 40};
        double[] percentiles = WithinBatchScorer.score(values).percentiles();

        assertEquals(100.0 * 0.5 / 4, percentiles[0], EPS);
        assertEquals(100.0 * 1.5 / 4, percentiles[1], EPS);
        assertEquals(100.0 * 2.5 / 4, percentiles[2], EPS);
        assertEquals(100.0 * 3.5 / 4, percentiles[3], EPS);
    }

    @Test
    public void tiedValuesShareTheirMidrank() {
        double[] values = {5, 5, 5, 5};
        double[] percentiles = WithinBatchScorer.score(values).percentiles();
        for (int i = 0; i < percentiles.length; i++) {
            assertEquals("a value tied with everything sits at the middle",
                    50.0, percentiles[i], EPS);
        }
    }

    @Test
    public void aConstantPopulationHasPercentilesButNoZScores() {
        WithinBatchScorer.Result result = WithinBatchScorer.score(new double[] {7, 7, 7});
        for (double z : result.zScores()) {
            assertTrue("a zero standard deviation cannot produce a z-score", Double.isNaN(z));
        }
        for (double p : result.percentiles()) {
            assertEquals(50.0, p, EPS);
        }
    }

    @Test
    public void fewerThanThreeFiniteValuesScoresNothing() {
        WithinBatchScorer.Result two = WithinBatchScorer.score(new double[] {1, 2});
        assertEquals(2, two.finiteCount());
        assertTrue(Double.isNaN(two.zScores()[0]));
        assertTrue(Double.isNaN(two.percentiles()[0]));

        WithinBatchScorer.Result empty = WithinBatchScorer.score(new double[0]);
        assertEquals(0, empty.finiteCount());
        assertEquals(0, empty.zScores().length);
    }

    @Test
    public void nonFiniteValuesAreExcludedFromTheReferencePopulation() {
        double[] withNaN = {1, 3, 5, Double.NaN, Double.POSITIVE_INFINITY};
        WithinBatchScorer.Result result = WithinBatchScorer.score(withNaN);

        assertEquals("only the three finite values form the population", 3, result.finiteCount());
        assertEquals(-2.0 / Math.sqrt(8.0 / 3.0), result.zScores()[0], 1e-12);
        assertTrue(Double.isNaN(result.zScores()[3]));
        assertTrue(Double.isNaN(result.percentiles()[4]));
    }

    @Test
    public void negativeZeroTiesWithPositiveZero() {
        double[] values = {-0.0, 0.0, 0.0, 5.0};
        double[] percentiles = WithinBatchScorer.score(values).percentiles();
        assertEquals(percentiles[1], percentiles[0], EPS);
        assertEquals(percentiles[2], percentiles[0], EPS);
    }

    @Test
    public void resultArraysAreDefensiveCopies() {
        WithinBatchScorer.Result result = WithinBatchScorer.score(new double[] {1, 2, 3});
        double[] first = result.zScores();
        first[0] = 999;
        assertEquals("mutating a returned array must not change the result",
                result.zScores()[0], WithinBatchScorer.score(new double[] {1, 2, 3}).zScores()[0], EPS);
    }

    @Test
    public void meanAndStandardDeviationSkipNonFiniteValues() {
        double[] stats = WithinBatchScorer.meanAndStandardDeviation(
                new double[] {1, 3, 5, Double.NaN});
        assertEquals(3.0, stats[0], 1e-12);
        assertEquals(Math.sqrt(8.0 / 3.0), stats[1], 1e-12);

        double[] none = WithinBatchScorer.meanAndStandardDeviation(new double[] {Double.NaN});
        assertTrue(Double.isNaN(none[0]));
        assertTrue(Double.isNaN(none[1]));
        assertTrue(Double.isNaN(WithinBatchScorer.meanAndStandardDeviation(null)[0]));
    }

    @Test
    public void nullInputIsRejected() {
        try {
            WithinBatchScorer.score(null);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("values"));
        }
    }
}
