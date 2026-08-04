package sc.fiji.oc3d.core.io;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class BatchFileDiscoveryTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void findsTiffsAndIgnoresEverythingElse() throws IOException {
        File root = folder.newFolder("in");
        touch(root, "a.tif");
        touch(root, "b.tiff");
        touch(root, "c.TIF");
        touch(root, "notes.txt");
        touch(root, "image.png");

        List<String> names = namesOf(BatchFileDiscovery.discover(root, false, null));

        assertEquals(Arrays.asList("a.tif", "b.tiff", "c.TIF"), names);
    }

    @Test
    public void orderIsDeterministicAndSlashNormalised() throws IOException {
        File root = folder.newFolder("in");
        File sub = new File(root, "sub");
        assertTrue(sub.mkdirs());
        touch(root, "b.tif");
        touch(sub, "a.tif");
        touch(root, "a.tif");

        List<File> first = BatchFileDiscovery.discover(root, true, null);
        List<File> second = BatchFileDiscovery.discover(root, true, null);

        assertEquals(namesOf(first), namesOf(second));
        // "a.tif" < "b.tif" < "sub/a.tif" as relative paths.
        assertEquals(Arrays.asList("a.tif", "b.tif", "a.tif"), namesOf(first));
        assertEquals("sub/a.tif", BatchFileDiscovery.relativePath(root, first.get(2)));
    }

    @Test
    public void nonRecursiveStopsAtTheTopLevel() throws IOException {
        File root = folder.newFolder("in");
        File sub = new File(root, "sub");
        assertTrue(sub.mkdirs());
        touch(root, "top.tif");
        touch(sub, "nested.tif");

        assertEquals(Arrays.asList("top.tif"), namesOf(BatchFileDiscovery.discover(root, false, null)));
        // Sorted by relative path, so "sub/nested.tif" precedes "top.tif".
        assertEquals(Arrays.asList("nested.tif", "top.tif"),
                namesOf(BatchFileDiscovery.discover(root, true, null)));
    }

    @Test
    public void theOutputDirectoryAndItsDescendantsAreExcluded() throws IOException {
        File root = folder.newFolder("in");
        File out = new File(root, "results");
        File outNested = new File(out, "deep");
        assertTrue(outNested.mkdirs());
        touch(root, "source.tif");
        touch(out, "previous.tif");
        touch(outNested, "older.tif");

        List<String> names = namesOf(BatchFileDiscovery.discover(root, true, out));

        assertEquals("a second run must not consume the first run's output",
                Arrays.asList("source.tif"), names);
    }

    @Test
    public void hiddenEntriesAreSkipped() throws IOException {
        File root = folder.newFolder("in");
        File hiddenDir = new File(root, ".cache");
        assertTrue(hiddenDir.mkdirs());
        touch(root, "visible.tif");
        touch(root, ".hidden.tif");
        touch(hiddenDir, "cached.tif");

        assertEquals(Arrays.asList("visible.tif"),
                namesOf(BatchFileDiscovery.discover(root, true, null)));
    }

    @Test
    public void customExtensionsAreAcceptedWithOrWithoutTheDot() throws IOException {
        File root = folder.newFolder("in");
        touch(root, "a.nd2");
        touch(root, "b.lif");
        touch(root, "c.tif");

        List<String> names = namesOf(
                BatchFileDiscovery.discover(root, false, null, Arrays.asList("nd2", ".LIF")));

        assertEquals(Arrays.asList("a.nd2", "b.lif"), names);
    }

    @Test
    public void emptyExtensionListFallsBackToTiff() throws IOException {
        File root = folder.newFolder("in");
        touch(root, "a.tif");
        touch(root, "b.nd2");

        assertEquals(Arrays.asList("a.tif"),
                namesOf(BatchFileDiscovery.discover(root, false, null, new ArrayList<String>())));
    }

    @Test
    public void theResultIsImmutable() throws IOException {
        File root = folder.newFolder("in");
        touch(root, "a.tif");
        List<File> files = BatchFileDiscovery.discover(root, false, null);
        try {
            files.add(new File("x.tif"));
            fail("expected UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
            assertEquals(1, files.size());
        }
    }

    @Test
    public void nullAndNonDirectoryInputsAreRejected() throws IOException {
        try {
            BatchFileDiscovery.discover(null, false, null);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("inputDirectory"));
        }

        File file = folder.newFile("plain.tif");
        try {
            BatchFileDiscovery.discover(file, false, null);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("not a directory"));
        }
    }

    @Test
    public void relativePathToleratesUnrelatedRoots() {
        String path = BatchFileDiscovery.relativePath(new File("."), null);
        assertEquals("", path);
    }

    private static void touch(File directory, String name) throws IOException {
        File file = new File(directory, name);
        if (!file.createNewFile() && !file.isFile()) {
            throw new IOException("could not create " + file);
        }
    }

    private static List<String> namesOf(List<File> files) {
        List<String> names = new ArrayList<String>();
        for (File file : files) {
            names.add(file.getName());
        }
        return names;
    }
}
