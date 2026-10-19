package de.robv.android.xposed.callbacks;

/**
 * Base class of Xposed callbacks (classic Xposed API).
 * Stub for compile-only validation; not a functional implementation.
 */
public abstract class XCallback implements Comparable<XCallback> {

    /** Base class for callback parameters. */
    public static class Param {
    }

    @Override
    public int compareTo(XCallback other) {
        return 0;
    }
}
