package dev.changestree.smartchanges.ui;

import dev.changestree.smartchanges.model.*;
import java.util.List;
import java.util.Set;
import static dev.changestree.smartchanges.model.ModelRegressionTest.item;

public final class ProjectorRegressionTest {
    public static void run() {
        var staged = item("/project/a.ts", false, ChangeArea.STAGED, new Object());
        var unstaged = item("/project/a.ts", false, ChangeArea.UNSTAGED, new Object());
        var other = item("/project/z.json", false, ChangeArea.NONE, null);
        var fresh = item("/project/new.ts", true, ChangeArea.NONE, null);
        var source = List.of(other, staged, unstaged, fresh);
        var rows = ChangesTableProjector.project(source, SortMode.NAME, true, true, Set.of(), "/project");
        var section = rows.getFirst();
        check(section.groupCount() == 2, "A partially staged file must count once");
        var extension = rows.stream().filter(r -> r.groupKind() == ChangesTableProjector.GroupKind.EXTENSION).findFirst().orElseThrow();
        check(ChangesTableProjector.itemsInGroup(source, extension).size() == 2, "Extension inclusion must cover both staging rows, without new files");
        var ids = ChangesTableProjector.groupIds(source, true);
        var collapsed = ChangesTableProjector.project(source, SortMode.NAME, true, true, ids, "/project");
        check(collapsed.size() == 2 && collapsed.stream().allMatch(ChangesTableProjector.Row::isGroup), "Collapse all must hide every file");
        var descending = ChangesTableProjector.project(source, SortMode.NAME, false, false, Set.of(), "/project");
        check(descending.get(1).item() == other, "Descending sort must order files inside the section");
        check(descending.stream().filter(r -> !r.isGroup()).allMatch(r -> r.relativePath().equals(".")), "Root-level paths must stay relative");
        System.out.println("PASS: grouping, unique file counts, collapse, sorting, relative paths");
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
