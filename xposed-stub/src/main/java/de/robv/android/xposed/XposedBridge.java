package de.robv.android.xposed;

import java.lang.reflect.Member;

/**
 * Стаб Xposed API.
 * В runtime LSPosed подменяет вызовы на реальные.
 */
public final class XposedBridge {
    private XposedBridge() {}

    public static void log(String text) {
        throw new RuntimeException("Stub! LSPosed framework not loaded");
    }

    public static void log(Throwable t) {
        throw new RuntimeException("Stub! LSPosed framework not loaded");
    }

    public static XC_MethodHook.Unhook hookMethod(Member m, XC_MethodHook callback) {
        throw new RuntimeException("Stub! LSPosed framework not loaded");
    }

    public static java.util.Set<XC_MethodHook.Unhook> hookAllMethods(Class<?> clazz, String methodName, XC_MethodHook callback) {
        throw new RuntimeException("Stub! LSPosed framework not loaded");
    }
}
