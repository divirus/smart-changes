package dev.changestree.smartchanges.commit;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vcs.changes.ChangeListManager;
import com.intellij.vcs.changes.viewModel.ChangesViewProxy;
import com.intellij.vcs.commit.ChangesViewCommitPanel;
import com.intellij.vcs.commit.ChangesViewCommitWorkflow;
import com.intellij.vcs.commit.ChangesViewCommitWorkflowHandler;
import dev.changestree.smartchanges.model.ChangedItem;
import kotlinx.coroutines.CoroutineScope;
import kotlinx.coroutines.CoroutineScopeKt;

import java.util.LinkedHashSet;
import java.util.List;

public final class ChangeListCommitSession extends PanelCommitSession {
    private final CoroutineScope scope;
    private final ChangesViewProxy changesView;
    private final ChangesViewCommitWorkflowHandler handler;

    public ChangeListCommitSession(Project project) {
        this(project, CoroutineScopeKt.CoroutineScope(kotlinx.coroutines.SupervisorKt.SupervisorJob(null)
                .plus(kotlinx.coroutines.Dispatchers.getDefault())));
    }

    private ChangeListCommitSession(Project project, CoroutineScope scope) {
        this(project, scope, new PanelChangesView(project, scope));
    }

    private ChangeListCommitSession(Project project, CoroutineScope scope, ChangesViewProxy changesView) {
        super(project, new ChangesViewCommitPanel(project, changesView));
        this.scope = scope;
        this.changesView = changesView;
        changesView.initPanel();
        handler = new ChangesViewCommitWorkflowHandler(new ChangesViewCommitWorkflow(project), (ChangesViewCommitPanel) ui);
        ui.getComponent().setVisible(true);
        ui.getCommitActionsPanel().setActive(true);
    }

    @Override
    public ChangesViewCommitWorkflowHandler handler() {
        return handler;
    }

    @Override
    protected void prepare(List<ChangedItem> items, Runnable ready) {
        // Synchronize the native inclusion model, then expose it directly to the commit UI.
        changesView.scheduleRefreshNow(preparationCallback(() -> {
            if (disposed || project.isDisposed()) {
                return;
            }
            ChangeListManager manager = ChangeListManager.getInstance(project);
            handler.synchronizeInclusion(manager.getChangeLists(), manager.getUnversionedFilesPaths().stream().toList());
            var target = items.stream().map(ChangedItem::getChangeListName)
                    .map(manager::findChangeList).filter(java.util.Objects::nonNull)
                    .findFirst().orElse(manager.getDefaultChangeList());
            LinkedHashSet<Object> included = new LinkedHashSet<>();
            for (ChangedItem item : items) {
                included.add(item.getChange() != null ? item.getChange() : item.getFilePath());
            }
            handler.setCommitState(target, included, true);
            ready.run();
        }));
    }

    @Override
    protected void disposeResources() {
        Disposer.dispose(handler); // The handler owns its UI.
        Disposer.dispose(changesView);
        CoroutineScopeKt.cancel(scope, null);
    }
}
