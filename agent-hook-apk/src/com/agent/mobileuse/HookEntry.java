package com.agent.mobileuse;

import android.content.Context;
import android.content.Intent;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class HookEntry implements IXposedHookLoadPackage {

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        // Hook system_server (android / system)
        if ("android".equals(lpparam.packageName) || "system".equals(lpparam.packageName)) {
            hookSystemServer(lpparam);
        }
        // Hook SystemUI (ColorOS Fluid Cloud / HyperOS super island)
        if ("com.android.systemui".equals(lpparam.packageName)) {
            hookSystemUI(lpparam);
        }
        // Hook Xiaomi XMSF auth (HyperOS focus-notification whitelist verification)
        if ("com.xiaomi.xmsf".equals(lpparam.packageName)) {
            hookXmsf(lpparam);
        }
        // Hook self (SettingsActivity activation check)
        if ("com.agent.mobileuse".equals(lpparam.packageName)) {
            hookSelf(lpparam);
        }
    }

    private void hookSelf(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> clazz = XposedHelpers.findClass("com.agent.mobileuse.SettingsActivity", lpparam.classLoader);
            XposedBridge.hookAllMethods(clazz, "isModuleActive", XC_MethodReplacement.returnConstant(Boolean.TRUE));
            XposedBridge.log("[AgentMobileUseHook] isModuleActive hooked successfully!");
        } catch (Throwable t) {
            XposedBridge.log("[AgentMobileUseHook] Failed to hook isModuleActive: " + t.getMessage());
        }
    }

    private void hookSystemServer(XC_LoadPackage.LoadPackageParam lpparam) {
        XposedBridge.log("[AgentMobileUseHook] System server loaded: " + lpparam.packageName);
        ClassLoader cl = lpparam.classLoader;

        // Force allow hosting tasks on virtual display
        hookAllMethodsReturningTrue(cl, "android.view.Display", "canHostTasks");
        hookAllMethodsReturningTrue(cl, "com.android.server.wm.LogicalDisplay", "canHostTasksLocked");
        hookAllMethodsReturningTrue(cl, "com.android.server.wm.ActivityTaskSupervisor", "isCallerAllowedToLaunchOnDisplay");
        hookAllMethodsReturningTrue(cl, "com.android.server.wm.ActivityTaskSupervisor", "isCallerAllowedToLaunchOnTaskDisplayArea");
        hookAllMethodsReturningTrue(cl, "com.android.server.wm.ActivityTaskSupervisor", "canPlaceEntityOnDisplay");
        hookAllMethodsReturningTrue(cl, "com.android.server.wm.ActivityRecord", "canBeLaunchedOnDisplay");
        hookAllMethodsReturningTrue(cl, "com.android.server.wm.Task", "canBeLaunchedOnDisplay");
        hookAllMethodsReturningTrue(cl, "com.android.server.wm.RootWindowContainer", "canLaunchOnDisplay");
        hookAllMethodsReturningTrue(cl, "com.android.server.display.DisplayManagerService", "validatePackageName");

        // Isolate InputMethodManagerService (IME) to prevent soft keyboard popup on Display 0
        hookImmsDisplayIsolation(cl);

        // Exclude AgentMobileEdgeGlow window from screenshot capture
        hookEdgeGlowScreenshotExclusion(cl);

        // Intercept Action Button on OnePlus 13 (ColorOS) to launch DemoDialogActivity.
        // HyperOS has no equivalent: the Xiaomi AI button only honors system preset
        // mappings and emits no standard KeyEvent, so there is nothing to hook there.
        if (!isHyperOsRom()) {
            hookActionButton(cl);
        }
    }

    /** ROM probe: true for MIUI / HyperOS. */
    static boolean isHyperOsRom() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method get = sp.getMethod("get", String.class);
            Object miOs = get.invoke(null, "ro.mi.os.version.name");
            if (miOs instanceof String && !((String) miOs).trim().isEmpty()) {
                return true;
            }
            Object miui = get.invoke(null, "ro.miui.ui.version.name");
            if (miui instanceof String && !((String) miui).trim().isEmpty()) {
                return true;
            }
            String manufacturer = android.os.Build.MANUFACTURER;
            return manufacturer != null && "xiaomi".equalsIgnoreCase(manufacturer.trim());
        } catch (Throwable t) {
            return false;
        }
    }

    private void hookAllMethodsReturningTrue(ClassLoader cl, String className, String methodName) {
        try {
            Class<?> clazz = XposedHelpers.findClass(className, cl);
            XposedBridge.hookAllMethods(clazz, methodName, XC_MethodReplacement.returnConstant(Boolean.TRUE));
        } catch (Throwable t) {
            XposedBridge.log("[AgentMobileUseHook] Failed to hook " + className + "#" + methodName + ": " + t.getMessage());
        }
    }

    private void hookImmsDisplayIsolation(ClassLoader cl) {
        try {
            Class<?> imms = XposedHelpers.findClass("com.android.server.inputmethod.InputMethodManagerService", cl);
            XposedBridge.hookAllMethods(imms, "computeImeDisplayIdForTarget", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    int displayId = (int) param.args[0];
                    if (displayId != 0) {
                        param.setResult(displayId);
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("[AgentMobileUseHook] Failed to hook IMMS: " + t.getMessage());
        }
    }

    private static Object getFieldSafe(Object obj, String fieldName) {
        if (obj == null) return null;
        Class<?> cur = obj.getClass();
        while (cur != null) {
            try {
                java.lang.reflect.Field f = cur.getDeclaredField(fieldName);
                f.setAccessible(true);
                return f.get(obj);
            } catch (NoSuchFieldException e) {
                cur = cur.getSuperclass();
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    private void hookEdgeGlowScreenshotExclusion(ClassLoader cl) {
        try {
            Class<?> animatorClass = XposedHelpers.findClass("com.android.server.wm.WindowStateAnimator", cl);
            XposedBridge.hookAllMethods(animatorClass, "createSurfaceLocked", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Object win = getFieldSafe(param.thisObject, "mWin");
                    if (win == null) return;

                    Object attrsObj = getFieldSafe(win, "mAttrs");
                    if (!(attrsObj instanceof android.view.WindowManager.LayoutParams)) return;
                    android.view.WindowManager.LayoutParams attrs = (android.view.WindowManager.LayoutParams) attrsObj;

                    CharSequence title = attrs.getTitle();
                    if (title == null || !"AgentMobileEdgeGlow".equals(title.toString())) {
                        return;
                    }

                    Object sc = param.getResult();
                    if (sc == null) {
                        sc = getFieldSafe(param.thisObject, "mSurfaceControl");
                    }
                    if (sc == null) {
                        sc = getFieldSafe(win, "mSurfaceControl");
                    }
                    if (sc == null) return;

                    try {
                        Class<?> scClass = Class.forName("android.view.SurfaceControl");
                        Class<?> txClass = Class.forName("android.view.SurfaceControl$Transaction");
                        Object tx = txClass.getConstructor().newInstance();
                        java.lang.reflect.Method setSkipMethod;
                        try {
                            setSkipMethod = txClass.getMethod("setSkipScreenshot", scClass, boolean.class);
                        } catch (NoSuchMethodException e) {
                            setSkipMethod = txClass.getDeclaredMethod("setSkipScreenshot", scClass, boolean.class);
                            setSkipMethod.setAccessible(true);
                        }
                        setSkipMethod.invoke(tx, sc, true);
                        txClass.getMethod("apply").invoke(tx);
                        try {
                            txClass.getMethod("close").invoke(tx);
                        } catch (Throwable ignored) {}
                        XposedBridge.log("[AgentMobileUseHook] SKIP_SCREENSHOT applied to AgentMobileEdgeGlow successfully!");
                    } catch (Throwable t) {
                        XposedBridge.log("[AgentMobileUseHook] Failed to apply SKIP_SCREENSHOT: " + t.getMessage());
                    }
                }
            });
            XposedBridge.log("[AgentMobileUseHook] hookEdgeGlowScreenshotExclusion installed successfully!");
        } catch (Throwable t) {
            XposedBridge.log("[AgentMobileUseHook] Failed to hook hookEdgeGlowScreenshotExclusion: " + t.getMessage());
        }
    }

    private void hookActionButton(ClassLoader cl) {
        try {
            Class<?> strategyClass = XposedHelpers.findClass("com.android.server.policy.StrategyActionButtonKeyLaunchApp", cl);
            XposedBridge.hookAllMethods(strategyClass, "interceptActionKeyDown", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    android.util.Log.i("AgentMobileUseHook", "Action Button key down intercepted!");
                    Context ctx = null;
                    try {
                        Class<?> cur = param.thisObject.getClass();
                        while (cur != null && ctx == null) {
                            try {
                                java.lang.reflect.Field f = cur.getDeclaredField("mContext");
                                f.setAccessible(true);
                                ctx = (Context) f.get(param.thisObject);
                            } catch (NoSuchFieldException ignored) {
                                cur = cur.getSuperclass();
                            }
                        }
                    } catch (Throwable t) {
                        android.util.Log.w("AgentMobileUseHook", "Could not get mContext via reflection: " + t.getMessage());
                    }

                    if (ctx != null) {
                        try {
                            Intent intent = new Intent();
                            intent.setClassName("com.agent.mobileuse", "com.agent.mobileuse.DemoDialogActivity");
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                            ctx.startActivity(intent);
                            android.util.Log.i("AgentMobileUseHook", "DemoDialogActivity launched successfully from Action Button!");
                        } catch (Throwable t) {
                            android.util.Log.e("AgentMobileUseHook", "Failed to launch DemoDialogActivity: " + t.getMessage(), t);
                        }
                    }
                    param.setResult(null);
                }
            });

            XposedBridge.hookAllMethods(strategyClass, "interceptActionKeyUp", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    param.setResult(null);
                }
            });

            XposedBridge.log("[AgentMobileUseHook] StrategyActionButtonKeyLaunchApp hooked successfully!");
        } catch (Throwable t) {
            XposedBridge.log("[AgentMobileUseHook] Failed to hook StrategyActionButtonKeyLaunchApp: " + t.getMessage());
        }
    }

    private static Object invokeNoArg(Object obj, String methodName) {
        if (obj == null) return null;
        try {
            java.lang.reflect.Method m = obj.getClass().getMethod(methodName);
            m.setAccessible(true);
            return m.invoke(obj);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isValidAgentLiveAlert(Object sbn) {
        if (sbn == null) return false;
        try {
            String pkg = (String) invokeNoArg(sbn, "getPackageName");
            if (!"com.agent.mobileuse".equals(pkg)) return false;

            Integer id = (Integer) invokeNoArg(sbn, "getId");
            if (id == null || id == 0) return false;

            // Only whitelist our designated Fluid Cloud notification IDs:
            // 10086: GlowService (Capsule: "运行中", "后台接管", "前台接管")
            // 2020:  NotifyReceiver (Task completed card)
            // 20086: QuestionReceiver (Interactive question card)
            if (id != 10086 && id != 2020 && id != 20086) return false;

            Object notif = invokeNoArg(sbn, "getNotification");
            if (notif instanceof android.app.Notification) {
                android.app.Notification n = (android.app.Notification) notif;
                // Exclude Android system AutoGroupSummary notifications (FLAG_GROUP_SUMMARY = 0x200)
                if ((n.flags & 0x00000200) != 0) return false;
                if (n.extras != null) {
                    // Check user personalization switch: Fluid Cloud conversion
                    if (!n.extras.getBoolean("enable_fluid_cloud", true)) {
                        return false;
                    }
                    CharSequence title = n.extras.getCharSequence(android.app.Notification.EXTRA_TITLE);
                    if (title == null || title.toString().trim().isEmpty()) return false;
                }
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private void hookSystemUI(XC_LoadPackage.LoadPackageParam lpparam) {
        XposedBridge.log("[AgentMobileUseHook] SystemUI loaded: " + lpparam.packageName);
        ClassLoader cl = lpparam.classLoader;

        if (isHyperOsRom()) {
            hookHyperOsSystemUI(lpparam);
            return;
        }

        // 1. Hook OplusLiveAlertFilters.shouldFilter to allow designated com.agent.mobileuse notifications
        try {
            Class<?> filtersClass = XposedHelpers.findClass(
                "com.oplus.systemui.statusbar.notification.livealert.data.repository.OplusLiveAlertFilters",
                cl
            );
            XposedBridge.hookAllMethods(filtersClass, "shouldFilter", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.args != null && param.args.length > 0 && param.args[0] != null) {
                        Object entry = param.args[0];
                        try {
                            Object sbn = invokeNoArg(entry, "getSbn");
                            if (sbn != null) {
                                String pkg = (String) invokeNoArg(sbn, "getPackageName");
                                if ("com.agent.mobileuse".equals(pkg)) {
                                    if (isValidAgentLiveAlert(sbn)) {
                                        param.setResult(Boolean.TRUE);
                                    } else {
                                        param.setResult(Boolean.FALSE);
                                    }
                                }
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            });
            XposedBridge.log("[AgentMobileUseHook] OplusLiveAlertFilters.shouldFilter hooked with whitelist filtering!");
        } catch (Throwable t) {
            XposedBridge.log("[AgentMobileUseHook] Failed to hook OplusLiveAlertFilters: " + t.getMessage());
        }

        // 2. Hook OplusLiveAlertFilterByPlugin.shouldFilter (Double insurance)
        try {
            Class<?> pluginFilterClass = XposedHelpers.findClass(
                "com.oplus.systemui.statusbar.notification.livealert.data.repository.OplusLiveAlertFilterByPlugin",
                cl
            );
            XposedBridge.hookAllMethods(pluginFilterClass, "shouldFilter", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.args != null && param.args.length > 0 && param.args[0] != null) {
                        Object entry = param.args[0];
                        try {
                            Object sbn = invokeNoArg(entry, "getSbn");
                            if (sbn != null) {
                                String pkg = (String) invokeNoArg(sbn, "getPackageName");
                                if ("com.agent.mobileuse".equals(pkg)) {
                                    if (isValidAgentLiveAlert(sbn)) {
                                        param.setResult(Boolean.TRUE);
                                    } else {
                                        param.setResult(Boolean.FALSE);
                                    }
                                }
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            });
            XposedBridge.log("[AgentMobileUseHook] OplusLiveAlertFilterByPlugin.shouldFilter hooked with whitelist filtering!");
        } catch (Throwable t) {
            XposedBridge.log("[AgentMobileUseHook] Failed to hook OplusLiveAlertFilterByPlugin: " + t.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // HyperOS / MIUI super island (焦点通知 / 超级岛)
    // ------------------------------------------------------------------

    /**
     * Unlock focus notifications for com.agent.mobileuse on HyperOS.
     *
     * <p>Two independent gates exist and both must return true:
     * <ol>
     *   <li>{@code miui.systemui.notification.NotificationSettingsManager
     *       #canShowFocus / #canCustomFocus} - the per-app permission check.</li>
     *   <li>{@code miui.systemui.notification.focus.SignatureChecker
     *       #checkSignatures} - the signature/partner whitelist inside the
     *       focus-notification SystemUI plugin (HyperOS 3+).</li>
     * </ol>
     * On newer ROMs those classes are loaded by a plugin ClassLoader, so the same
     * hooks are re-applied from {@code PluginFactory#createPluginContext}.
     */
    private void hookHyperOsSystemUI(XC_LoadPackage.LoadPackageParam lpparam) {
        ClassLoader cl = lpparam.classLoader;
        hookFocusGates(cl, false);

        // Never let the focus-notification pipeline drop our own notification.
        // FocusNotificationPluginImpl#onNotificationPosted returns true = drop.
        try {
            Class<?> impl = XposedHelpers.findClass(
                    "miui.systemui.notification.FocusNotificationPluginImpl", cl);
            XposedBridge.hookAllMethods(impl, "onNotificationPosted", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.args == null || param.args.length == 0 || param.args[0] == null) {
                        return;
                    }
                    String pkg = (String) invokeNoArg(param.args[0], "getPackageName");
                    if ("com.agent.mobileuse".equals(pkg)) {
                        param.setResult(Boolean.FALSE); // false = keep this notification
                    }
                }
            });
            XposedBridge.log("[AgentMobileUseHook] FocusNotificationPluginImpl.onNotificationPosted hooked");
        } catch (Throwable t) {
            XposedBridge.log("[AgentMobileUseHook] FocusNotificationPluginImpl hook skipped: " + t.getMessage());
        }

        // OS3+ ships the focus-notification UI as a SystemUI plugin whose classes
        // live in a child ClassLoader; install the gates again on that loader.
        try {
            Class<?> factory = XposedHelpers.findClass(
                    "com.android.systemui.shared.plugins.PluginInstance$PluginFactory", cl);
            XposedBridge.hookAllMethods(factory, "createPluginContext", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Object pluginContext = param.getResult();
                    if (pluginContext == null) {
                        return;
                    }
                    Object loader = invokeNoArg(pluginContext, "getClassLoader");
                    if (loader instanceof ClassLoader) {
                        hookFocusGates((ClassLoader) loader, true);
                    }
                }
            });
            XposedBridge.log("[AgentMobileUseHook] PluginFactory.createPluginContext hooked for focus plugin loader");
        } catch (Throwable t) {
            XposedBridge.log("[AgentMobileUseHook] PluginFactory hook skipped: " + t.getMessage());
        }
    }

    /** Install the canShowFocus / checkSignatures gates on one ClassLoader. */
    private void hookFocusGates(ClassLoader cl, boolean quiet) {
        hookAllMethodsReturningTrueQuiet(cl,
                "miui.systemui.notification.NotificationSettingsManager", "canShowFocus", quiet);
        hookAllMethodsReturningTrueQuiet(cl,
                "miui.systemui.notification.NotificationSettingsManager", "canCustomFocus", quiet);
        hookAllMethodsReturningTrueQuiet(cl,
                "miui.systemui.notification.focus.SignatureChecker", "checkSignatures", quiet);
    }

    /** Same as {@link #hookAllMethodsReturningTrue} but failure logging is optional. */
    private void hookAllMethodsReturningTrueQuiet(ClassLoader cl, String className,
                                                  String methodName, boolean quiet) {
        try {
            Class<?> clazz = XposedHelpers.findClass(className, cl);
            XposedBridge.hookAllMethods(clazz, methodName, XC_MethodReplacement.returnConstant(Boolean.TRUE));
            XposedBridge.log("[AgentMobileUseHook] " + className + "#" + methodName + " -> true");
        } catch (Throwable t) {
            if (!quiet) {
                XposedBridge.log("[AgentMobileUseHook] Failed to hook " + className + "#"
                        + methodName + ": " + t.getMessage());
            }
        }
    }

    /**
     * XMSF half of the focus-notification whitelist: {@code
     * com.xiaomi.xms.auth.AuthSession} verifies the partner signature and calls
     * the failure callback {@code b(Throwable)}. Clearing the error code field
     * {@code a} and invoking the success callback {@code h()} makes verification
     * pass, mirroring the well-known HyperIsland approach.
     */
    private void hookXmsf(XC_LoadPackage.LoadPackageParam lpparam) {
        XposedBridge.log("[AgentMobileUseHook] XMSF loaded, hooking focus auth: " + lpparam.packageName);
        try {
            Class<?> authSession = XposedHelpers.findClass("com.xiaomi.xms.auth.AuthSession",
                    lpparam.classLoader);
            XposedBridge.hookAllMethods(authSession, "b", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.args == null || param.args.length != 1
                            || !(param.args[0] instanceof Throwable)) {
                        return;
                    }
                    Object session = param.thisObject;
                    try {
                        java.lang.reflect.Field codeField = findFieldInHierarchy(session.getClass(), "a");
                        if (codeField != null && codeField.getType() == int.class) {
                            codeField.setAccessible(true);
                            codeField.setInt(session, 0);
                        }
                    } catch (Throwable ignored) {}
                    try {
                        java.lang.reflect.Method success = findMethodInHierarchy(session.getClass(), "h");
                        if (success != null) {
                            success.setAccessible(true);
                            param.setResult(success.invoke(session));
                        }
                    } catch (Throwable ignored) {}
                }
            });
            XposedBridge.log("[AgentMobileUseHook] com.xiaomi.xms.auth.AuthSession#b hooked");
        } catch (Throwable t) {
            XposedBridge.log("[AgentMobileUseHook] Failed to hook XMSF AuthSession: " + t.getMessage());
        }
    }

    /** Find a declared field on the class or any superclass. */
    private static java.lang.reflect.Field findFieldInHierarchy(Class<?> clazz, String name) {
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (Throwable ignored) {}
        }
        return null;
    }

    /** Find a declared no-arg method on the class or any superclass. */
    private static java.lang.reflect.Method findMethodInHierarchy(Class<?> clazz, String name) {
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredMethod(name);
            } catch (Throwable ignored) {}
        }
        return null;
    }
}
