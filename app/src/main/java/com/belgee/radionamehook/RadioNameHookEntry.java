package com.belgee.radionamehook;

import android.util.Log;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Главная точка входа LSPosed модуля.
 * Запускается каждый раз когда загружается процесс из scope (см. arrays.xml).
 *
 * Стратегия hook'а:
 * 1. Hook на класс MusicPlaybackInfoImpl - на любые setRadioStationName/setTitle
 *    подставляем значение из базы (если оно пустое)
 * 2. Hook на RadioInfo - на getName() возвращаем имя если оно null
 * 3. На случай если первое не сработает - hook на toString() для отладки
 */
public class RadioNameHookEntry implements IXposedHookLoadPackage {
    private static final String TAG = "RadioNameHook";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        // Реагируем на NSMedia процесс И на виджет (он в другом процессе)
        boolean isNSMedia = "com.ecarx.multimedia".equals(lpparam.packageName);
        boolean isWidget = "ecarx.xsf.widget".equals(lpparam.packageName);

        if (!isNSMedia && !isWidget) {
            return;
        }

        XposedBridge.log(TAG + ": loaded into " + lpparam.packageName);
        Log.i(TAG, "Hook entry loaded into " + lpparam.packageName);

        // Инициализируем базу станций
        StationsDatabase.init();

        // АВТОПОИСК: ищем ВСЕ классы с методами setRadioStationName/getRadioStationName
        // и хукаем ВСЕ найденные сразу
        scanAndHookAll(lpparam);

