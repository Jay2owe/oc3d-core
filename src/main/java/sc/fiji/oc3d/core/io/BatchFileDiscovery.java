package sc.fiji.oc3d.core.io;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Finds the input files for a folder batch run.
 *
 * <p>Three properties, each of which exists because its absence caused a real
 * problem:
 *
 * <ul>
 *   <li><b>Deterministic order.</b> Results are sorted by path relative to the
 *       input directory, with {@code /} as the separator so a run on Windows and
 *       a run on Linux order identically. Batch outputs carry a
 *       {@code SourceImageIndex}, and within-batch scores are computed against
 *       the whole population - if discovery order varied, two runs over the same
 *       folder would produce different files.</li>
 *   <li><b>The output directory is excluded</b>, along with everything under it.
 *       Writing results into a subfolder of the input folder is the obvious
 *       thing for a user to do, and without this the second run consumes the
 *       first run's output.</li>
 *   <li><b>Symbolic-link directories are not followed</b> and hidden entries are
 *       skipped, so a link back up the tree cannot make discovery loop.</li>
 * </ul>
 */
public final class BatchFileDiscovery {

    /** What a 3D stack is assumed to be, absent anything else. */
    public static final List<String> TIFF_EXTENSIONS =
            Collections.unmodifiableList(Arrays.asList(".tif", ".tiff"));

    private BatchFileDiscovery() {
    }

    /**
     * Discovers {@code .tif} and {@code .tiff} files below an input directory.
     *
     * @see #discover(File, boolean, File, Collection)
     */
    public static List<File> discover(File inputDirectory,
                                      boolean recursive,
                                      File outputDirectory) throws IOException {
        return discover(inputDirectory, recursive, outputDirectory, TIFF_EXTENSIONS);
    }

    /**
     * Discovers files below an input directory.
     *
     * @param inputDirectory  directory to scan
     * @param recursive       whether to descend into subdirectories
     * @param outputDirectory optional directory to exclude with all of its
     *                        descendants; may be {@code null}
     * @param extensions      lower-case suffixes to accept, e.g. {@code ".tif"}.
     *                        Null or empty means {@link #TIFF_EXTENSIONS}
     * @return immutable, deterministically sorted list
     * @throws IOException if the directory tree cannot be read
     * @throws IllegalArgumentException if {@code inputDirectory} is null or is
     *         not a directory, naming the path that was given
     */
    public static List<File> discover(File inputDirectory,
                                      final boolean recursive,
                                      File outputDirectory,
                                      Collection<String> extensions) throws IOException {
        if (inputDirectory == null) {
            throw new IllegalArgumentException("inputDirectory must not be null (inputDirectory=null).");
        }

        final Path root = inputDirectory.toPath().toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException(
                    "inputDirectory is not a directory: " + inputDirectory);
        }
        final Path excluded = outputDirectory == null
                ? null
                : outputDirectory.toPath().toAbsolutePath().normalize();
        final Set<String> suffixes = normalizeExtensions(extensions);
        final List<Path> discovered = new ArrayList<Path>();

        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory,
                                                     BasicFileAttributes attributes) {
                Path normalized = directory.toAbsolutePath().normalize();
                if (excluded != null && normalized.startsWith(excluded)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (!normalized.equals(root) && isHidden(normalized)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (!recursive && !normalized.equals(root)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                Path normalized = file.toAbsolutePath().normalize();
                if (attributes.isRegularFile()
                        && (excluded == null || !normalized.startsWith(excluded))
                        && !isHidden(normalized)
                        && hasExtension(normalized, suffixes)) {
                    discovered.add(normalized);
                }
                return FileVisitResult.CONTINUE;
            }
        });

        Collections.sort(discovered, new Comparator<Path>() {
            @Override
            public int compare(Path left, Path right) {
                return relativeSortKey(root, left).compareTo(relativeSortKey(root, right));
            }
        });

        List<File> files = new ArrayList<File>(discovered.size());
        for (Path path : discovered) {
            files.add(path.toFile());
        }
        return Collections.unmodifiableList(files);
    }

    /**
     * Path of {@code file} relative to {@code root}, with {@code /} separators.
     *
     * <p>The same key discovery sorts on, exposed so batch outputs can record it
     * as {@code SourceRelativePath} and stay comparable across platforms.
     */
    public static String relativePath(File root, File file) {
        if (root == null || file == null) return "";
        Path rootPath = root.toPath().toAbsolutePath().normalize();
        Path filePath = file.toPath().toAbsolutePath().normalize();
        try {
            return relativeSortKey(rootPath, filePath);
        } catch (IllegalArgumentException notRelative) {
            return filePath.toString().replace(File.separatorChar, '/');
        }
    }

    private static Set<String> normalizeExtensions(Collection<String> extensions) {
        Set<String> suffixes = new LinkedHashSet<String>();
        if (extensions != null) {
            for (String extension : extensions) {
                if (extension == null) continue;
                String trimmed = extension.trim().toLowerCase(Locale.ROOT);
                if (trimmed.isEmpty()) continue;
                suffixes.add(trimmed.startsWith(".") ? trimmed : "." + trimmed);
            }
        }
        if (suffixes.isEmpty()) suffixes.addAll(TIFF_EXTENSIONS);
        return suffixes;
    }

    private static boolean hasExtension(Path path, Set<String> suffixes) {
        Path fileName = path == null ? null : path.getFileName();
        if (fileName == null) return false;
        String lower = fileName.toString().toLowerCase(Locale.ROOT);
        for (String suffix : suffixes) {
            if (lower.endsWith(suffix)) return true;
        }
        return false;
    }

    private static boolean isHidden(Path path) {
        Path fileName = path == null ? null : path.getFileName();
        if (fileName != null && fileName.toString().startsWith(".")) return true;
        try {
            return path != null && Files.isHidden(path);
        } catch (IOException unreadableAttribute) {
            return false;
        }
    }

    private static String relativeSortKey(Path root, Path file) {
        String relative = root.relativize(file).normalize().toString();
        return relative.replace(File.separatorChar, '/');
    }
}
