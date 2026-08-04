package sc.fiji.oc3d.core;

import ij.ImagePlus;
import ij.measure.ResultsTable;
import ij.process.ImageProcessor;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import sc.fiji.oc3d.core.api.MorphPredicate;
import sc.fiji.oc3d.core.api.OC3DParameters;
import sc.fiji.oc3d.core.api.OC3DResult;
import sc.fiji.oc3d.core.api.WarningSink;
import sc.fiji.oc3d.core.image.ImageOps;
import sc.fiji.oc3d.core.io.CsvWriter;
import sc.fiji.oc3d.core.io.SummaryReporter;
import sc.fiji.oc3d.core.label.LabelParameters;
import sc.fiji.oc3d.core.label.LabelRenumberer;
import sc.fiji.oc3d.core.label.LabelResult;
import sc.fiji.oc3d.core.label.StreamingLabelEngine;
import sc.fiji.oc3d.core.label.StreamingLabeller;
import sc.fiji.oc3d.core.macro.MacroFilters;
import sc.fiji.oc3d.core.macro.MacroOptions;
import sc.fiji.oc3d.core.map.ObjectMapBuilder;
import sc.fiji.oc3d.core.measure.LabelFeatureAccumulator;
import sc.fiji.oc3d.core.progress.ProgressListener;
import sc.fiji.oc3d.core.spi.LabelEngine;
import sc.fiji.oc3d.core.testing.Fixtures;
import sc.fiji.oc3d.core.ui.DialogModel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Walks one image through the whole chassis - macro string, engine, measurement,
 * filtering, maps, summary, CSV - and checks the pieces agree with each other.
 *
 * <p>The unit tests each pin one class. This pins the seams between them, which
 * is where an extraction actually goes wrong.
 */
