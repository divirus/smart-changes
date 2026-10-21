package dev.changestree.smartchanges.model;

/** The Git staging side represented by a row, when the IDE's staging area is enabled. */
public enum ChangeArea {
    NONE,
    STAGED,
    UNSTAGED,
    CONFLICTED
}
