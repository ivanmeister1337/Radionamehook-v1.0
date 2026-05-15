package de.robv.android.xposed;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Стаб XposedHelpers - удобные методы для рефлексии.
 * В runtime LSPosed подменяет на реальную реализацию.
 */
public final class XposedHelpers {
    private XposedHelpers() {}

    public static Class<?> findClass(String className, ClassLoader classLoader) {
        try {
            return Class.forName(className, false, classLoader);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    public static Class<?> findClassIfExists(String className, ClassLoader classLoader) {
        try {
            return Class.forName(className, false, classLoader);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    public static XC_MethodHook.Unhook findAndHookMethod(Class<?> clazz, String methodName, Object... args) {
        throw new RuntimeException("Stub!");
    }

    public static XC_MethodHook.Unhook findAndHookMethod(String className, ClassLoader classLoader,
                                                          String methodName, Object... args) {
        throw new RuntimeException("Stub!");
    }

    public static XC_MethodHook.Unhook findAndHookConstructor(Class<?> clazz, Object... args) {
        throw new RuntimeException("Stub!");
    }

    public static Method findMethodExact(Class<?> clazz, String methodName, Class<?>... parameterTypes) {
        throw new RuntimeException("Stub!");
    }

    public static Method findMethodBestMatch(Class<?> clazz, String methodName, Object... args) {
        throw new RuntimeException("Stub!");
    }

    public static Object getObjectField(Object obj, String fieldName) {
        try {
            Field f = obj.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            return f.get(obj);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static void setObjectField(Object obj, String fieldName, Object value) {
        try {
            Field f = obj.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            f.set(obj, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static Object callMethod(Object obj, String methodName, Object... args) {
        throw new RuntimeException("Stub!");
    }

    public static Object callStaticMethod(Class<?> clazz, String methodName, Object... args) {
        throw new RuntimeException("Stub!");
    }

    public static Object newInstance(Class<?> clazz, Object... args) {
        throw new RuntimeException("Stub!");
    }
}
