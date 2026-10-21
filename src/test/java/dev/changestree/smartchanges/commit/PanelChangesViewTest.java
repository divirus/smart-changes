package dev.changestree.smartchanges.commit;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.InclusionModel;
import kotlinx.coroutines.CoroutineScopeKt;
import kotlin.coroutines.EmptyCoroutineContext;

import java.lang.reflect.Proxy;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class PanelChangesViewTest {
    static void run() {
        Project project = (Project) Proxy.newProxyInstance(Project.class.getClassLoader(), new Class<?>[]{Project.class},
                (proxy, method, args) -> null);
        PanelChangesView view = new PanelChangesView(project, CoroutineScopeKt.CoroutineScope(EmptyCoroutineContext.INSTANCE));
        Set<Object> selected = new LinkedHashSet<>();
        InclusionModel model = (InclusionModel) Proxy.newProxyInstance(InclusionModel.class.getClassLoader(),
                new Class<?>[]{InclusionModel.class}, (proxy, method, args) ->
                        method.getName().equals("getInclusion") ? selected : null);
        FilePath unversioned = (FilePath) Proxy.newProxyInstance(FilePath.class.getClassLoader(), new Class<?>[]{FilePath.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "getPath" -> "/project/file.ts";
                    default -> null;
                });
        com.intellij.openapi.vcs.changes.ContentRevision revision =
                (com.intellij.openapi.vcs.changes.ContentRevision) Proxy.newProxyInstance(
                        Change.class.getClassLoader(), new Class<?>[]{com.intellij.openapi.vcs.changes.ContentRevision.class},
                        (proxy, method, args) -> switch (method.getName()) {
                            case "getFile" -> unversioned;
                            case "getContent" -> "content";
                            case "getRevisionNumber" -> com.intellij.openapi.vcs.history.VcsRevisionNumber.NULL;
                            default -> null;
                        });
        Change tracked = new Change(null, revision);
        // No native tree is created or populated. The workflow still sees exactly the selection.
        view.setInclusionModel(model);
        selected.add(tracked);
        selected.add(unversioned);
        check(view.getIncludedChanges().equals(List.of(tracked)), "Selected tracked change was lost");
        check(view.getIncludedUnversionedFiles().equals(List.of(unversioned)), "Selected new file was lost");
        selected.remove(tracked);
        check(view.getIncludedChanges().isEmpty(), "Excluded change remains included");
        check(view.getIncludedUnversionedFiles().size() == 1, "Excluding a tracked file affected the new file");
        view.setInclusionModel(null); // The native handler clears its model during disposal.
        check(view.getIncludedChanges().isEmpty(), "Detached model still exposes included changes");
        view.setInclusionModel(model);
        selected.clear();
        check(view.getIncludedUnversionedFiles().isEmpty(), "Cleared inclusion retains new files");
        System.out.println("PASS: commit inclusion without a native tree, tracked/new file separation, exclusion updates");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
