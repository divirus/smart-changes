package dev.changestree.smartchanges.ui;

import com.intellij.diff.DiffDialogHints;
import com.intellij.diff.DiffManager;
import com.intellij.openapi.actionSystem.ActionGroup;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.ActionUiKind;
import com.intellij.ide.DataManager;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.DataSink;
import com.intellij.openapi.actionSystem.PlatformDataKeys;
import com.intellij.openapi.actionSystem.UiCompatibleDataProvider;
import com.intellij.openapi.actionSystem.ex.ActionUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.VcsDataKeys;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ChangeListListener;
import com.intellij.openapi.vcs.changes.ChangeListManager;
import com.intellij.openapi.vcs.changes.LocalChangeList;
import com.intellij.openapi.vcs.changes.actions.diff.ChangeDiffRequestProducer;
import com.intellij.openapi.vcs.changes.ui.ChangeDiffRequestChain;
import com.intellij.openapi.vcs.changes.ui.ChangesListView;
import com.intellij.openapi.fileChooser.actions.VirtualFileDeleteProvider;
import com.intellij.ide.CopyProvider;
import com.intellij.ide.TreeExpander;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.pom.Navigatable;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.util.Alarm;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.ui.JBUI;
import com.intellij.vcs.commit.CommitWorkflowHandler;
import dev.changestree.smartchanges.model.ChangeInclusionModel;
import dev.changestree.smartchanges.model.ChangeArea;
import dev.changestree.smartchanges.model.ChangedItem;
import dev.changestree.smartchanges.model.ChangesSnapshotProvider;
import dev.changestree.smartchanges.model.HiddenChangesModel;
import dev.changestree.smartchanges.model.SortMode;
import dev.changestree.smartchanges.git.GitChangesSource;
import dev.changestree.smartchanges.settings.SmartChangesSettings;
import org.jetbrains.annotations.NotNull;

import javax.swing.JButton;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.datatransfer.StringSelection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

final class SmartChangesPanel extends JPanel implements ChangeListListener, UiCompatibleDataProvider, com.intellij.openapi.Disposable {
    private static final Logger LOG = Logger.getInstance(SmartChangesPanel.class);
    private static final String CHANGES_POPUP_GROUP = "ChangesViewPopupMenu";

    private final Project project;
    private final ChangesSnapshotProvider snapshotProvider;
    private final GitChangesSource gitChangesSource;
    private final SmartChangesSettings settings;
    private final HiddenChangesModel hiddenChangesModel;
    private final Alarm refreshAlarm;
    private final AtomicLong refreshSequence = new AtomicLong();
    private final ChangeInclusionModel inclusionModel = new ChangeInclusionModel();
    private final JBCheckBox groupingToggle = new JBCheckBox("Group by");
    private final JBCheckBox showHiddenToggle = new JBCheckBox("Show hidden (0)");
    private dev.changestree.smartchanges.commit.PanelCommitSession commitSession;
    private final JPanel commitContainer = new JPanel(new BorderLayout());
    private final JButton commitButton = new JButton("Commit");
    private final JButton commitAndPushButton = new JButton("Commit and Push...");
    private final JButton hideRestoreButton = new JButton("Hide selected");
    private final Set<String> collapsedGroups = new HashSet<>();
    private final ChangesTableView tableView;
    private volatile List<ChangedItem> actionContextItems;
    private final CopyProvider copyProvider = new CopyProvider() {
        @Override
        public ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }

        @Override
        public void performCopy(DataContext dataContext) {
            String selectedPaths = tableView.selectedItems().stream()
                    .map(ChangedItem::getPath)
                    .collect(java.util.stream.Collectors.joining("\n"));
            if (!selectedPaths.isEmpty()) {
                CopyPasteManager.getInstance().setContents(new StringSelection(selectedPaths));
            }
        }

        @Override
        public boolean isCopyEnabled(DataContext dataContext) {
            return !tableView.selectedItems().isEmpty();
        }

