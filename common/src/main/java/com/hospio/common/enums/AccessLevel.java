package com.hospio.common.enums;

/** Full · Can edit · View only. Ordered so {@code compareTo} answers "at least this level". */
public enum AccessLevel {
    VIEW, EDIT, FULL;

    public boolean atLeast(AccessLevel other) {
        return this.ordinal() >= other.ordinal();
    }
}
