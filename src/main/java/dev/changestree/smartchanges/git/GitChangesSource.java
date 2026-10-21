package dev.changestree.smartchanges.git;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.DataSink;
import dev.changestree.smartchanges.model.ChangedItem;

import java.util.Collection;
import java.util.List;

/** Core-facing contract; implementations live behind the optional Git4Idea dependency. */
public interface GitChangesSource {
    List<ChangedItem> mergeWithChangeLists(Collection<ChangedItem> changeListItems);

    void addRefreshListener(Runnable listener, Disposable parentDisposable);

    void addActionData(DataSink sink, Collection<ChangedItem> selectedItems);

    boolean hasStagingArea();

    dev.changestree.smartchanges.commit.PanelCommitSession createCommitSession();
}
