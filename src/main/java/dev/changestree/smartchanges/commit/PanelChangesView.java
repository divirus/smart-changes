package dev.changestree.smartchanges.commit;

import com.intellij.codeWithMe.ClientId;
import com.intellij.diff.chains.DiffRequestProducer;
import com.intellij.openapi.ListSelection;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.changes.*;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.vcs.changes.viewModel.ChangesViewProxy;
import kotlin.Pair;
import kotlin.Unit;
import kotlinx.coroutines.CoroutineScope;
import kotlinx.coroutines.channels.BufferOverflow;
import kotlinx.coroutines.flow.MutableSharedFlow;
import kotlinx.coroutines.flow.SharedFlow;
import kotlinx.coroutines.flow.SharedFlowKt;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.util.Collection;
import java.util.List;

/** Supplies commit data directly, without an invisible second Changes tree. */
final class PanelChangesView extends ChangesViewProxy {
    private final JComponent panel = new JPanel();
    private final MutableSharedFlow<Unit> inclusionChanged = SharedFlowKt.MutableSharedFlow(0, 1, BufferOverflow.DROP_OLDEST);
    private final MutableSharedFlow<Pair<ChangesViewDiffAction, ClientId>> diffRequests =
            SharedFlowKt.MutableSharedFlow(0, 1, BufferOverflow.DROP_OLDEST);
    private InclusionModel inclusion;
    private final InclusionListener inclusionListener = () -> inclusionChanged.tryEmit(Unit.INSTANCE);
    private boolean disposed;

    PanelChangesView(Project project, CoroutineScope scope) {
        super(project, scope);
    }

    @Override public SharedFlow<Unit> getInclusionChanged() { return inclusionChanged; }
    @Override public SharedFlow<Pair<ChangesViewDiffAction, ClientId>> getDiffRequests() { return diffRequests; }
    @Override public JComponent getPanel() { return panel; }
    @Override public void initPanel() { }
    @Override public void setToolbarHorizontal(boolean horizontal) { }

    @Override
    public void scheduleRefreshNow(Runnable callback) {
        // The handler refreshes VCS state separately before writing its final commit state.
        // No tree model needs to be rebuilt before exposing included items.
        ApplicationManager.getApplication().invokeLater(() -> {
            if (!disposed && !getProject().isDisposed() && callback != null) callback.run();
        }, ModalityState.nonModal());
    }

    @Override public void scheduleDelayedRefresh() { }
    @Override public void setGrouping(String grouping) { }
    @Override public void resetViewImmediatelyAndRefreshLater() { }

    @Override
    public void setInclusionModel(InclusionModel model) {
        if (inclusion != null) inclusion.removeInclusionListener(inclusionListener);
        inclusion = model;
        if (model != null) model.addInclusionListener(inclusionListener);
    }

    @Override
    public List<Change> getDisplayedChanges() {
        return List.copyOf(ChangeListManager.getInstance(getProject()).getAllChanges());
    }

    @Override
    public List<Change> getIncludedChanges() {
        return included(Change.class);
    }

    @Override
    public List<FilePath> getDisplayedUnversionedFiles() {
        return List.copyOf(ChangeListManager.getInstance(getProject()).getUnversionedFilesPaths());
    }

    @Override public List<FilePath> getIncludedUnversionedFiles() { return included(FilePath.class); }

    private <T> List<T> included(Class<T> type) {
        return inclusion == null ? List.of() : inclusion.getInclusion().stream()
                .filter(type::isInstance).map(type::cast).toList();
    }

    // Navigation and diffs belong to the visible Smart Changes table.
    @Override public void expand(Object item) { }
    @Override public void select(Object item) { }
    @Override public void selectFirst(Collection<?> items) { }
    @Override public void selectFile(VirtualFile file) { }
    @Override public void selectChanges(List<? extends Change> changes) { }
    @Override public boolean hasContentToDiff() { return false; }
    @Override public ListSelection<? extends DiffRequestProducer> getDiffRequestProducers(boolean selectedOnly) {
        return ListSelection.empty();
    }
    @Override public ChangeViewDiffRequestProcessor createDiffPreviewProcessor(boolean editor) {
        throw new UnsupportedOperationException("Diff preview is owned by Smart Changes");
    }

    @Override
    public void dispose() {
        disposed = true;
        if (inclusion != null) inclusion.removeInclusionListener(inclusionListener);
        super.dispose();
    }
}
