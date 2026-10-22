package dev.changestree.smartchanges.settings;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.components.StoragePathMacros;
import dev.changestree.smartchanges.model.SortMode;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

@Service(Service.Level.PROJECT)
@State(name = "SmartChangesSettings", storages = @Storage(StoragePathMacros.WORKSPACE_FILE))
public final class SmartChangesSettings implements PersistentStateComponent<SmartChangesSettings.State> {
    public static final class State {
        public List<String> hiddenColumns = new ArrayList<>();
        public String sortMode = SortMode.NAME.name();
        // Retained so existing workspace settings from older builds deserialize cleanly.
        public String groupMode = "EXTENSION";
        public boolean ascending = true;
        public boolean groupingEnabled = true;
        public List<String> hiddenFilePaths = new ArrayList<>();
    }

    private State state = new State();

    @Override
    public @NotNull State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State state) {
        if (state.hiddenFilePaths == null) {
            state.hiddenFilePaths = new ArrayList<>();
        }
        if ("NONE".equals(state.groupMode)) {
            state.groupMode = "EXTENSION";
            state.groupingEnabled = false;
        }
        state.hiddenFilePaths = state.hiddenFilePaths.stream()
                .filter(path -> path != null && !path.isBlank()).distinct()
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        if (state.hiddenColumns == null) state.hiddenColumns = new ArrayList<>();
        this.state = state;
    }

    public boolean isColumnVisible(SortMode mode) {
        return mode == SortMode.NAME || !state.hiddenColumns.contains(mode.name());
    }

    public void setColumnVisible(SortMode mode, boolean visible) {
        state.hiddenColumns.removeIf(mode.name()::equals);
        if (!visible && mode != SortMode.NAME) state.hiddenColumns.add(mode.name());
    }

    public SortMode getSortMode() {
        return enumOrDefault(state.sortMode, SortMode.NAME, SortMode.class);
    }

    public void setSortMode(SortMode mode) {
        state.sortMode = mode.name();
    }

    public boolean isGroupingEnabled() {
        return state.groupingEnabled;
    }

    public void setGroupingEnabled(boolean enabled) {
        state.groupingEnabled = enabled;
    }

    public boolean isAscending() {
        return state.ascending;
    }

    public void setAscending(boolean ascending) {
        state.ascending = ascending;
    }

    public List<String> getHiddenFilePaths() {
        return List.copyOf(state.hiddenFilePaths);
    }

    public void setHiddenFilePaths(List<String> paths) {
        state.hiddenFilePaths = new ArrayList<>(paths);
    }

    private static <T extends Enum<T>> T enumOrDefault(String value, T fallback, Class<T> type) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException | NullPointerException ignored) {
            return fallback;
        }
    }
}