        // Дополнительный hook на RadioInfo (резервно)
        hookRadioInfoGetName(lpparam);
    }

    /**
     * Идёт по всем классам APK, находит те у которых есть set/getRadioStationName,
     * и ставит hook на каждый сразу. Не нужно угадывать имена классов.
     */
    private void scanAndHookAll(XC_LoadPackage.LoadPackageParam lpparam) {
        boolean dexScanWorked = false;
        try {
            XposedBridge.log(TAG + ": ===== SCAN AND HOOK =====");
            dalvik.system.DexFile dex = new dalvik.system.DexFile(lpparam.appInfo.sourceDir);
            java.util.Enumeration<String> entries = dex.entries();
            ClassLoader cl = lpparam.classLoader;
            int hookedCount = 0;

            while (entries.hasMoreElements()) {
                String className = entries.nextElement();
                dexScanWorked = true; // dex открылся, идем дальше

                // Только наши пакеты
                if (!className.startsWith("com.ecarx.")
                        && !className.startsWith("ecarx.")
                        && !className.startsWith("com.neusoft.")) {
                    continue;
                }
                if (className.contains(".R$") || className.endsWith(".R")
                        || className.endsWith(".BuildConfig")) {
                    continue;
                }

                try {
                    Class<?> c = cl.loadClass(className);
                    boolean hooked = false;
                    boolean hasFrequencyField = hasFrequencyField(c);

                    for (Method m : c.getDeclaredMethods()) {
                        String mn = m.getName();
                        if (mn.equals("setRadioStationName") && m.getParameterCount() == 1
                                && m.getParameterTypes()[0] == String.class) {
                            hookSetMethod(c, className);
                            hookedCount++;
                            hooked = true;
                        }
                        if (mn.equals("getRadioStationName") && m.getParameterCount() == 0
                                && m.getReturnType() == String.class) {
                            hookGetMethod(c, className);
                            hookedCount++;
                            hooked = true;
                        }
                        // ВАЖНО: Если у класса есть поле frequency/freq -
                        // также hook на getName() и getStationName()
                        // (RadioInfo, IFrequency, StationInfo и т.п.)
                        if (hasFrequencyField && m.getParameterCount() == 0
                                && m.getReturnType() == String.class
                                && (mn.equals("getName") || mn.equals("getStationName")
                                    || mn.equals("getLastRadioName"))) {
                            hookGenericNameGetter(c, className, mn);
                            hookedCount++;
                            hooked = true;
                        }
                    }

                    if (hooked) {
                        XposedBridge.log(TAG + ": ✓ " + className);
                    }
                } catch (Throwable t) { /* skip */ }
            }
            XposedBridge.log(TAG + ": ===== HOOKED " + hookedCount + " methods =====");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": dex scan error: " + t.getMessage());
        }

        // Если dex scan не сработал (как для виджета с odex/vdex),
        // пробуем перебрать известные имена классов через classloader
        if (!dexScanWorked) {
            XposedBridge.log(TAG + ": dex scan failed, trying known class names");
            tryKnownWidgetClasses(lpparam);
        }
    }

    /**
     * Проверяет есть ли в классе поле с частотой (frequency/freq/mFrequency)
     */
    private boolean hasFrequencyField(Class<?> c) {
        String[] fieldNames = {
            "frequency", "freq", "mFrequency", "mFreq",
            "radioFrequency", "mRadioFrequency", "mLastRadioFreq"
        };
        for (String name : fieldNames) {
            try {
                c.getDeclaredField(name);
                return true;
            } catch (Throwable t) { /* try next */ }
        }
        return false;
    }

    /**
     * Hook на любой getter имени станции (getName, getStationName и т.п.)
     */
    private void hookGenericNameGetter(Class<?> targetClass, final String classNameForLog,
                                        final String methodName) {
        try {
            XposedHelpers.findAndHookMethod(targetClass, methodName,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        String result = (String) param.getResult();
                        if (result == null || result.isEmpty()) {
                            String freq = readFreqFromObject(param.thisObject);
                            String found = StationsDatabase.findName(freq);
                            if (found != null) {
                                param.setResult(found);
                                XposedBridge.log(TAG + ": >>> " + classNameForLog
                                    + "." + methodName + "() '' -> '" + found
                                    + "' (freq=" + freq + ")");
                            }
                        }
                    }
                });
        } catch (Throwable t) { /* skip */ }
    }

    /**
     * Для виджета (NSMediaWidget) - dex scan не работает из-за odex/vdex.
     * Пробуем известные имена классов виджета напрямую.
     */
    private void tryKnownWidgetClasses(XC_LoadPackage.LoadPackageParam lpparam) {
        // Из логов мы знаем что виджет имеет класс MusicPlayInfoHolder (без "back")
        // с полем mLastUiRadioName. Пытаемся его найти под разными пакетами:
        String[] possibleNames = {
            "ecarx.xsf.widget.MusicPlayInfoHolder",
            "ecarx.xsf.widget.holder.MusicPlayInfoHolder",
            "ecarx.xsf.widget.bean.MusicPlayInfoHolder",
            "ecarx.xsf.widget.remoteviews.MusicPlayInfoHolder",
            "ecarx.xsf.widget.data.MusicPlayInfoHolder",
            "com.ecarx.xsf.widget.MusicPlayInfoHolder",
            // Также RemoteViews которые рисуют имя
            "ecarx.xsf.widget.remoteviews.UnfoldRadioWidgetRemoteViews",
            "ecarx.xsf.widget.remoteviews.RadioWidgetRemoteViews"
        };

        ClassLoader cl = lpparam.classLoader;
        for (String name : possibleNames) {
            try {
                Class<?> c = cl.loadClass(name);
                XposedBridge.log(TAG + ": ✓ widget class found: " + name);
                // Перехватываем ВСЕ публичные методы класса для диагностики
                hookAllStringSettersAndGetters(c, name);
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": widget class not found: " + name);
            }
        }
    }

    /**
     * Хукает на классе все методы set*Name(String) и get*Name() - для диагностики.
     */
    private void hookAllStringSettersAndGetters(Class<?> targetClass, final String classNameForLog) {
        for (Method m : targetClass.getDeclaredMethods()) {
            String mn = m.getName();
            try {
                // setXxxName(String)
                if ((mn.startsWith("set") && mn.contains("Name"))
                        && m.getParameterCount() == 1
                        && m.getParameterTypes()[0] == String.class) {
                    final String finalMn = mn;
                    XposedHelpers.findAndHookMethod(targetClass, mn, String.class,
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                String newName = (String) param.args[0];
                                if (newName == null || newName.isEmpty()) {
                                    String freq = readFreqFromObject(param.thisObject);
                                    String found = StationsDatabase.findName(freq);
                                    if (found != null) {
                                        param.args[0] = found;
                                        XposedBridge.log(TAG + ": >>> WIDGET "
                                            + classNameForLog + "." + finalMn
                                            + "('') -> '" + found + "' freq=" + freq);
                                    } else {
                                        XposedBridge.log(TAG + ": [widget] " + finalMn
                                            + " called with empty, freq=" + freq);
                                    }
                                }
                            }
                        });
                    XposedBridge.log(TAG + ":   widget hook: " + mn);
                }
                // getXxxName() returns String
                if ((mn.startsWith("get") && mn.contains("Name"))
                        && m.getParameterCount() == 0
                        && m.getReturnType() == String.class) {
                    final String finalMn = mn;
                    XposedHelpers.findAndHookMethod(targetClass, mn,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                String result = (String) param.getResult();
                                if (result == null || result.isEmpty()) {
                                    String freq = readFreqFromObject(param.thisObject);
                                    String found = StationsDatabase.findName(freq);
                                    if (found != null) {
                                        param.setResult(found);
                                        XposedBridge.log(TAG + ": >>> WIDGET "
                                            + classNameForLog + "." + finalMn
                                            + "() '' -> '" + found + "' freq=" + freq);
                                    }
                                }
                            }
                        });
                    XposedBridge.log(TAG + ":   widget hook: " + mn);
                }
            } catch (Throwable t) { /* skip */ }
        }
    }

    private void hookSetMethod(Class<?> targetClass, final String classNameForLog) {
        try {
            XposedHelpers.findAndHookMethod(targetClass, "setRadioStationName", String.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        String newName = (String) param.args[0];
                        if (newName == null || newName.isEmpty()) {
                            String freq = readFreqFromObject(param.thisObject);
                            String found = StationsDatabase.findName(freq);
                            if (found != null) {
                                param.args[0] = found;
                                XposedBridge.log(TAG + ": >>> set on " + classNameForLog
                                    + ": '' -> '" + found + "' (freq=" + freq + ")");
                            } else {
                                XposedBridge.log(TAG + ": set on " + classNameForLog
                                    + ": empty, freq=" + freq + ", no match in DB");
                            }
                        }
                    }
                });
        } catch (Throwable t) { /* skip */ }
    }

    private void hookGetMethod(Class<?> targetClass, final String classNameForLog) {
        try {
            XposedHelpers.findAndHookMethod(targetClass, "getRadioStationName",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        String result = (String) param.getResult();
                        if (result == null || result.isEmpty()) {
                            String freq = readFreqFromObject(param.thisObject);
                            String found = StationsDatabase.findName(freq);
                            if (found != null) {
                                param.setResult(found);
                                XposedBridge.log(TAG + ": >>> get on " + classNameForLog
                                    + "(): '' -> '" + found + "' (freq=" + freq + ")");
                            }
                        }
                    }
                });
        } catch (Throwable t) { /* skip */ }
    }


    /**
     * Резервный hook на RadioInfo.getName() - возвращаем имя если оно null/пусто
     */
    private void hookRadioInfoGetName(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> radioInfoClass = XposedHelpers.findClass(
                "com.ecarx.common.bean.radio.RadioInfo", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(radioInfoClass, "getName",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        String result = (String) param.getResult();
                        if (result == null || result.isEmpty()) {
                            String freq = readFreqFromObject(param.thisObject);
                            String found = StationsDatabase.findName(freq);
                            if (found != null) {
                                param.setResult(found);
                                XposedBridge.log(TAG + ": >>> RadioInfo.getName() '' -> '"
                                    + found + "' (freq=" + freq + ")");
                            }
                        }
                    }
                });
            XposedBridge.log(TAG + ": ✓ hooked RadioInfo.getName");
        } catch (Throwable t) {
            // class not exists in widget process - ok
        }
    }


    /**
     * Универсальный метод - пытаемся достать частоту из объекта.
     * Может быть в полях: mLastRadioFreq, mRadioFrequency, frequency, freq
     */
    private static String readFreqFromObject(Object obj) {
        if (obj == null) return null;

        String[] candidateFields = {
            "mLastRadioFreq", "mRadioFrequency", "radioFrequency",
            "frequency", "freq", "mFrequency", "mFreq",
            "mLastRadioFrequency", "mMeidaId" // да, в логах было "mMeidaId='103.7'"
        };

        for (String fieldName : candidateFields) {
            try {
                Field f = obj.getClass().getDeclaredField(fieldName);
                f.setAccessible(true);
                Object val = f.get(obj);
                if (val != null) {
                    String s = val.toString();
                    if (!s.isEmpty() && !s.equals("0")) {
                        return s;
                    }
                }
            } catch (Throwable t) {
                // try next field
            }
        }

        // Не нашли через поля - пробуем геттер
        String[] candidateGetters = {
            "getRadioFrequency", "getFrequency", "getFreq",
            "getMeidaId", "getMediaId"
        };
        for (String getterName : candidateGetters) {
            try {
                Method m = obj.getClass().getMethod(getterName);
                Object val = m.invoke(obj);
                if (val != null) {
                    String s = val.toString();
                    if (!s.isEmpty() && !s.equals("0")) {
                        return s;
                    }
                }
            } catch (Throwable t) {
                // try next
            }
        }

        return null;
    }
}