        @Override
        public boolean isCopyVisible(DataContext dataContext) {
            return true;
        }
    };
    private final TreeExpander treeExpander = new TreeExpander() {
        @Override
        public void expandAll() {
            collapsedGroups.removeAll(groupIds());
            renderSnapshot();
        }

        @Override
        public boolean canExpand() {
            return groupIds().stream().anyMatch(collapsedGroups::contains);
        }

        @Override
        public void collapseAll() {
            collapsedGroups.addAll(groupIds());
            renderSnapshot();
        }

        @Override
        public boolean canCollapse() {
            return groupIds().stream().anyMatch(group -> !collapsedGroups.contains(group));
        }
    };
    private volatile List<ChangedItem> lastSnapshot = List.of();
    private volatile boolean disposed;
    private boolean viewReady;
    private volatile boolean initialRefreshReady;

    SmartChangesPanel(Project project) {
        this.project = project;
        this.snapshotProvider = new ChangesSnapshotProvider(project);
        this.gitChangesSource = project.getService(GitChangesSource.class);
        this.settings = project.getService(SmartChangesSettings.class);
        this.hiddenChangesModel = new HiddenChangesModel(settings.getHiddenFilePaths());
        this.refreshAlarm = new Alarm(Alarm.ThreadToUse.SWING_THREAD, this);
        this.tableView = new ChangesTableView(settings, inclusionModel, createPopupGroup(), new ChangesTableView.Listener() {
            @Override
            public void sortBy(SortMode mode) {
                updateSortMode(mode);
            }

            @Override
            public void toggleInclusion(ChangesTableProjector.Row row) {
                SmartChangesPanel.this.toggleInclusion(row);
            }

            @Override
            public void toggleAllInclusion() {
                SmartChangesPanel.this.toggleAllInclusion();
            }

            @Override
            public void toggleGroup(String groupId) {
                SmartChangesPanel.this.toggleGroup(groupId);
            }

            @Override
            public void openSelected() {
                performDefaultAction();
            }

            @Override
            public boolean isShowingHidden() {
                return showHiddenToggle.isSelected();
            }

            @Override
            public void setHidden(List<ChangedItem> items, boolean hidden) {
                setItemsHidden(items, hidden);
            }

            @Override
            public void selectionChanged() {
                updateVisibilityAction();
            }
        });
        viewReady = true;
        updateVisibilityAction();

        groupingToggle.setSelected(settings.isGroupingEnabled());
        groupingToggle.setToolTipText("Group changes by file extension");
        groupingToggle.getAccessibleContext().setAccessibleName("Group by file extension");
        showHiddenToggle.setToolTipText("Show files hidden from the main Changes list; hiding does not change VCS state");
        showHiddenToggle.getAccessibleContext().setAccessibleName("Show hidden changes");
        setLayout(new BorderLayout());
        add(buildToolbar(), BorderLayout.NORTH);
        add(tableView, BorderLayout.CENTER);
        add(buildCommitPanel(), BorderLayout.SOUTH);
        bindControls();

        ChangeListManager.getInstance(project).addChangeListListener(this, this);
        if (gitChangesSource != null) {
            gitChangesSource.addRefreshListener(this::scheduleRefresh, this);
        }
        // A startup snapshot can be empty before the first VCS scan. Do not prune
        // persisted hidden paths or exclusion state until that scan has completed.
        ChangeListManager.getInstance(project).invokeAfterUpdate(false, () -> {
            if (!disposed && !project.isDisposed()) {
                initialRefreshReady = true;
                scheduleRefresh();
            }
        });
    }

    private JPanel buildToolbar() {
        JPanel toolbar = new JPanel(new BorderLayout(0, JBUI.scale(3)));
        toolbar.setBorder(JBUI.Borders.empty(3, 6, 4, 6));

        ActionManager actionManager = ActionManager.getInstance();
        DefaultActionGroup nativeActions = new DefaultActionGroup("Smart Changes Actions", false);
        addNativeAction(nativeActions, actionManager, "ChangesView.Revert");
        addNativeAction(nativeActions, actionManager, "ChangesView.ShelveSilently");
        addNativeAction(nativeActions, actionManager, "ChangesView.Changelists");
        addNativeAction(nativeActions, actionManager, "ExpandAll");
        addNativeAction(nativeActions, actionManager, "CollapseAll");
        ActionToolbar nativeToolbar = actionManager.createActionToolbar("SmartChangesToolbar", nativeActions, true);
        nativeToolbar.setTargetComponent(this);
        toolbar.add(nativeToolbar.getComponent(), BorderLayout.NORTH);

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0));
        controls.setOpaque(false);
        controls.add(groupingToggle);
        controls.add(showHiddenToggle);
        controls.add(hideRestoreButton);
        toolbar.add(controls, BorderLayout.SOUTH);
        return toolbar;
    }

    private static void addNativeAction(DefaultActionGroup group, ActionManager actionManager, String actionId) {
        AnAction action = actionManager.getAction(actionId);
        if (action != null) {
            group.add(action);
        }
    }

    private JPanel buildCommitPanel() {
        commitContainer.setBorder(JBUI.Borders.empty(5, 8, 8, 8));
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0));
        commitButton.addActionListener(event -> startCommitWorkflow(false));
        commitAndPushButton.addActionListener(event -> startCommitWorkflow(true));
        actions.add(commitButton);
        actions.add(commitAndPushButton);
        commitContainer.add(actions, BorderLayout.SOUTH);
        ensureCommitSession();
        return commitContainer;
    }

    private void ensureCommitSession() {
        boolean staging = gitChangesSource != null && gitChangesSource.hasStagingArea()
                && (commitItems().isEmpty() || commitItems().stream().anyMatch(item -> item.getGitStatusNode() != null));
        if (commitSession != null && staging == stagingSession) {
            return;
        }
        if (commitSession != null && commitSession.isBusy()) {
            return;
        }
        String message = commitSession == null ? "" : commitSession.ui.getCommitMessage().getText();
        if (commitSession != null) {
            commitContainer.remove(commitSession.ui.getComponent());
            Disposer.dispose(commitSession);
        }
        stagingSession = staging;
        commitSession = staging ? gitChangesSource.createCommitSession()
                : new dev.changestree.smartchanges.commit.ChangeListCommitSession(project);
        Disposer.register(this, commitSession);
        commitSession.setStateChanged(() -> ApplicationManager.getApplication().invokeLater(() -> {
            if (!disposed && !project.isDisposed()) renderSnapshot();
        }));
        if (!message.isBlank()) {
            commitSession.ui.getCommitMessage().setCommitMessage(message);
        }
        commitContainer.add(commitSession.ui.getComponent(), BorderLayout.CENTER);
        commitContainer.revalidate();
        commitContainer.repaint();
    }

    private boolean stagingSession;

    private void startCommitWorkflow(boolean andPush) {
        if (showHiddenToggle.isSelected() || (commitSession != null && commitSession.isBusy())) {
            return;
        }
        List<ChangedItem> items = commitItems();
        if (items.isEmpty()) {
            return;
        }
        boolean stagedGit = items.stream().anyMatch(item -> item.getGitStatusNode() != null);
        boolean changeListItems = items.stream().anyMatch(item -> item.getGitStatusNode() == null);
        if (stagedGit && changeListItems) {
            Messages.showWarningDialog(project,
                    "Git staging changes and other VCS changes must be committed separately. Include files from one workflow and retry.",
                    "Mixed VCS Selection");
            return;
        }
        List<ChangedItem> hiddenStagedItems = hiddenChangesModel.inView(lastSnapshot, true).stream()
                .filter(item -> item.getGitStatusNode() != null && item.getArea() == ChangeArea.STAGED)
                .collect(java.util.stream.Collectors.toMap(
                        ChangedItem::getPath,
                        item -> item,
                        (first, ignored) -> first,
                        java.util.LinkedHashMap::new
                ))
                .values().stream()
                .toList();
        if (!hiddenStagedItems.isEmpty()) {
            String fileList = hiddenStagedItems.stream()
                    .map(ChangedItem::getName)
                    .limit(5)
                    .collect(java.util.stream.Collectors.joining(", "));
            String more = hiddenStagedItems.size() > 5 ? " and more" : "";
            int answer = Messages.showYesNoDialog(
                    project,
                    hiddenStagedItems.size() + " hidden staged file(s) remain staged and may be included in the native commit workflow: "
                            + fileList + more + ".\n\nContinue?",
                    "Hidden Staged Changes",
                    Messages.getWarningIcon()
            );
            if (answer != Messages.YES) {
                showHiddenToggle.setSelected(true);
                renderSnapshot();
                return;
            }
        }
        ensureCommitSession();
        commitSession.execute(items, andPush);
    }

    private void executeNativeAction(String actionId) {
        executeNativeAction(actionId, tableView.selectedItems());
    }

    private void executeNativeAction(String actionId, List<ChangedItem> items) {
        AnAction action = ActionManager.getInstance().getAction(actionId);
        if (action == null || project.isDisposed()) {
            return;
        }
        // Native actions require a component attached to the showing window.
        List<ChangedItem> previousItems = actionContextItems;
        actionContextItems = List.copyOf(items);
        try {
            AnActionEvent event = AnActionEvent.createEvent(action,
                    DataManager.getInstance().getDataContext(this), null, "SmartChanges", ActionUiKind.NONE, null);
            ActionUtil.performAction(action, event);
        } finally {
            actionContextItems = previousItems;
        }
    }

    private ActionGroup createPopupGroup() {
        ActionManager actionManager = ActionManager.getInstance();
        DefaultActionGroup composite = new DefaultActionGroup("Smart Changes Popup", false);
        boolean hasActions = false;
        if (actionManager.getAction(CHANGES_POPUP_GROUP) instanceof ActionGroup changesActions) {
            composite.add(changesActions);
            hasActions = true;
        }
        if (actionManager.getAction("Git.Stage.Tree.Menu") instanceof ActionGroup nativeGitMenu) {
            DefaultActionGroup gitStageActions = new DefaultActionGroup("Git staging", true);
            gitStageActions.add(nativeGitMenu);
            composite.addSeparator();
            composite.add(gitStageActions);
            hasActions = true;
        }
        return hasActions ? composite : null;
    }

    private void bindControls() {
        groupingToggle.addActionListener(event -> {
            settings.setGroupingEnabled(groupingToggle.isSelected());
            renderSnapshot();
        });
        showHiddenToggle.addActionListener(event -> renderSnapshot());
        hideRestoreButton.addActionListener(event -> setItemsHidden(
                tableView.selectedItems(), !showHiddenToggle.isSelected()));
    }

    private void updateSortMode(SortMode clicked) {
        if (clicked == settings.getSortMode()) {
            settings.setAscending(!settings.isAscending());
        } else {
            settings.setSortMode(clicked);
            settings.setAscending(true);
        }
        renderSnapshot();
    }

    private void toggleGroup(String groupId) {
        if (!collapsedGroups.add(groupId)) {
            collapsedGroups.remove(groupId);
        }
        renderSnapshot();
    }

    private void toggleInclusion(ChangesTableProjector.Row row) {
        if (row.isGroup()) {
            List<ChangedItem> groupItems = ChangesTableProjector.itemsInGroup(currentViewItems(), row);
            boolean include = inclusionModel.stateOf(groupItems) != ChangeInclusionModel.SelectionState.ALL;
            setIncluded(groupItems, include);
            return;
        }

        ChangedItem item = row.item();
        if (inclusionModel.isToggleable(item)) {
            setIncluded(List.of(item), !inclusionModel.isIncluded(item));
        }
    }

    private void toggleAllInclusion() {
        List<ChangedItem> viewItems = currentViewItems();
        if (!viewItems.isEmpty()) {
            boolean include = inclusionModel.stateOf(viewItems) != ChangeInclusionModel.SelectionState.ALL;
            setIncluded(viewItems, include);
        }
    }

    private void setIncluded(List<ChangedItem> items, boolean include) {
        ChangeInclusionModel.Operations operations = inclusionModel.setIncluded(items, include);
        if (!operations.toStage().isEmpty()) {
            executeNativeAction("Git.Stage.Add", operations.toStage());
        }
        if (!operations.toUnstage().isEmpty()) {
            executeNativeAction("Git.Stage.Reset", operations.toUnstage());
        }
        renderSnapshot();
    }

    private List<ChangedItem> commitItems() {
        return inclusionModel.includedFrom(hiddenChangesModel.inView(lastSnapshot, false));
    }

    private void scheduleRefresh() {
        if (disposed || !initialRefreshReady) {
            return;
        }
        long request = refreshSequence.incrementAndGet();
        refreshAlarm.cancelAllRequests();
        refreshAlarm.addRequest(() -> loadSnapshotAsync(request), 180);
    }

    private void loadSnapshotAsync(long request) {
        if (disposed || project.isDisposed() || refreshSequence.get() != request) {
            return;
        }
        if (com.intellij.openapi.vcs.changes.ChangeListManagerEx.getInstanceEx(project).isInUpdate()) {
            ChangeListManager.getInstance(project).invokeAfterUpdate(false, this::scheduleRefresh);
            return;
        }
        AppExecutorUtil.getAppExecutorService().execute(() -> {
            if (disposed || project.isDisposed() || refreshSequence.get() != request) return;
            List<ChangedItem> snapshot;
            try {
                snapshot = List.copyOf(snapshotProvider.load());
            } catch (RuntimeException exception) {
                LOG.warn("Could not refresh the Smart Changes view", exception);
                return;
            }
            List<ChangedItem> result = snapshot;
            ApplicationManager.getApplication().invokeLater(() -> {
                if (!disposed && !project.isDisposed() && refreshSequence.get() == request) {
                    lastSnapshot = result;
                    inclusionModel.retainCurrentItems(result);
                    if (hiddenChangesModel.retainCurrentItems(result)) {
                        settings.setHiddenFilePaths(hiddenChangesModel.persistedPaths());
                    }
                    renderSnapshot();
                }
            });
        });
    }

    private void renderSnapshot() {
        if (disposed || !ApplicationManager.getApplication().isDispatchThread()) {
            return;
        }
        List<ChangedItem> viewItems = currentViewItems();
        ensureCommitSession();
        commitSession.ui.getComponent().setVisible(true);
        List<ChangesTableProjector.Row> rows = ChangesTableProjector.project(
                viewItems,
                settings.getSortMode(),
                settings.isGroupingEnabled(),
                settings.isAscending(),
                collapsedGroups,
                project.getBasePath()
        );
        tableView.setRows(rows, viewItems, showHiddenToggle.isSelected());
        showHiddenToggle.setText("Show hidden (" + hiddenChangesModel.hiddenFileCount() + ")");
        updateVisibilityAction();
        boolean canCommit = !showHiddenToggle.isSelected() && !commitItems().isEmpty() && !commitSession.isBusy();
        commitButton.setEnabled(canCommit);
        commitAndPushButton.setEnabled(canCommit
                && gitChangesSource != null
                && ActionManager.getInstance().getAction("Git.Commit.And.Push.Executor") != null);
        commitButton.setToolTipText(showHiddenToggle.isSelected()
                ? "Switch back to Changes to start a commit workflow"
                : "Commit included changes using the message below");
        commitAndPushButton.setToolTipText(commitAndPushButton.isEnabled()
                ? "Commit the included changes and run the IDE Git push workflow"
                : "Git commit and push are unavailable for the current view");
    }

    private List<ChangedItem> currentViewItems() {
        return hiddenChangesModel.inView(lastSnapshot, showHiddenToggle.isSelected());
    }

    private void setItemsHidden(List<ChangedItem> items, boolean hidden) {
        if (items.isEmpty() || !hiddenChangesModel.setHidden(items, hidden)) {
            return;
        }
        settings.setHiddenFilePaths(hiddenChangesModel.persistedPaths());
        renderSnapshot();
    }

    private void updateVisibilityAction() {
        if (!viewReady) {
            return;
        }
        hideRestoreButton.setText(showHiddenToggle.isSelected() ? "Restore selected" : "Hide selected");
        hideRestoreButton.setEnabled(!tableView.selectedItems().isEmpty());
        hideRestoreButton.setToolTipText(showHiddenToggle.isSelected()
                ? "Restore selected files to the main Changes list"
                : "Move selected files to the hidden changes list");
    }

    private Set<String> groupIds() {
        return ChangesTableProjector.groupIds(currentViewItems(), settings.isGroupingEnabled());
    }

    private void performDefaultAction() {
        List<ChangedItem> selected = tableView.selectedItems();
        if (selected.size() != 1 || project.isDisposed()) {
            return;
        }
        ChangedItem item = selected.getFirst();
        if (item.getChange() != null) {
            showDiff(List.of(item.getChange()));
        } else if (item.getGitStatusNode() != null) {
            executeNativeAction("Diff.ShowDiff");
        } else {
            openFile(item);
        }
    }

    private void openFile(ChangedItem item) {
        VirtualFile file = item.getFilePath().getVirtualFile();
        if (file == null) {
            file = LocalFileSystem.getInstance().findFileByIoFile(item.getFilePath().getIOFile());
        }
        if (file != null && file.isValid()) {
            new OpenFileDescriptor(project, file).navigate(true);
        }
    }

    private void showDiff(List<Change> changes) {
        List<ChangeDiffRequestProducer> producers = changes.stream()
                .filter(change -> ChangeDiffRequestProducer.canCreate(project, change))
                .map(change -> ChangeDiffRequestProducer.create(project, change))
                .toList();
        if (!producers.isEmpty()) {
            DiffManager.getInstance().showDiff(
                    project,
                    new ChangeDiffRequestChain(producers, 0),
                    DiffDialogHints.DEFAULT
            );
        }
    }

    @Override
    public void uiDataSnapshot(@NotNull DataSink sink) {
        List<ChangedItem> scopedItems = actionContextItems;
        fillDataSnapshot(sink, scopedItems == null ? tableView.selectedItems() : scopedItems);
    }

    private void fillDataSnapshot(DataSink sink, List<ChangedItem> items) {
        List<Change> selectedChanges = items.stream()
                .map(ChangedItem::getChange)
                .filter(java.util.Objects::nonNull)
                .toList();
        Change[] changes = selectedChanges.toArray(Change[]::new);
        List<FilePath> paths = items.stream().map(ChangedItem::getFilePath).toList();
        List<VirtualFile> files = paths.stream()
                .map(FilePath::getVirtualFile)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        List<FilePath> unversionedPaths = items.stream()
                .filter(ChangedItem::isUnversioned)
                .map(ChangedItem::getFilePath)
                .toList();
        LinkedHashSet<LocalChangeList> changeLists = new LinkedHashSet<>();
        ChangeListManager manager = ChangeListManager.getInstance(project);
        for (ChangedItem item : items) {
            if (!item.getChangeListName().isBlank()) {
                LocalChangeList changeList = manager.findChangeList(item.getChangeListName());
                if (changeList != null) {
                    changeLists.add(changeList);
                }
            }
        }

        sink.set(CommonDataKeys.PROJECT, project);
        CommitWorkflowHandler commitWorkflowHandler = commitSession == null ? null : commitSession.handler();
        if (commitWorkflowHandler == null) {
            sink.setNull(VcsDataKeys.COMMIT_WORKFLOW_HANDLER);
        } else {
            sink.set(VcsDataKeys.COMMIT_WORKFLOW_HANDLER, commitWorkflowHandler);
        }
        if (commitSession != null) {
            commitSession.ui.uiDataSnapshot(sink);
        }
        sink.set(PlatformDataKeys.COPY_PROVIDER, copyProvider);
        sink.set(PlatformDataKeys.TREE_EXPANDER, treeExpander);
        sink.set(VcsDataKeys.CHANGES, changes);
        sink.set(VcsDataKeys.SELECTED_CHANGES, changes);
        sink.set(VcsDataKeys.SELECTED_CHANGES_IN_DETAILS, changes);
        sink.set(VcsDataKeys.CHANGE_LISTS, changeLists.toArray(LocalChangeList[]::new));
        sink.set(VcsDataKeys.VIRTUAL_FILES, files);
        sink.set(ChangesListView.UNVERSIONED_FILE_PATHS_DATA_KEY, unversionedPaths);
        sink.set(ChangesListView.EXACTLY_SELECTED_FILES_DATA_KEY, files);
        if (paths.isEmpty()) {
            sink.setNull(PlatformDataKeys.DELETE_ELEMENT_PROVIDER);
        } else {
            sink.set(PlatformDataKeys.DELETE_ELEMENT_PROVIDER, new VirtualFileDeleteProvider());
        }
        sink.set(CommonDataKeys.VIRTUAL_FILE_ARRAY, files.toArray(VirtualFile[]::new));
        if (!paths.isEmpty()) {
            sink.set(VcsDataKeys.FILE_PATH, paths.getFirst());
        } else {
            sink.setNull(VcsDataKeys.FILE_PATH);
        }
        if (!files.isEmpty()) {
            sink.set(CommonDataKeys.VIRTUAL_FILE, files.getFirst());
        } else {
            sink.setNull(CommonDataKeys.VIRTUAL_FILE);
        }
        List<Navigatable> navigatables = files.stream()
                .filter(VirtualFile::isValid)
                .map(file -> (Navigatable) new OpenFileDescriptor(project, file))
                .toList();
        sink.set(CommonDataKeys.NAVIGATABLE_ARRAY, navigatables.toArray(Navigatable[]::new));
        if (navigatables.isEmpty()) {
            sink.setNull(CommonDataKeys.NAVIGATABLE);
        } else {
            sink.set(CommonDataKeys.NAVIGATABLE, navigatables.getFirst());
        }
        if (selectedChanges.size() == 1) {
            sink.set(VcsDataKeys.CURRENT_CHANGE, selectedChanges.getFirst());
            sink.set(VcsDataKeys.CHANGE_LEAD_SELECTION, changes);
        } else {
            sink.setNull(VcsDataKeys.CURRENT_CHANGE);
            sink.setNull(VcsDataKeys.CHANGE_LEAD_SELECTION);
        }
        VirtualFile selectedUnversioned = items.stream()
                .filter(ChangedItem::isUnversioned)
                .map(item -> item.getFilePath().getVirtualFile())
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
        if (selectedUnversioned != null) {
            sink.set(VcsDataKeys.CURRENT_UNVERSIONED, selectedUnversioned);
        } else {
            sink.setNull(VcsDataKeys.CURRENT_UNVERSIONED);
        }
        if (gitChangesSource != null) {
            gitChangesSource.addActionData(sink, items);
        }
    }

    @Override
    public void changeListUpdateDone() {
        scheduleRefresh();
    }

    @Override
    public void changedFileStatusChanged() {
        scheduleRefresh();
    }

    @Override
    public void unchangedFileStatusChanged() {
        scheduleRefresh();
    }

    @Override
    public void changesAdded(@NotNull java.util.Collection<? extends Change> changes,
                             @NotNull com.intellij.openapi.vcs.changes.ChangeList changeList) {
        scheduleRefresh();
    }

    @Override
    public void changesRemoved(@NotNull java.util.Collection<? extends Change> changes,
                               @NotNull com.intellij.openapi.vcs.changes.ChangeList changeList) {
        scheduleRefresh();
    }

    @Override
    public void changesMoved(@NotNull java.util.Collection<? extends Change> changes,
                             @NotNull com.intellij.openapi.vcs.changes.ChangeList fromList,
                             @NotNull com.intellij.openapi.vcs.changes.ChangeList toList) {
        scheduleRefresh();
    }

    @Override
    public void dispose() {
        // Children (alarms, commit session, listeners) are released by Disposer.
        disposed = true;
        refreshSequence.incrementAndGet();
    }

}
