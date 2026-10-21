package dev.changestree.smartchanges.git;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.util.EventDispatcher;
import dev.changestree.smartchanges.commit.PanelCommitSession;
import dev.changestree.smartchanges.model.ChangedItem;
import git4idea.index.GitStageCommitWorkflow;
import git4idea.index.GitStageCommitWorkflowHandler;
import git4idea.index.GitStageTracker;
import git4idea.index.ui.GitStageCommitPanel;
import git4idea.index.ui.GitStageUiSettings;
import git4idea.index.ui.GitStageUiSettingsListener;

import java.util.List;

final class GitPanelCommitSession extends PanelCommitSession {
    private final GitStageCommitPanel panel;
    private final GitStageCommitWorkflowHandler handler;
    private final GitStageTracker tracker;

    GitPanelCommitSession(Project project) {
        super(project, new GitStageCommitPanel(project, new SessionSettings()));
        panel = (GitStageCommitPanel) ui;
        handler = new GitStageCommitWorkflowHandler(new GitStageCommitWorkflow(project), panel);
        tracker = GitStageTracker.getInstance(project);
        tracker.addListener(() -> ApplicationManager.getApplication().invokeLater(() -> {
            if (!disposed && !project.isDisposed() && !isBusy()) {
                updateState();
            }
        }), panel);
        handler.getWorkflow().addListener(new com.intellij.vcs.commit.CommitWorkflowListener() {
            @Override
            public void executionEnded() {
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (!disposed && !project.isDisposed()) updateState();
                });
            }
        }, panel);
        updateState();
    }

    private void updateState() {
        var state = tracker.getState();
        handler.setState(state);
        panel.setTrackerState(state);
    }

    @Override
    public GitStageCommitWorkflowHandler handler() {
        return handler;
    }

    @Override
    protected void prepare(List<ChangedItem> items, Runnable ready) {
        updateState();
        panel.setIncludedRoots(items.stream().map(ChangedItem::getVcsRoot)
                .filter(java.util.Objects::nonNull).distinct().toList());
        ready.run();
    }

    @Override
    protected void disposeResources() {
        Disposer.dispose(panel); // Git's panel owns its handler.
    }

    /** Never enable commit-all: it would stage excluded working-tree changes. */
    private static final class SessionSettings implements GitStageUiSettings {
        private final EventDispatcher<GitStageUiSettingsListener> listeners =
                EventDispatcher.create(GitStageUiSettingsListener.class);
        public boolean getIgnoredFilesShown() { return false; }
        public void setIgnoredFilesShown(boolean value) { }
        public boolean isCommitAllEnabled() { return false; }
        public void setCommitAllEnabled(boolean value) { }
        public void addListener(GitStageUiSettingsListener listener, Disposable parent) {
            listeners.addListener(listener, parent);
        }
    }
}
