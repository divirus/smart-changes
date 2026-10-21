package dev.changestree.smartchanges.model;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Tracks view-only hidden paths, independent of VCS status and staging state. */
public final class HiddenChangesModel {
    private final Set<String> hiddenPaths = new LinkedHashSet<>();

    public HiddenChangesModel(Collection<String> persistedPaths) {
        if (persistedPaths != null) {
            persistedPaths.stream()
                    .filter(path -> path != null && !path.isBlank())
                    .map(HiddenChangesModel::normalizePath)
                    .forEach(hiddenPaths::add);
        }
    }

    public boolean isHidden(ChangedItem item) {
        return hiddenPaths.contains(pathOf(item));
    }

    public List<ChangedItem> inView(Collection<ChangedItem> items, boolean hiddenView) {
        return items.stream()
                .filter(item -> isHidden(item) == hiddenView)
                .toList();
    }

    public boolean setHidden(Collection<ChangedItem> items, boolean hidden) {
        boolean changed = false;
        for (ChangedItem item : items) {
            String path = pathOf(item);
            changed |= hidden ? hiddenPaths.add(path) : hiddenPaths.remove(path);
        }
        return changed;
    }

    /** Forget paths that are no longer changed in the project. */
    public boolean retainCurrentItems(Collection<ChangedItem> items) {
        Set<String> currentPaths = new LinkedHashSet<>();
        for (ChangedItem item : items) {
            currentPaths.add(pathOf(item));
        }
        return hiddenPaths.retainAll(currentPaths);
    }

    public int hiddenFileCount() {
        return hiddenPaths.size();
    }

    public List<String> persistedPaths() {
        return List.copyOf(hiddenPaths);
    }

    private static String pathOf(ChangedItem item) {
        return normalizePath(item.getPath());
    }

    private static String normalizePath(String path) {
        return java.io.File.separatorChar == '\\' ? path.replace('\\', '/') : path;
    }
}
