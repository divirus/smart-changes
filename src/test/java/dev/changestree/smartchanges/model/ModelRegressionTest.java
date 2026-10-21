package dev.changestree.smartchanges.model;

import com.intellij.openapi.vcs.FilePath;
import dev.changestree.smartchanges.settings.SmartChangesSettings;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;

public final class ModelRegressionTest {
    public static void run() {
        ChangedItem file = item("/project/file.ts", false, ChangeArea.NONE, null);
        ChangeInclusionModel inclusion = new ChangeInclusionModel();
        inclusion.setIncluded(List.of(file), false);
        inclusion.retainCurrentItems(List.of(item("/project/file.ts", false, ChangeArea.NONE, null)));
        check(!inclusion.isIncluded(file), "Refresh must retain excluded files");
        inclusion.retainCurrentItems(List.of());
        check(inclusion.isIncluded(file), "A genuinely new change should use the default inclusion");
        ChangedItem staged = item("/project/partial.ts", false, ChangeArea.STAGED, new Object());
        ChangedItem unstaged = item("/project/partial.ts", false, ChangeArea.UNSTAGED, new Object());
        ChangedItem conflict = item("/project/conflict.ts", false, ChangeArea.CONFLICTED, new Object());
        var operations = inclusion.setIncluded(List.of(staged, unstaged, conflict), true);
        check(operations.toStage().equals(List.of(unstaged)), "Only unstaged rows may be staged; conflicts must remain untouched");
        check(inclusion.stateOf(List.of(staged, unstaged)) == ChangeInclusionModel.SelectionState.MIXED,
                "Partial staging must display mixed inclusion");

        HiddenChangesModel hidden = new HiddenChangesModel(List.of());
        hidden.setHidden(List.of(staged), true);
        check(hidden.isHidden(unstaged), "Hiding must apply to both sides of a partially staged file");
        check(inclusion.isIncluded(staged) && !inclusion.isIncluded(unstaged), "Hiding must not change staging");
        if (java.io.File.separatorChar != '\\') {
            ChangedItem literalBackslash = item("/project/a\\b.ts", false, ChangeArea.NONE, null);
            hidden.setHidden(List.of(literalBackslash), true);
            check(!hidden.isHidden(item("/project/a/b.ts", false, ChangeArea.NONE, null)),
                    "Unix backslash filenames must not collide with directories");
        }

        SmartChangesSettings settings = new SmartChangesSettings();
        SmartChangesSettings.State state = new SmartChangesSettings.State();
        state.hiddenFilePaths = Arrays.asList(null, "", " ", "/project/file.ts", "/project/file.ts");
        state.sortMode = "invalid";
        state.groupMode = "NONE";
        settings.loadState(state);
        check(settings.getHiddenFilePaths().equals(List.of("/project/file.ts")), "Invalid workspace paths must be sanitized");
        check(settings.getSortMode() == SortMode.NAME && !settings.isGroupingEnabled(), "Legacy/corrupt settings must load safely");
        System.out.println("PASS: selection retention, partial staging, conflicts, hidden paths, settings migration");
    }

    public static ChangedItem item(String path, boolean unversioned, ChangeArea area, Object node) {
        FilePath filePath = (FilePath) Proxy.newProxyInstance(FilePath.class.getClassLoader(), new Class<?>[]{FilePath.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getPath" -> path;
                    case "getName" -> path.substring(path.lastIndexOf('/') + 1);
                    default -> null;
                });
        return new ChangedItem(null, filePath, null, "Modified", "Default", unversioned, area, null, node, null);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
