package sc.fiji.oc3d.core.io;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Groups files that belong to the same acquisition by a filename pattern.
 *
 * <p>The batch idea shared by every plugin in the family: the user names one
 * capture group as the one that <em>varies</em> - usually the channel - and
 * every file that agrees on all the other groups belongs to the same run.
 * {@code sampleA_objects_C1.tif} and {@code sampleA_objects_C2.tif} collapse to
 * the key {@code sampleA_objects_*.tif} and are analysed together.
 *
 * <p>Lifted from CPC and Volumetric Colocalization, which had grown two copies
 * with different defects. Both are fixed here rather than one being promoted
 * over the other:
 *
 * <ul>
 *   <li><b>Non-participating capture groups.</b> A pattern like
 *       {@code (.+?)_objects_(?:x(.+))?\.tif} matches a filename without the
 *       optional group taking part, and {@link Matcher#start(int)} then returns
 *       {@code -1}. CPC fed that straight into {@code substring} and threw
 *       {@link StringIndexOutOfBoundsException} before the batch had opened a
 *       single image. Such a file cannot be assigned to a channel, so it is
 *       skipped.
 *   <li><b>Directory cycles.</b> Neither the recursion nor the filesystem
 *       guaranteed termination. A junction or symlink pointing at an ancestor -
 *       ordinary on Windows and in synced folders - made CPC recurse until the
 *       stack ran out. Canonical paths are tracked, so each real directory is
 *       visited once.
 *   <li><b>Re-reading its own output.</b> A batch whose save directory sits
 *       inside the scanned tree can find its own results on a second run.
 *       Excluding by path rather than by folder name means a user's data folder
 *       that happens to be called {@code CPC} is still scanned.
 * </ul>
 *
 * <p>What is deliberately <em>not</em> unified is how files are ordered inside a
 * group, and whether an over-large group is worth reporting. Both are visible in
 * results - order decides which image is channel one - so each caller states
 * what it wants rather than inheriting a sibling's choice.
 */
public final class RegexGroupDiscovery {

    private RegexGroupDiscovery() {
        // Utility class.
    }

    /** Key used when the varying group is outside the pattern's group count. */
    public static final String UNGROUPED_KEY = "all";

    /** How files are ordered within a group. Order decides channel identity. */
    public enum GroupOrder {
        /** {@link File#compareTo}, i.e. case-sensitive on most filesystems. */
        FILENAME,
        /** Case-insensitive by name. */
        FILENAME_IGNORE_CASE
    }

    /**
     * Scans one folder, without recursing.
     *
     * @param folder       directory to scan; a non-directory yields no groups
     * @param pattern      must match the whole filename
     * @param varyingGroup 1-based capture group that varies within a group;
     *                     out of range collapses everything to
     *                     {@link #UNGROUPED_KEY}
     * @param order        ordering within each group
     * @return group key to files, in the order the keys were first seen
     */
    public static Map<String, List<File>> findGroups(File folder, Pattern pattern,
                                                     int varyingGroup, GroupOrder order) {
        Map<String, List<File>> groups = new LinkedHashMap<String, List<File>>();
        if (folder == null || pattern == null) return groups;
        File[] files = folder.listFiles();
        if (files == null) return groups;

        Arrays.sort(files);
        for (File file : files) {
            if (!file.isFile()) continue;
            Matcher matcher = pattern.matcher(file.getName());
            if (!matcher.matches()) continue;

            String key;
            if (varyingGroup >= 1 && varyingGroup <= matcher.groupCount()) {
                int start = matcher.start(varyingGroup);
                int end = matcher.end(varyingGroup);
                // The optional group did not take part. There is no channel to
                // read, so this file cannot join a group.
                if (start < 0 || end < 0) continue;
                key = file.getName().substring(0, start) + "*"
                        + file.getName().substring(end);
            } else {
                key = UNGROUPED_KEY;
            }

            List<File> list = groups.get(key);
            if (list == null) {
                list = new ArrayList<File>();
                groups.put(key, list);
            }
            list.add(file);
        }

        if (order == GroupOrder.FILENAME_IGNORE_CASE) {
            for (List<File> files1 : groups.values()) {
                Collections.sort(files1, new java.util.Comparator<File>() {
                    @Override
                    public int compare(File left, File right) {
                        return left.getName().compareToIgnoreCase(right.getName());
                    }
                });
            }
        }
        return groups;
    }

    /**
     * Scans a folder tree.
     *
     * @param excluded directories to skip, compared by canonical path 2014 pass
     *                 the batch's own save directory so a second run does not
     *                 discover the first run's output
     * @return relative folder path (empty for the root) to that folder's groups;
     *         folders with no matches are absent
     */
    public static Map<String, Map<String, List<File>>> findGroupsRecursive(
            File root, Pattern pattern, int varyingGroup, boolean recursive,
            GroupOrder order, Set<File> excluded) {
        Map<String, Map<String, List<File>>> result =
                new LinkedHashMap<String, Map<String, List<File>>>();
        if (root == null) return result;

        if (!recursive) {
            Map<String, List<File>> groups = findGroups(root, pattern, varyingGroup, order);
            if (!groups.isEmpty()) result.put("", groups);
            return result;
        }

        Set<String> excludedPaths = canonicalPaths(excluded);
        walk(root, "", pattern, varyingGroup, order, result,
                new HashSet<String>(), excludedPaths);
        return result;
    }

    private static void walk(File current, String relativePath, Pattern pattern,
                             int varyingGroup, GroupOrder order,
                             Map<String, Map<String, List<File>>> result,
                             Set<String> visited, Set<String> excludedPaths) {
        String canonical = canonicalPath(current);
        if (excludedPaths.contains(canonical)) return;
        // A junction or symlink back to an ancestor would otherwise recurse
        // until the stack ran out.
        if (!visited.add(canonical)) return;

        Map<String, List<File>> groups = findGroups(current, pattern, varyingGroup, order);
        if (!groups.isEmpty()) result.put(relativePath, groups);

        File[] children = current.listFiles();
        if (children == null) return;
        Arrays.sort(children);
        for (File child : children) {
            if (!child.isDirectory()) continue;
            String childPath = relativePath.isEmpty()
                    ? child.getName()
                    : relativePath + "/" + child.getName();
            walk(child, childPath, pattern, varyingGroup, order, result,
                    visited, excludedPaths);
        }
    }

    private static Set<String> canonicalPaths(Set<File> files) {
        Set<String> paths = new HashSet<String>();
        if (files == null) return paths;
        for (File file : files) {
            if (file != null) paths.add(canonicalPath(file));
        }
        return paths;
    }

    private static String canonicalPath(File file) {
        try {
            return file.getCanonicalPath();
        } catch (IOException e) {
            return file.getAbsolutePath();
        }
    }

    /**
     * Pairs each file with its counterpart in another folder 2014 a label image
     * with the raw image it was segmented from.
     *
     * <p>Matching is on the whole context, not just the channel: every capture
     * group except the varying one has to agree. Two samples imaged on the same
     * channel would otherwise be interchangeable, and a run could silently
     * weight one sample's centroids by another sample's intensities.
     *
     * @return a list parallel to {@code sourceFiles}; an entry is null where no
     *         counterpart was found, which the caller must handle
     */
    public static List<File> matchByContextAndChannel(
            List<File> sourceFiles, Pattern sourcePattern,
            File otherFolder, Pattern otherPattern, int varyingGroup) {
        Map<String, File> lookup = new LinkedHashMap<String, File>();
        File[] candidates = otherFolder == null ? null : otherFolder.listFiles();
        if (candidates != null) {
            for (File candidate : candidates) {
                if (!candidate.isFile()) continue;
                Matcher matcher = otherPattern.matcher(candidate.getName());
                if (!matcher.matches()) continue;
                if (varyingGroup < 1 || varyingGroup > matcher.groupCount()) continue;
                if (matcher.start(varyingGroup) < 0) continue;
                lookup.put(contextKey(matcher, varyingGroup) + "|"
                        + matcher.group(varyingGroup), candidate);
            }
        }

        List<File> matched = new ArrayList<File>();
        for (File sourceFile : sourceFiles) {
            Matcher matcher = sourcePattern.matcher(sourceFile.getName());
            if (!matcher.matches() || varyingGroup < 1
                    || varyingGroup > matcher.groupCount()
                    || matcher.start(varyingGroup) < 0) {
                matched.add(null);
                continue;
            }
            matched.add(lookup.get(contextKey(matcher, varyingGroup) + "|"
                    + matcher.group(varyingGroup)));
        }
        return matched;
    }

    /** Every capture group except the varying one, joined with {@code |}. */
    public static String contextKey(Matcher matcher, int varyingGroup) {
        StringBuilder context = new StringBuilder();
        for (int group = 1; group <= matcher.groupCount(); group++) {
            if (group == varyingGroup) continue;
            if (context.length() > 0) context.append('|');
            context.append(matcher.group(group));
        }
        return context.toString();
    }

    /**
     * A folder-safe name for a group key.
     *
     * <p>{@code *_objects_LH_SCN.tif} becomes {@code objects_LH_SCN}: the
     * wildcard, the extension and any leading or trailing separators go, and
     * runs of underscores collapse. A key that reduces to nothing becomes
     * {@code batch}, because an empty string would put every group's output in
     * the same directory.
     */
    public static String groupDisplayName(String groupKey) {
        String name = groupKey.replace("*", "");
        name = name.replaceAll("^[_\\-./\\\\]+|[_\\-./\\\\]+$", "");
        name = name.replaceAll("[_\\-]{2,}", "_");
        name = name.replaceAll("\\.[^.]+$", "");
        if (name.isEmpty()) name = "batch";
        return name;
    }

    /**
     * The preview a batch dialog shows before anything is opened.
     *
     * @param minimumGroupSize groups smaller than this are marked SKIP and are
     *                         excluded from the runnable count
     */
    public static String preview(Map<String, Map<String, List<File>>> nestedGroups,
                                 int minimumGroupSize) {
        if (nestedGroups.isEmpty()) return "No matching files found.";

        int totalFolders = nestedGroups.size();
        int totalGroups = 0;
        int runnableGroups = 0;
        int totalFiles = 0;
        for (Map<String, List<File>> folder : nestedGroups.values()) {
            totalGroups += folder.size();
            for (List<File> group : folder.values()) {
                totalFiles += group.size();
                if (group.size() >= minimumGroupSize) runnableGroups++;
            }
        }

        StringBuilder preview = new StringBuilder();
        preview.append(totalFolders).append(" folder(s), ")
                .append(totalGroups).append(" group(s), ")
                .append(runnableGroups).append(" runnable, ")
                .append(totalFiles).append(" files\n\n");

        for (Map.Entry<String, Map<String, List<File>>> folderEntry : nestedGroups.entrySet()) {
            String folderPath = folderEntry.getKey();
            Map<String, List<File>> groups = folderEntry.getValue();

            int folderFiles = 0;
            for (List<File> group : groups.values()) folderFiles += group.size();

            preview.append(folderPath.isEmpty() ? "(root)" : folderPath + "/")
                    .append("  (").append(groups.size()).append(" groups, ")
                    .append(folderFiles).append(" files)\n");

            for (Map.Entry<String, List<File>> groupEntry : groups.entrySet()) {
                List<File> files = groupEntry.getValue();
                preview.append("  ").append(groupEntry.getKey())
                        .append("  (").append(files.size())
                        .append(files.size() < minimumGroupSize ? " — SKIP" : "")
                        .append(")\n");
                for (File file : files) {
                    preview.append("    ").append(file.getName()).append('\n');
                }
            }
        }
        return preview.toString();
    }
}
