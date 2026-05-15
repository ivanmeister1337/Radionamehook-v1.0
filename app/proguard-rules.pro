# Сохраняем Xposed entry-point
-keep class com.belgee.radionamehook.RadioNameHookEntry { *; }
-keep class * implements de.robv.android.xposed.IXposedHookLoadPackage { *; }
