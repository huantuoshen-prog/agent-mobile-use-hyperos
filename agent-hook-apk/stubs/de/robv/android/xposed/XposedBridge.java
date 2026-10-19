package de.robv.android.xposed;

import java.lang.reflect.Constructor;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;

/**
 * Core Xposed bridge (classic Xposed API).
 * Stub for compile-only validation; not a functional implementation.
 */
public final class XposedBridge {

    public static final int LOGLEVEL_NONE = 0;
    public static final int LOGLEVEL_ERROR = 1;
    public static final int LOGLEVEL_INFO = 2;
    public static final int LOGLEVEL_DEBUG = 3;

    private XposedBridge() {
    }

    /** Log a message to the Xposed log. */
    public static void log(String text) {
    }

    /** Log a throwable to the Xposed log. */
    public static void log(Throwable t) {
    }

    /** Hooks all methods with the given name in the given class. */
    public static Set<XC_MethodHook.Unhook> hookAllMethods(Class<?> hookClass, String methodName,
            XC_MethodHook callback) {
        return Collections.emptySet();
    }

    /** Hooks all constructors of the given class. */
    public static Set<XC_MethodHook.Unhook> hookAllConstructors(Class<?> hookClass, XC_MethodHook callback) {
        return Collections.emptySet();
    }

    /** Hooks the given method or constructor. */
    public static XC_MethodHook.Unhook hookMethod(Member hookMethod, XC_MethodHook callback) {
        return null;
    }

    /** Hooks the given method if it exists, otherwise returns null. */
    public static XC_MethodHook.Unhook hookMethodIfExists(Member hookMethod, XC_MethodHook callback) {
        return null;
    }

    public static void deoptimizeMethod(Member method) {
    }

    public static void deoptimizeMethod(Member method, boolean isStatic) {
    }

    public static Constructor<?> findConstructorExact(Class<?> clazz, Class<?>... parameterTypes) {
        return null;
    }

    public static Method findMethodExact(Class<?> clazz, String methodName, Class<?>... parameterTypes) {
        return null;
    }
}
