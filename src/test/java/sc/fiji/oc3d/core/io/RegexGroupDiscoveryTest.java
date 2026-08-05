package sc.fiji.oc3d.core.io;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class RegexGroupDiscoveryTest {

    private static final Pattern CHANNELS = Pattern.compile("(.+?)_objects_(.+)\\.tif");

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void groupsFilesThatAgreeOnEverythingButTheVaryingGroup() throws IOException {
        File folder = temp.newFolder("flat");
        touch(folder, "sampleA_objects_C1.tif");
        touch(folder, "sampleA_objects_C2.tif");
        touch(folder, "sampleB_objects_C1.tif");

        Map<String, List<File>> groups = RegexGroupDiscovery.findGroups(
                folder, CHANNELS, 2, RegexGroupDiscovery.GroupOrder.FILENAME);

        assertEquals(Arrays.asList("sampleA_objects_*.tif", "sampleB_objects_*.tif"),
                new java.util.ArrayList<String>(groups.keySet()));
        assertEquals(2, groups.get("sampleA_objects_*.tif").size());
        assertEquals(1, groups.get("sampleB_objects_*.tif").size());
    }

    @Test
    public void varyingGroupOutsideThePatternCollapsesToOneGroup() throws IOException {
        File folder = temp.newFolder("ungrouped");
        touch(folder, "sampleA_objects_C1.tif");
        touch(folder, "sampleB_objects_C2.tif");

        Map<String, List<File>> groups = RegexGroupDiscovery.findGroups(
                folder, CHANNELS, 9, RegexGroupDiscovery.GroupOrder.FILENAME);

        assertEquals(Collections.singleton(RegexGroupDiscovery.UNGROUPED_KEY), groups.keySet());
        assertEquals(2, groups.get(RegexGroupDiscovery.UNGROUPED_KEY).size());
    }

    /**
     * The defect this class was promoted to fix. CPC threw
     * {@code StringIndexOutOfBoundsException} here, before opening any image.
     */
    @Test
    public void skipsFilesWhereTheVaryingGroupDidNotParticipate() throws IOException {
        File folder = temp.newFolder("optional");
        touch(folder, "opt_objects_.tif");
        touch(folder, "opt_objects_xC1.tif");
        touch(folder, "opt_objects_xC2.tif");

        Map<String, List<File>> groups = RegexGroupDiscovery.findGroups(folder,
                Pattern.compile("(.+?)_objects_(?:x(.+))?\\.tif"), 2,
                RegexGroupDiscovery.GroupOrder.FILENAME);

        assertEquals(Collections.singleton("opt_objects_x*.tif"), groups.keySet());
        assertEquals(2, groups.get("opt_objects_x*.tif").size());
    }

    @Test
    public void recursionRecordsRelativeFolderPaths() throws IOException {
        File root = temp.newFolder("tree");
        File nested = new File(root, "plate2");
        assertTrue(nested.mkdirs());
        touch(root, "sampleA_objects_C1.tif");
        touch(root, "sampleA_objects_C2.tif");
        touch(nested, "sampleC_objects_C1.tif");
        touch(nested, "sampleC_objects_C2.tif");

        Map<String, Map<String, List<File>>> found = RegexGroupDiscovery.findGroupsRecursive(
                root, CHANNELS, 2, true, RegexGroupDiscovery.GroupOrder.FILENAME, null);

        assertEquals(Arrays.asList("", "plate2"), new java.util.ArrayList<String>(found.keySet()));
    }

    @Test
    public void excludedDirectoriesAreNotScanned() throws IOException {
        File root = temp.newFolder("with-output");
        File output = new File(root, "CPC");
        assertTrue(output.mkdirs());
        touch(root, "sampleA_objects_C1.tif");
        touch(root, "sampleA_objects_C2.tif");
        touch(output, "sampleA_objects_C1.tif");
        touch(output, "sampleA_objects_C2.tif");

        Map<String, Map<String, List<File>>> found = RegexGroupDiscovery.findGroupsRecursive(
                root, CHANNELS, 2, true, RegexGroupDiscovery.GroupOrder.FILENAME,
                new HashSet<File>(Collections.singletonList(output)));

        assertEquals(Collections.singleton(""), found.keySet());
    }

    @Test
    public void nonRecursiveScanIgnoresSubfolders() throws IOException {
        File root = temp.newFolder("shallow");
        File nested = new File(root, "plate2");
        assertTrue(nested.mkdirs());
        touch(nested, "sampleC_objects_C1.tif");

        Map<String, Map<String, List<File>>> found = RegexGroupDiscovery.findGroupsRecursive(
                root, CHANNELS, 2, false, RegexGroupDiscovery.GroupOrder.FILENAME, null);

        assertTrue(found.isEmpty());
    }

    /**
     * {@code FILENAME_IGNORE_CASE} is the same everywhere; {@code FILENAME} is
     * not.
     * <p>
     * {@link File#compareTo} is defined to follow the platform: case-sensitive
     * on Unix, case-insensitive on Windows. A group of {@code _a} and {@code _B}
     * therefore assigns channel one differently on a Mac and on a lab PC, which
     * is worth knowing about but is not something to change quietly — it decides
     * which image the results call channel one. A caller that needs one answer
     * on every machine asks for {@code FILENAME_IGNORE_CASE}.
     */
    @Test
    public void caseInsensitiveOrderingIsStableAcrossPlatforms() throws IOException {
        File folder = temp.newFolder("mixed-case");
        touch(folder, "sample_objects_a.tif");
        touch(folder, "sample_objects_B.tif");

        List<File> caseInsensitive = RegexGroupDiscovery.findGroups(
                folder, CHANNELS, 2, RegexGroupDiscovery.GroupOrder.FILENAME_IGNORE_CASE)
                .get("sample_objects_*.tif");

        assertEquals("sample_objects_a.tif", caseInsensitive.get(0).getName());
        assertEquals("sample_objects_B.tif", caseInsensitive.get(1).getName());

        List<File> platform = RegexGroupDiscovery.findGroups(
                folder, CHANNELS, 2, RegexGroupDiscovery.GroupOrder.FILENAME)
                .get("sample_objects_*.tif");
        assertEquals(2, platform.size());
    }

    @Test
    public void rawImagesMatchOnContextAsWellAsChannel() throws IOException {
        File labels = temp.newFolder("labels");
        File raws = temp.newFolder("raws");
        touch(labels, "sampleA_objects_C1.tif");
        touch(labels, "sampleB_objects_C1.tif");
        touch(raws, "sampleA_raw_C1.tif");

        List<File> labelFiles = Arrays.asList(
                new File(labels, "sampleA_objects_C1.tif"),
                new File(labels, "sampleB_objects_C1.tif"));

        List<File> matched = RegexGroupDiscovery.matchByContextAndChannel(
                labelFiles, CHANNELS, raws, Pattern.compile("(.+?)_raw_(.+)\\.tif"), 2);

        assertEquals("sampleA_raw_C1.tif", matched.get(0).getName());
        // sampleB has the same channel but no raw of its own, and must not
        // silently borrow sampleA's.
        assertNull(matched.get(1));
    }

    @Test
    public void groupDisplayNameStripsWildcardExtensionAndSeparators() {
        assertEquals("objects_LH_SCN",
                RegexGroupDiscovery.groupDisplayName("*_objects_LH_SCN.tif"));
        // Underscore runs collapse, but the separator strip runs before the
        // extension is removed, so a trailing underscore can survive.
        assertEquals("sample_", RegexGroupDiscovery.groupDisplayName("sample__*.tif"));
        // "*.tif" strips to ".tif", then the leading dot goes as a separator,
        // leaving "tif" with no extension left to remove. Odd, but it is what
        // CPC has always written on disk, and folder names are user-visible.
        assertEquals("tif", RegexGroupDiscovery.groupDisplayName("*.tif"));
        assertEquals("batch", RegexGroupDiscovery.groupDisplayName("*"));
    }

    @Test
    public void previewCountsRunnableGroupsAgainstTheStatedMinimum() throws IOException {
        File folder = temp.newFolder("preview");
        touch(folder, "sampleA_objects_C1.tif");
        touch(folder, "sampleA_objects_C2.tif");
        touch(folder, "lonely_objects_C1.tif");

        Map<String, Map<String, List<File>>> found = RegexGroupDiscovery.findGroupsRecursive(
                folder, CHANNELS, 2, false, RegexGroupDiscovery.GroupOrder.FILENAME, null);
        String preview = RegexGroupDiscovery.preview(found, 2);

        assertTrue(preview, preview.startsWith("1 folder(s), 2 group(s), 1 runnable, 3 files"));
        assertTrue(preview, preview.contains("SKIP"));
    }

    @Test
    public void emptyDiscoverySaysSo() {
        assertEquals("No matching files found.",
                RegexGroupDiscovery.preview(
                        Collections.<String, Map<String, List<File>>>emptyMap(), 2));
    }

    private static void touch(File folder, String name) throws IOException {
        assertTrue(new File(folder, name).createNewFile());
    }
}