public class PipelineIntegrationTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    /** Three well-separated cubes of 27, 8 and 1 voxels. */
    private ImagePlus threeCubes() {
        ImagePlus image = Fixtures.blank("stack.tif", 20, 20, 12, 8);
        fill(image, 1, 1, 1, 3, 255);   // 27 voxels
        fill(image, 10, 10, 5, 2, 255); // 8 voxels
        fill(image, 16, 2, 9, 1, 255);  // 1 voxel
        return image;
    }

    @Test
    public void labellingFeedsMeasurementWithMatchingCounts() {
        ImagePlus image = threeCubes();

        LabelResult labelled = StreamingLabeller
                .label(image, new LabelParameters().threshold(128), ProgressListener.NONE);

        assertEquals(3, labelled.objectCount());
        assertEquals(27 + 8 + 1, labelled.totalObjectVoxels());

        LabelFeatureAccumulator.Result measured =
                LabelFeatureAccumulator.scan(labelled.labelImage(), image, null);

        assertEquals(labelled.objectCount(), measured.objectCount());
        for (int label = 1; label <= labelled.objectCount(); label++) {
            assertEquals("the labeller and the accumulator must count the same voxels",
                    labelled.voxelCount(label),
                    measured.valuesForLabel(label).voxelCount());
        }
    }

    @Test
    public void theEngineIsReachableThroughTheSpiInterface() {
        LabelEngine engine = StreamingLabelEngine.INSTANCE;
        assertNotNull(engine.name());

        LabelResult result = engine.label(threeCubes(),
                new LabelParameters().threshold(128).minSize(5), null);

        assertEquals("the 1-voxel cube is below minSize", 2, result.objectCount());
    }

    @Test
    public void sizeFilteringHappensInTheEngineAndShowsInTheTable() {
        ImagePlus image = threeCubes();

        LabelResult labelled = StreamingLabeller.label(image,
                new LabelParameters().threshold(128).minSize(9), ProgressListener.NONE);
        ResultsTable table = LabelFeatureAccumulator
                .scan(labelled.labelImage(), image, null)
                .toStatisticsTable();

        assertEquals(1, table.size());
        assertEquals(27.0, table.getValue("Nb of obj. voxels", 0), 0.0);
    }

    @Test
    public void morphFiltersFromAMacroStringSelectTheSameObjectsAsTheDialogModel() {
        ImagePlus image = threeCubes();

        DialogModel model = new DialogModel();
        model.minSize = 1;
        DialogModel.FeatureRange surface = rangeOf(model, "surface_area");
        surface.minText = "20";

        String options = model.toMacroOptions();
        assertTrue(options, options.contains("surface_area>=20"));

        List<MorphPredicate> fromModel = model.enabledPredicates();
        List<MorphPredicate> fromMacro = MacroFilters.parse(options);
        assertEquals(fromModel.size(), fromMacro.size());
        assertEquals(fromModel.get(0).format(), fromMacro.get(0).format());

        assertEquals("the two routes must select the same objects",
                surviving(image, fromModel), surviving(image, fromMacro));
    }

    @Test
    public void filteringRenumberingMeasurementAndMapsAllAgree() throws IOException {
        ImagePlus image = threeCubes();
        OC3DParameters parameters = OC3DParameters.builder()
                .threshold(128)
                .minSize(1)
                .addFilters("volume>=8")
                .warningSink(WarningSink.NONE)
                .build();

        LabelResult labelled = StreamingLabeller
                .label(image, parameters.labelParameters(), ProgressListener.NONE);
        LabelFeatureAccumulator.Result measured =
                LabelFeatureAccumulator.scan(labelled.labelImage(), image, null);

        // Filter, then renumber so the survivors are dense 1..N.
        Set<Integer> keep = new HashSet<Integer>();
        List<String> filterLabels = new ArrayList<String>();
        int[] survivingPerFilter = new int[parameters.morphPredicates().size()];
        for (Integer label : measured.labelsSorted()) {
            boolean passes = true;
            for (MorphPredicate predicate : parameters.morphPredicates()) {
                if (!predicate.matches(
                        measured.valuesForLabel(label.intValue()).feature(predicate.featureName))) {
                    passes = false;
                    break;
                }
            }
            if (passes) keep.add(label);
        }
        for (int i = 0; i < parameters.morphPredicates().size(); i++) {
            filterLabels.add(parameters.morphPredicates().get(i).format());
            survivingPerFilter[i] = keep.size();
        }

        ImagePlus labelImage = labelled.labelImage();
        LabelRenumberer.Result renumbered = LabelRenumberer.renumber(labelImage, keep);
        assertEquals("the 1-voxel cube fails volume>=8", 2, renumbered.objectCount());

        ResultsTable table = LabelFeatureAccumulator.scan(labelImage, image, null)
                .toStatisticsTable();
        OC3DResult result = new OC3DResult(table, labelImage, survivingPerFilter,
                filterLabels.toArray(new String[filterLabels.size()]));

        assertEquals(2, result.objectCount());
        assertTrue(result.foundObjects());
        assertEquals(1, result.survivingPerFilter().length);
        assertEquals(2, result.survivingPerFilter()[0]);
        assertEquals("volume>=8.0", result.filterLabels()[0]);

        // Every surviving row must be findable in the maps.
        ImagePlus objects = ObjectMapBuilder.objectMap(labelImage, table, image.getTitle());
        ImagePlus centroids = ObjectMapBuilder.centroidMap(labelImage, table, image.getTitle());
        assertEquals("Objects map of stack.tif", objects.getTitle());
        assertEquals(2, centroidVoxelCount(centroids));

        // The summary reads from the same table.
        String summary = SummaryReporter.format("stack.tif", null, result, 1, Integer.MAX_VALUE, 128);
        assertTrue(summary, summary.startsWith("stack.tif: 2 objects detected"));
        assertTrue(summary, summary.contains("Size=17.5"));

        // And the CSV holds the same rows.
        File csv = folder.newFile("objects.csv");
        CsvWriter.write(csv, table);
        List<String> lines = Files.readAllLines(csv.toPath(), StandardCharsets.UTF_8);
        assertEquals("header plus one row per surviving object", 3, lines.size());
    }

    @Test
    public void aRedirectImageMeasuresIntensityFromTheOtherChannel() {
        ImagePlus image = threeCubes();
        ImagePlus redirect = Fixtures.constant("dapi.tif", image, 500f);

        LabelResult labelled = StreamingLabeller
                .label(image, new LabelParameters().threshold(128), ProgressListener.NONE);
        ResultsTable table = LabelFeatureAccumulator
                .scan(labelled.labelImage(), redirect, null)
                .toStatisticsTable();

        assertEquals("intensities come from the redirect, not the segmented image",
                500.0, table.getValue("Mean", 0), 1e-9);

        String summary = SummaryReporter.format("stack.tif", "dapi.tif", 3, table,
                1, Integer.MAX_VALUE, 128);
        assertTrue(summary, summary.startsWith("stack.tif redirect to dapi.tif:"));
    }

    @Test
    public void aThresholdedCopyLabelsIdenticallyToAnInPlaceThreshold() {
        ImagePlus image = threeCubes();

        LabelResult direct = StreamingLabeller
                .label(image, new LabelParameters().threshold(128), ProgressListener.NONE);
        LabelResult viaCopy = StreamingLabeller.label(
                ImageOps.thresholdBinaryMaskCopy(image, 128),
                new LabelParameters().threshold(1), ProgressListener.NONE);

        assertEquals(direct.objectCount(), viaCopy.objectCount());
        assertEquals(direct.totalObjectVoxels(), viaCopy.totalObjectVoxels());
    }

    @Test
    public void aMacroStringDrivesTheWholeRun() {
        String options = "threshold=128 min=8 max=Infinity volume>=8 hide_centroids";

        int threshold = MacroOptions.parseIntOption(
                MacroOptions.value(options, "threshold", null), 0, "threshold");
        int minSize = MacroOptions.parseIntOption(
                MacroOptions.value(options, "min", null), 10, "min");
        int maxSize = MacroOptions.parseMaxSize(MacroOptions.value(options, "max", null));
        List<MorphPredicate> filters = MacroFilters.parse(options);

        assertEquals(128, threshold);
        assertEquals(8, minSize);
        assertEquals(Integer.MAX_VALUE, maxSize);
        assertEquals(1, filters.size());
        assertTrue(MacroOptions.hasFlag(options, "hide_centroids"));

        LabelResult labelled = StreamingLabeller.label(threeCubes(),
                new LabelParameters().threshold(threshold).minSize(minSize).maxSize(maxSize),
                ProgressListener.NONE);

        assertEquals("min=8 already removes the 1-voxel cube", 2, labelled.objectCount());
    }

    @Test
    public void anEmptyRunProducesAnEmptyTableAndAZeroSummary() {
        ImagePlus empty = Fixtures.blank("empty.tif", 8, 8, 4, 8);

        LabelResult labelled = StreamingLabeller
                .label(empty, new LabelParameters().threshold(128), ProgressListener.NONE);
        ResultsTable table = LabelFeatureAccumulator.scan(labelled.labelImage(), empty, null)
                .toStatisticsTable();
        OC3DResult result = new OC3DResult(table, labelled.labelImage(), null, null);

        assertEquals(0, labelled.objectCount());
        assertEquals(0, result.objectCount());
        assertTrue(!result.foundObjects());
        assertTrue(SummaryReporter.format("empty.tif", null, result, 1, Integer.MAX_VALUE, 128)
                .contains(": 0 objects detected"));
    }

    private static Set<Integer> surviving(ImagePlus image, List<MorphPredicate> filters) {
        LabelResult labelled = StreamingLabeller
                .label(image, new LabelParameters().threshold(128), ProgressListener.NONE);
        LabelFeatureAccumulator.Result measured =
                LabelFeatureAccumulator.scan(labelled.labelImage(), image, null);
        Set<Integer> keep = new HashSet<Integer>();
        for (Integer label : measured.labelsSorted()) {
            boolean passes = true;
            for (MorphPredicate predicate : filters) {
                if (!predicate.matches(
                        measured.valuesForLabel(label.intValue()).feature(predicate.featureName))) {
                    passes = false;
                    break;
                }
            }
            if (passes) keep.add(label);
        }
        return keep;
    }

    private static DialogModel.FeatureRange rangeOf(DialogModel model, String feature) {
        for (DialogModel.FeatureRange range : model.featureRanges()) {
            if (range.feature.equals(feature)) return range;
        }
        throw new IllegalStateException("no range for " + feature);
    }

    private static int centroidVoxelCount(ImagePlus map) {
        int count = 0;
        for (int z = 1; z <= map.getStackSize(); z++) {
            ImageProcessor processor = map.getStack().getProcessor(z);
            for (int i = 0; i < processor.getPixelCount(); i++) {
                if (processor.getf(i) > 0f) count++;
            }
        }
        return count;
    }

    private static void fill(ImagePlus image, int x0, int y0, int z0, int side, int value) {
        for (int z = z0; z < z0 + side; z++) {
            ImageProcessor processor = image.getStack().getProcessor(z + 1);
            for (int y = y0; y < y0 + side; y++) {
                for (int x = x0; x < x0 + side; x++) {
                    processor.setf(x, y, value);
                }
            }
        }
    }
}
