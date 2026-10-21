package dev.changestree.smartchanges.model;

import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.FileStatus;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vfs.VirtualFile;

import java.io.File;
import java.util.Locale;

public final class ChangedItem {
    private final Change change;
    private final FilePath filePath;
    private final String beforePath;
    private final String status;
    private final String changeListName;
    private final boolean unversioned;
    private final ChangeArea area;
    private final VirtualFile vcsRoot;
    private final Object gitStatusNode;
    private final FileStatus fileStatus;

    public ChangedItem(
            Change change,
            FilePath filePath,
            String beforePath,
            String status,
            String changeListName,
            boolean unversioned,
            ChangeArea area,
            VirtualFile vcsRoot,
            Object gitStatusNode,
            FileStatus fileStatus
    ) {
        this.change = change;
        this.filePath = filePath;
        this.beforePath = beforePath;
        this.status = status;
        this.changeListName = changeListName;
        this.unversioned = unversioned;
        this.area = area;
        this.vcsRoot = vcsRoot;
        this.gitStatusNode = gitStatusNode;
        this.fileStatus = fileStatus != null
                ? fileStatus
                : change != null ? change.getFileStatus() : unversioned ? FileStatus.UNKNOWN : null;
    }

    public Change getChange() {
        return change;
    }

    public FilePath getFilePath() {
        return filePath;
    }

    public String getPath() {
        return filePath.getPath();
    }

    public String getName() {
        return filePath.getName();
    }

    public String getParentPath() {
        String parent = new File(getPath()).getParent();
        return parent == null ? "" : parent;
    }

    public String getBeforePath() {
        return beforePath;
    }

    public String getStatus() {
        return status;
    }

    public String getChangeListName() {
        return changeListName;
    }

    public boolean isUnversioned() {
        return unversioned;
    }

    public ChangeArea getArea() {
        return area;
    }

    public VirtualFile getVcsRoot() {
        return vcsRoot;
    }

    /** Opaque Git plugin payload, kept out of the core model to isolate Git-specific APIs. */
    public Object getGitStatusNode() {
        return gitStatusNode;
    }

    public FileStatus getFileStatus() {
        return fileStatus;
    }

    public String getAreaLabel() {
        return switch (area) {
            case STAGED -> "Staged";
            case UNSTAGED -> "Unstaged";
            case CONFLICTED -> "Conflict";
            case NONE -> "";
        };
    }

    public String getExtension() {
        String name = getName();
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) {
            return "(No extension)";
        }
        return name.substring(dot).toLowerCase(Locale.ROOT);
    }

    public String getStableId() {
        String rootPath = vcsRoot == null ? "" : vcsRoot.getPath();
        return rootPath + "\u0000" + getPath() + "\u0000"
                + (beforePath == null ? "" : beforePath) + "\u0000" + area;
    }

    public String getInclusionId() {
        String rootPath = vcsRoot == null ? "" : vcsRoot.getPath();
        return rootPath + "\u0000" + getPath() + "\u0000"
                + (beforePath == null ? "" : beforePath);
    }

    @Override
    public String toString() {
        return getName() + "  ·  " + (area == ChangeArea.NONE ? status : getAreaLabel() + " · " + status)
                + "  —  " + getParentPath();
    }
}
