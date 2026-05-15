package de.robv.android.xposed.callbacks;

import android.content.pm.ApplicationInfo;

public abstract class XC_LoadPackage {

    public abstract void handleLoadPackage(LoadPackageParam lpparam) throws Throwable;

    public static class LoadPackageParam {
        public String packageName;
        public String processName;
        public ClassLoader classLoader;
        public ApplicationInfo appInfo;
        public boolean isFirstApplication;
    }
}
