package dev.changestree.smartchanges.model;

public enum SortMode {
    NAME("Name"),
    TYPE("Type"),
    PATH("Path"),
    MODIFIED("Modified");

    private final String label;

    SortMode(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public static SortMode forColumn(int column) {
        return switch (column) {
            case 0 -> NAME;
            case 1 -> TYPE;
            case 2 -> PATH;
            case 3 -> MODIFIED;
            default -> NAME;
        };
    }

    @Override
    public String toString() {
        return label;
    }
}
