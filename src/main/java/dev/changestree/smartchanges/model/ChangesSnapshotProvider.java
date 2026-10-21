package dev.changestree.smartchanges.model;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.FileStatus;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ChangeListManager;
import com.intellij.openapi.vcs.changes.LocalChangeList;
import dev.changestree.smartchanges.git.GitChangesSource;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ChangesSnapshotProvider {
    private final Project project;

    public ChangesSnapshotProvider(Project project) {
        this.project = project;
    }

    public List<ChangedItem> load() {
        return ReadAction.computeBlocking(() -> {
            ChangeListManager manager = ChangeListManager.getInstance(project);
            Map<String, ChangedItem> items = new LinkedHashMap<>();

            Collection<Change> changes = manager.getAllChanges();
            for (Change change : changes) {
                FilePath before = change.getBeforeRevision() == null
                        ? null
                        : change.getBeforeRevision().getFile();
                FilePath after = change.getAfterRevision() == null
                        ? null
                        : change.getAfterRevision().getFile();
                FilePath displayedPath = after != null ? after : before;
                if (displayedPath == null) {
                    continue;
                }

                LocalChangeList changeList = manager.getChangeList(change);
                String changeListName = changeList == null ? "" : changeList.getName();
                String beforePath = before == null ? null : before.getPath();
                ChangedItem item = new ChangedItem(
                        change,
                        displayedPath,
                        beforePath,
                        change.getFileStatus().getText(),
                        changeListName,
                        false,
                        ChangeArea.NONE,
                        null,
                        null,
                        change.getFileStatus()
                );
                items.put(item.getStableId(), item);
            }

            for (FilePath path : manager.getUnversionedFilesPaths()) {
                ChangedItem item = new ChangedItem(null, path, null, "Unversioned", "", true,
                        ChangeArea.NONE, null, null, FileStatus.UNKNOWN);
                items.put(item.getStableId(), item);
            }

            GitChangesSource gitChangesSource = project.getService(GitChangesSource.class);
            List<ChangedItem> genericItems = new ArrayList<>(items.values());
            return gitChangesSource == null
                    ? genericItems
                    : gitChangesSource.mergeWithChangeLists(genericItems);
        });
    }
}
