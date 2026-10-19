package de.robv.android.xposed.callbacks;

import android.content.pm.ApplicationInfo;

/**
 * Callback for package/process load events (classic Xposed API).
 * Stub for compile-only validation; not a functional implementation.
 */
public final class XC_LoadPackage extends XCallback {

    public static final class LoadPackageParam extends XCallback.Param {
        public String packageName;
        public String processName;
        public ClassLoader classLoader;
        public ApplicationInfo appInfo;
        public boolean isFirstApplication;
    }
}
