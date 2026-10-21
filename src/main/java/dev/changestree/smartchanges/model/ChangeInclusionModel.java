package dev.changestree.smartchanges.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Owns which changes are included in the next commit. Git staging operations are returned to the caller. */
public final class ChangeInclusionModel {
    public enum SelectionState {
        NONE,
        ALL,
        MIXED
    }

    public record Operations(List<ChangedItem> toStage, List<ChangedItem> toUnstage) {
        public Operations {
            toStage = List.copyOf(toStage);
            toUnstage = List.copyOf(toUnstage);
        }
    }

    private final Set<String> excludedItems = new HashSet<>();

    public boolean isIncluded(ChangedItem item) {
        if (item.getGitStatusNode() != null) {
            return item.getArea() == ChangeArea.STAGED;
        }
        return !excludedItems.contains(item.getInclusionId());
    }

    public boolean isToggleable(ChangedItem item) {
        return item.getGitStatusNode() == null || item.getArea() != ChangeArea.CONFLICTED;
    }

    public SelectionState stateOf(Collection<ChangedItem> items) {
        int toggleableCount = 0;
        int includedCount = 0;
        for (ChangedItem item : items) {
            if (!isToggleable(item)) {
                continue;
            }
            toggleableCount++;
            if (isIncluded(item)) {
                includedCount++;
            }
        }
        if (includedCount == 0) {
            return SelectionState.NONE;
        }
        if (includedCount == toggleableCount) {
            return SelectionState.ALL;
        }
        return SelectionState.MIXED;
    }

    public List<ChangedItem> includedFrom(Collection<ChangedItem> items) {
        return items.stream().filter(this::isIncluded).toList();
    }

    /** Drop per-change overrides once those changes disappear from the current snapshot. */
    public void retainCurrentItems(Collection<ChangedItem> items) {
        Set<String> currentIds = new HashSet<>();
        for (ChangedItem item : items) {
            if (item.getGitStatusNode() == null) {
                currentIds.add(item.getInclusionId());
            }
        }
        excludedItems.retainAll(currentIds);
    }

    public Operations setIncluded(Collection<ChangedItem> items, boolean included) {
        List<ChangedItem> toStage = new ArrayList<>();
        List<ChangedItem> toUnstage = new ArrayList<>();
        for (ChangedItem item : items) {
            if (!isToggleable(item)) {
                continue;
            }
            if (item.getGitStatusNode() != null) {
                if (isIncluded(item) != included) {
                    (included ? toStage : toUnstage).add(item);
                }
            } else if (included) {
                excludedItems.remove(item.getInclusionId());
            } else {
                excludedItems.add(item.getInclusionId());
            }
        }
        return new Operations(toStage, toUnstage);
    }
}
