package dev.changestree.smartchanges.commit;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vcs.changes.CommitExecutor;
import com.intellij.vcs.commit.NonModalCommitPanel;
import com.intellij.vcs.commit.NonModalCommitWorkflowHandler;
import dev.changestree.smartchanges.model.ChangedItem;

import java.util.List;

/** A workflow owned by the Smart Changes panel, never the native Changes tab. */
public abstract class PanelCommitSession implements Disposable {
    protected final Project project;
    public final NonModalCommitPanel ui;
    private boolean preparing;
    protected boolean disposed;
    private long preparationId;
    private javax.swing.Timer preparationTimeout;
    private Runnable stateChanged = () -> { };

    public final void setStateChanged(Runnable listener) {
        stateChanged = listener;
        handler().getWorkflow().addListener(new com.intellij.vcs.commit.CommitWorkflowListener() {
            @Override public void executionStarted() { stateChanged.run(); }
            @Override public void executionEnded() { stateChanged.run(); }
        }, ui);
    }

    protected abstract void disposeResources();

    @Override
    public final void dispose() {
        disposed = true;
        preparationId++;
        if (preparationTimeout != null) preparationTimeout.stop();
        disposeResources();
    }

    protected PanelCommitSession(Project project, NonModalCommitPanel ui) {
        this.project = project;
        this.ui = ui;
        ui.getComponent().setVisible(true);
        ui.getCommitActionsPanel().setActive(true);
        // Our buttons perform hidden-file checks before invoking the workflow.
        ui.getCommitActionsPanel().setVisible(false);
    }

    public abstract NonModalCommitWorkflowHandler<?, ?> handler();

    protected abstract void prepare(List<ChangedItem> items, Runnable ready);

    /** Refresh completion may run in the proxy's background coroutine scope. */
    protected final Runnable preparationCallback(Runnable action) {
        ApplicationManager.getApplication().assertIsDispatchThread();
        long request = preparationId;
        return PreparationCallback.create(
                task -> ApplicationManager.getApplication().invokeLater(task, ModalityState.nonModal()),
                () -> !disposed && !project.isDisposed() && preparing && request == preparationId,
                action, this::failPreparation);
    }

    private void failPreparation(RuntimeException exception) {
        preparationId++;
        if (preparationTimeout != null) preparationTimeout.stop();
        preparing = false;
        stateChanged.run();
        com.intellij.openapi.diagnostic.Logger.getInstance(PanelCommitSession.class)
                .warn("Could not prepare commit", exception);
        if (!disposed && !project.isDisposed()) {
            Messages.showErrorDialog(project, "Could not prepare the commit: " + exception.getMessage(), "Commit");
        }
    }

    public final boolean isBusy() {
        return preparing || handler().getWorkflow().isExecuting();
    }

    public final void execute(List<ChangedItem> items, boolean andPush) {
        ApplicationManager.getApplication().assertIsDispatchThread();
        if (disposed || isBusy()) {
            return;
        }
        String message = ui.getCommitMessage().getText();
        if (message.isBlank()) {
            Messages.showWarningDialog(project, "Enter a commit message before committing.", "Commit");
            ui.getCommitMessage().getEditorField().requestFocusInWindow();
            return;
        }
        preparing = true;
        stateChanged.run();
        long request = ++preparationId;
        preparationTimeout = new javax.swing.Timer(15000, event -> {
            preparing = false;
            preparationId++;
            stateChanged.run();
            Messages.showErrorDialog(project, "Timed out while preparing the commit changes. Refresh the list and retry.", "Commit");
        });
        preparationTimeout.setRepeats(false);
        preparationTimeout.start();
        try {
            prepare(List.copyOf(items), () -> {
                if (request != preparationId) return;
                preparationTimeout.stop();
                preparing = false;
                stateChanged.run();
                if (disposed || project.isDisposed()) {
                    return;
                }
                ui.getComponent().setVisible(true);
                // Changing the changelist can load its previous message.
                ui.getCommitMessage().setCommitMessage(message);
                CommitExecutor executor = andPush ? handler().getExecutor("Git.Commit.And.Push.Executor") : null;
                if (andPush && (executor == null || !handler().isExecutorEnabled(executor))) {
                    Messages.showErrorDialog(project, "Commit and Push is not available for the current changes.", "Commit and Push");
                    return;
                }
                if (handler().isCommitEmpty()) {
                    Messages.showWarningDialog(project, "There are no included changes to commit. Refresh the list and retry.", "Commit");
                    return;
                }
                handler().executorCalled(executor);
            });
        } catch (RuntimeException exception) {
            failPreparation(exception);
        }
    }
}
