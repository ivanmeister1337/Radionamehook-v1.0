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
        try {
            XposedBridge.log(TAG + ": ===== SCAN AND HOOK =====");
            dalvik.system.DexFile dex = new dalvik.system.DexFile(lpparam.appInfo.sourceDir);
            java.util.Enumeration<String> entries = dex.entries();
            ClassLoader cl = lpparam.classLoader;
            int hookedCount = 0;

            while (entries.hasMoreElements()) {
                String className = entries.nextElement();

                // Только наши пакеты
                if (!className.startsWith("com.ecarx.")
                        && !className.startsWith("ecarx.")
                        && !className.startsWith("com.neusoft.")) {
                    continue;
                }
                // Skip noisy
                if (className.contains(".R$") || className.endsWith(".R")
                        || className.endsWith(".BuildConfig")) {
                    continue;
                }

                try {
                    Class<?> c = cl.loadClass(className);
                    boolean hooked = false;

                    for (Method m : c.getDeclaredMethods()) {
                        String mn = m.getName();
                        // Hook на setRadioStationName(String)
                        if (mn.equals("setRadioStationName") && m.getParameterCount() == 1
                                && m.getParameterTypes()[0] == String.class) {
                            hookSetMethod(c, className);
                            hookedCount++;
                            hooked = true;
                        }
                        // Hook на getRadioStationName()
                        if (mn.equals("getRadioStationName") && m.getParameterCount() == 0
                                && m.getReturnType() == String.class) {
                            hookGetMethod(c, className);
                            hookedCount++;
                            hooked = true;
                        }
                    }

                    if (hooked) {
                        XposedBridge.log(TAG + ": ✓ " + className);
                    }
                } catch (Throwable t) {
                    // load error - skip
                }
            }
            XposedBridge.log(TAG + ": ===== HOOKED " + hookedCount + " methods total =====");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": scanAndHookAll error: " + t.getMessage());
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
