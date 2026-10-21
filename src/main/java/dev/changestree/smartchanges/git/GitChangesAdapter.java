package dev.changestree.smartchanges.git;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.DataSink;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.changes.ui.ChangesViewContentManager;
import com.intellij.openapi.vfs.VirtualFile;
import dev.changestree.smartchanges.model.ChangeArea;
import dev.changestree.smartchanges.model.ChangedItem;
import git4idea.index.GitFileStatus;
import git4idea.index.GitStageManagerKt;
import git4idea.index.ui.GitFileStatusNode;
import git4idea.index.ui.GitStageDataKeys;
import git4idea.index.ui.GitStagePanel;
import git4idea.index.ui.GitStageTree;
import git4idea.index.ui.NodeKind;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryChangeListener;
import git4idea.repo.GitRepositoryManager;
import git4idea.status.GitStagingAreaHolder;
import git4idea.status.GitStagingAreaHolder.StagingAreaListener;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Git4Idea bridge. All Git-specific types stay in this optional plugin fragment. */
public final class GitChangesAdapter implements GitChangesSource {
    private final Project project;

    public GitChangesAdapter(Project project) {
        this.project = project;
    }

    @Override
    public List<ChangedItem> mergeWithChangeLists(Collection<ChangedItem> changeListItems) {
        List<GitRepository> repositories = GitRepositoryManager.getInstance(project).getRepositories();
        if (repositories.isEmpty() || !hasStagingArea()) {
            return List.copyOf(changeListItems);
        }

        List<ChangedItem> merged = new ArrayList<>();
        for (ChangedItem item : changeListItems) {
            if (!isUnderAnyGitRoot(item.getPath(), repositories)) {
                merged.add(item);
            }
        }

        for (GitRepository repository : repositories) {
            VirtualFile root = repository.getRoot();
            for (GitFileStatus status : repository.getStagingAreaHolder().getAllRecords()) {
                if (status.isIgnored() || status.isNotChanged()) {
                    continue;
                }
                FilePath filePath = status.getPath();
                if (filePath == null) {
                    continue;
                }
                String beforePath = status.getOrigPath() == null ? null : status.getOrigPath().getPath();
                if (status.isConflicted()) {
                    merged.add(createItem(status, root, filePath, beforePath, ChangeArea.CONFLICTED,
                            NodeKind.CONFLICTED, "Conflicted", false));
                    continue;
                }

                if (status.getStagedStatus() != null) {
                    merged.add(createItem(status, root, filePath, beforePath, ChangeArea.STAGED,
                            NodeKind.STAGED, status.getStagedStatus().getText(), false));
                }
                if (status.getUnStagedStatus() != null) {
                    merged.add(createItem(status, root, filePath, beforePath, ChangeArea.UNSTAGED,
                            NodeKind.UNSTAGED, status.getUnStagedStatus().getText(), false));
                }
                if (status.isUntracked()) {
                    merged.add(createItem(status, root, filePath, beforePath, ChangeArea.UNSTAGED,
                            NodeKind.UNTRACKED, "Unversioned", true));
                }
            }
        }
        return merged;
    }

    private static ChangedItem createItem(
            GitFileStatus status,
            VirtualFile root,
            FilePath filePath,
            String beforePath,
            ChangeArea area,
            NodeKind kind,
            String statusText,
            boolean unversioned
    ) {
        GitFileStatusNode node = new GitFileStatusNode(root, status, kind);
        return new ChangedItem(null, filePath, beforePath, statusText, "", unversioned,
                area, root, node, node.getFileStatus());
    }

    @Override
    public void addRefreshListener(Runnable listener, Disposable parentDisposable) {
        var connection = project.getMessageBus().connect(parentDisposable);
        connection.subscribe(GitStagingAreaHolder.TOPIC,
                (StagingAreaListener) repository -> listener.run());
        connection.subscribe(GitRepository.GIT_REPO_CHANGE,
                (GitRepositoryChangeListener) repository -> listener.run());
    }

    @Override
    public void addActionData(DataSink sink, Collection<ChangedItem> selectedItems) {
        List<GitFileStatusNode> nodes = selectedItems.stream()
                .map(ChangedItem::getGitStatusNode)
                .filter(GitFileStatusNode.class::isInstance)
                .map(GitFileStatusNode.class::cast)
                .toList();
        sink.set(GitStageDataKeys.GIT_FILE_STATUS_NODES, nodes);

        GitStageTree stageTree = getActiveStageTree();
        if (stageTree == null) {
            sink.setNull(GitStageDataKeys.GIT_STAGE_TREE);
        } else {
            sink.set(GitStageDataKeys.GIT_STAGE_TREE, stageTree);
        }
    }

    @Override
    public boolean hasStagingArea() {
        return !GitRepositoryManager.getInstance(project).getRepositories().isEmpty()
                && GitStageManagerKt.isStagingAreaAvailable(project);
    }

    @Override
    public dev.changestree.smartchanges.commit.PanelCommitSession createCommitSession() {
        return new GitPanelCommitSession(project);
    }

    private GitStagePanel getActiveStagePanel() {
        return ChangesViewContentManager.getInstance(project).getActiveComponent(GitStagePanel.class);
    }

    private GitStageTree getActiveStageTree() {
        GitStagePanel panel = getActiveStagePanel();
        return panel != null && panel.getTree() instanceof GitStageTree tree ? tree : null;
    }

    private static boolean isUnderAnyGitRoot(String path, List<GitRepository> repositories) {
        Path candidate;
        try {
            candidate = Path.of(path).toAbsolutePath().normalize();
        } catch (RuntimeException ignored) {
            return false;
        }
        for (GitRepository repository : repositories) {
            Path root;
            try {
                root = Path.of(repository.getRoot().getPath()).toAbsolutePath().normalize();
            } catch (RuntimeException ignored) {
                continue;
            }
            if (candidate.startsWith(root)) {
                return true;
            }
        }
        return false;
    }
}
