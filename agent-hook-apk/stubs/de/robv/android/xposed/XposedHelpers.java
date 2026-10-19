package de.robv.android.xposed;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Reflection helpers of the classic Xposed API.
 * Stub for compile-only validation; not a functional implementation.
 */
public final class XposedHelpers {

    private XposedHelpers() {
    }

    /* ---------- class lookup ---------- */

    public static Class<?> findClass(String className, ClassLoader classLoader) {
        return null;
    }

    public static Class<?> findClassIfExists(String className, ClassLoader classLoader) {
        return null;
    }

    /* ---------- fields: instance ---------- */

    public static Field findField(Class<?> clazz, String fieldName) {
        return null;
    }

    public static Field findFieldIfExists(Class<?> clazz, String fieldName) {
        return null;
    }

    public static Object getObjectField(Object obj, String fieldName) {
        return null;
    }

    public static boolean getBooleanField(Object obj, String fieldName) {
        return false;
    }

    public static byte getByteField(Object obj, String fieldName) {
        return 0;
    }

    public static char getCharField(Object obj, String fieldName) {
        return 0;
    }

    public static short getShortField(Object obj, String fieldName) {
        return 0;
    }

    public static int getIntField(Object obj, String fieldName) {
        return 0;
    }

    public static long getLongField(Object obj, String fieldName) {
        return 0L;
    }

    public static float getFloatField(Object obj, String fieldName) {
        return 0f;
    }

    public static double getDoubleField(Object obj, String fieldName) {
        return 0d;
    }

    public static void setObjectField(Object obj, String fieldName, Object value) {
    }

    public static void setBooleanField(Object obj, String fieldName, boolean value) {
    }

    public static void setByteField(Object obj, String fieldName, byte value) {
    }

    public static void setCharField(Object obj, String fieldName, char value) {
    }

    public static void setShortField(Object obj, String fieldName, short value) {
    }

    public static void setIntField(Object obj, String fieldName, int value) {
    }

    public static void setLongField(Object obj, String fieldName, long value) {
    }

    public static void setFloatField(Object obj, String fieldName, float value) {
    }

    public static void setDoubleField(Object obj, String fieldName, double value) {
    }

    /* ---------- fields: static ---------- */

    public static Object getStaticObjectField(Class<?> clazz, String fieldName) {
        return null;
    }

    public static boolean getStaticBooleanField(Class<?> clazz, String fieldName) {
        return false;
    }

    public static int getStaticIntField(Class<?> clazz, String fieldName) {
        return 0;
    }

    public static void setStaticObjectField(Class<?> clazz, String fieldName, Object value) {
    }

    public static void setStaticBooleanField(Class<?> clazz, String fieldName, boolean value) {
    }

    public static void setStaticIntField(Class<?> clazz, String fieldName, int value) {
    }

    /* ---------- methods ---------- */

    public static Method findMethodExact(Class<?> clazz, String methodName, Object... parameterTypes) {
        return null;
    }

    public static Method findMethodExact(Class<?> clazz, String methodName, Class<?>... parameterTypes) {
        return null;
    }

    public static Method findMethodExactIfExists(Class<?> clazz, String methodName, Object... parameterTypes) {
        return null;
    }

    public static Method findMethodBestMatch(Class<?> clazz, String methodName, Object... args) {
        return null;
    }

    public static Method findMethodBestMatch(Class<?> clazz, String methodName, Class<?>[] parameterTypes,
            Object[] args) {
        return null;
    }

    public static Constructor<?> findConstructorExact(Class<?> clazz, Object... parameterTypes) {
        return null;
    }

    public static Constructor<?> findConstructorExact(Class<?> clazz, Class<?>... parameterTypes) {
        return null;
    }

    public static Object callMethod(Object obj, String methodName, Object... args) {
        return null;
    }

    public static Object callMethod(Object obj, String methodName, Class<?>[] parameterTypes, Object... args) {
        return null;
    }

    public static Object callStaticMethod(Class<?> clazz, String methodName, Object... args) {
        return null;
    }

    public static Object callStaticMethod(Class<?> clazz, String methodName, Class<?>[] parameterTypes,
            Object... args) {
        return null;
    }

    /* ---------- construction ---------- */

    public static Object newInstance(Class<?> clazz, Object... args) {
        return null;
    }

    public static Object newInstance(Class<?> clazz, Class<?>[] parameterTypes, Object... args) {
        return null;
    }

    /* ---------- additional (per-object) instance fields ---------- */

    public static Object getAdditionalInstanceField(Object obj, String key) {
        return null;
    }

    public static Object setAdditionalInstanceField(Object obj, String key, Object value) {
        return null;
    }

    public static Object removeAdditionalInstanceField(Object obj, String key) {
        return null;
    }

    public static Object getAdditionalStaticField(Object obj, String key) {
        return null;
    }

    public static Object setAdditionalStaticField(Object obj, String key, Object value) {
        return null;
    }

    public static Object removeAdditionalStaticField(Object obj, String key) {
        return null;
    }
}
