package dev.changestree.smartchanges.ui;

import dev.changestree.smartchanges.model.ChangedItem;
import dev.changestree.smartchanges.model.SortMode;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class ChangesTableProjector {
    private ChangesTableProjector() {
    }

    static List<Row> project(
            List<ChangedItem> source,
            SortMode sortMode,
            boolean groupingEnabled,
            boolean ascending,
            Set<String> collapsedGroups,
            String projectBasePath
    ) {
        Comparator<ChangedItem> itemComparator = comparator(sortMode, ascending);
        List<ChangedItem> changes = source.stream()
                .filter(item -> !item.isUnversioned())
                .sorted(itemComparator)
                .toList();
        List<ChangedItem> unversioned = source.stream()
                .filter(ChangedItem::isUnversioned)
                .sorted(itemComparator)
                .toList();

        List<Row> rows = new ArrayList<>(source.size() + 4);
        appendSection(rows, changes, false, sortMode, ascending, groupingEnabled, collapsedGroups, projectBasePath,
                itemComparator);
        appendSection(rows, unversioned, true, sortMode, ascending, groupingEnabled, collapsedGroups, projectBasePath,
                itemComparator);
        return rows;
    }

    static Set<String> groupIds(List<ChangedItem> source, boolean groupingEnabled) {
        Set<String> result = new LinkedHashSet<>();
        for (boolean unversioned : List.of(false, true)) {
            List<ChangedItem> sectionItems = source.stream()
                    .filter(item -> item.isUnversioned() == unversioned)
                    .toList();
            if (sectionItems.isEmpty()) {
                continue;
            }
            result.add(sectionId(unversioned));
            if (groupingEnabled) {
                sectionItems.stream()
                        .map(ChangedItem::getExtension)
                        .distinct()
                        .map(extension -> extensionGroupId(unversioned, extension))
                        .forEach(result::add);
            }
        }
        return result;
    }

    static List<ChangedItem> itemsInGroup(List<ChangedItem> source, Row row) {
        if (!row.isGroup()) {
            return List.of();
        }
        return source.stream()
                .filter(item -> item.isUnversioned() == row.unversioned())
                .filter(item -> row.groupKind() != GroupKind.EXTENSION
                        || item.getExtension().equals(row.groupValue()))
                .toList();
    }

    private static void appendSection(
            List<Row> rows,
            List<ChangedItem> items,
            boolean unversioned,
            SortMode sortMode,
            boolean ascending,
            boolean groupingEnabled,
            Set<String> collapsedGroups,
            String projectBasePath,
            Comparator<ChangedItem> itemComparator
    ) {
        if (items.isEmpty()) {
            return;
        }

        String sectionId = sectionId(unversioned);
        boolean sectionCollapsed = collapsedGroups.contains(sectionId);
        rows.add(Row.section(sectionId, unversioned ? "Unversioned Files" : "Changes",
                distinctFileCount(items), sectionCollapsed, unversioned));
        if (sectionCollapsed) {
            return;
        }

        if (!groupingEnabled) {
            appendFiles(rows, items, projectBasePath, 1);
            return;
        }

        Map<String, List<ChangedItem>> groups = new HashMap<>();
        for (ChangedItem item : items) {
            groups.computeIfAbsent(item.getExtension(), ignored -> new ArrayList<>()).add(item);
        }

        List<Map.Entry<String, List<ChangedItem>>> orderedGroups = new ArrayList<>(groups.entrySet());
        orderedGroups.sort((left, right) -> {
            int comparison;
            if (sortMode == SortMode.TYPE) {
                comparison = compareStrings(left.getKey(), right.getKey());
                if (!ascending) {
                    comparison = -comparison;
                }
            } else {
                comparison = itemComparator.compare(left.getValue().getFirst(), right.getValue().getFirst());
            }
            return comparison != 0 ? comparison : compareStrings(left.getKey(), right.getKey());
        });

        for (Map.Entry<String, List<ChangedItem>> group : orderedGroups) {
            List<ChangedItem> groupItems = group.getValue();
            groupItems.sort(itemComparator);
            String extension = group.getKey();
            String groupId = extensionGroupId(unversioned, extension);
            boolean groupCollapsed = collapsedGroups.contains(groupId);
            rows.add(Row.extension(groupId, extension, groupTitle(extension), distinctFileCount(groupItems),
                    groupCollapsed, unversioned));
            if (!groupCollapsed) {
                appendFiles(rows, groupItems, projectBasePath, 2);
            }
        }
    }

    private static void appendFiles(List<Row> rows, List<ChangedItem> items, String projectBasePath, int indentLevel) {
        for (ChangedItem item : items) {
            rows.add(Row.file(item, relativeParentPath(item, projectBasePath), indentLevel));
        }
    }

    private static int distinctFileCount(List<ChangedItem> items) {
        return (int) items.stream().map(ChangedItem::getPath).distinct().count();
    }

    private static String sectionId(boolean unversioned) {
        return unversioned ? "section:unversioned" : "section:changes";
    }

    private static String extensionGroupId(boolean unversioned, String extension) {
        return "extension:" + (unversioned ? "unversioned" : "changes") + ":" + extension;
    }

    private static Comparator<ChangedItem> comparator(SortMode mode, boolean ascending) {
        Comparator<ChangedItem> primary = switch (mode) {
            case NAME -> comparing(ChangedItem::getName);
            case TYPE -> comparing(item -> typeName(item.getExtension()));
            case PATH -> comparing(ChangedItem::getPath);
        };
        Comparator<ChangedItem> tieBreaker = comparing(ChangedItem::getPath)
                .thenComparing(ChangedItem::getName, String.CASE_INSENSITIVE_ORDER);
        return (left, right) -> {
            int comparison = primary.compare(left, right);
            if (comparison != 0) {
                return ascending ? comparison : -comparison;
            }
            return tieBreaker.compare(left, right);
        };
    }

    private static Comparator<ChangedItem> comparing(java.util.function.Function<ChangedItem, String> selector) {
        return Comparator.comparing(selector, String.CASE_INSENSITIVE_ORDER).thenComparing(selector);
    }

    private static String groupTitle(String extension) {
        if (extension.equals("(No extension)")) {
            return "OTHER";
        }
        return typeName(extension).toUpperCase(Locale.ROOT);
    }

    private static String typeName(String extension) {
        if (extension.equals("(No extension)")) {
            return "other";
        }
        return extension.startsWith(".") ? extension.substring(1) : extension;
    }

    private static String relativeParentPath(ChangedItem item, String projectBasePath) {
        String parentPath = item.getParentPath();
        if (projectBasePath == null || projectBasePath.isBlank()) {
            return parentPath;
        }
        try {
            Path base = Path.of(projectBasePath).normalize();
            Path parent = Path.of(parentPath).normalize();
            if (parent.startsWith(base)) {
                String relative = base.relativize(parent).toString().replace(File.separatorChar, '/');
                return relative.isEmpty() ? "." : relative;
            }
        } catch (RuntimeException ignored) {
            // Keep the original path if the host filesystem cannot normalize a VCS path.
        }
        return parentPath;
    }

    private static int compareStrings(String left, String right) {
        int comparison = String.CASE_INSENSITIVE_ORDER.compare(left, right);
        return comparison != 0 ? comparison : left.compareTo(right);
    }

    enum GroupKind {
        CHANGES,
        UNVERSIONED,
        EXTENSION
    }

    record Row(
            ChangedItem item,
            String groupId,
            String groupTitle,
            String groupValue,
            int groupCount,
            String relativePath,
            boolean collapsed,
            GroupKind groupKind,
            boolean unversioned,
            int indentLevel
    ) {
        static Row file(ChangedItem item, String relativePath, int indentLevel) {
            return new Row(item, null, null, null, 0, relativePath, false, null,
                    item.isUnversioned(), indentLevel);
        }

        static Row section(String groupId, String title, int count, boolean collapsed, boolean unversioned) {
            return new Row(null, groupId, title, null, count, "", collapsed,
                    unversioned ? GroupKind.UNVERSIONED : GroupKind.CHANGES, unversioned, 0);
        }

        static Row extension(
                String groupId,
                String extension,
                String title,
                int count,
                boolean collapsed,
                boolean unversioned
        ) {
            return new Row(null, groupId, title, extension, count, "", collapsed,
                    GroupKind.EXTENSION, unversioned, 1);
        }

        boolean isGroup() {
            return groupKind != null;
        }

        String getNameValue() {
            if (isGroup()) {
                return groupTitle + "  " + groupCount + (groupCount == 1 ? " file" : " files");
            }
            return item.getAreaLabel().isEmpty()
                    ? item.getName()
                    : item.getName() + "  ·  " + item.getAreaLabel();
        }

        String getTypeValue() {
            return isGroup() ? "" : typeName(item.getExtension());
        }

        String getPathValue() {
            return isGroup() ? "" : relativePath;
        }
    }
}
