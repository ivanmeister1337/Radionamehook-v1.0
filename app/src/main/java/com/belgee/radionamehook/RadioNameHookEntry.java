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
        // Реагируем только на NSMedia процесс
        if (!"com.ecarx.multimedia".equals(lpparam.packageName)) {
            return;
        }

        XposedBridge.log(TAG + ": loaded into " + lpparam.packageName);
        Log.i(TAG, "Hook entry loaded into " + lpparam.packageName);

        // Инициализируем базу станций
        StationsDatabase.init();

        // Сканер классов - находим все классы NSMedia с нужными ключевыми словами
        // Это диагностика - поможет найти правильное имя для hook'а
        scanClasses(lpparam);

        // Hook стратегия #1: подмена в MusicPlaybackInfoImpl
        hookMusicPlaybackInfoImpl(lpparam);

        // Hook стратегия #2: подмена в RadioInfo.getName()
        hookRadioInfoGetName(lpparam);

        // Hook стратегия #3: для отладки - логируем все setRadioStationName
        hookSetRadioStationName(lpparam);
    }

    /**
     * ДИАГНОСТИКА: проходит по ВСЕМ классам APK через DexFile API
     * и логирует те у которых есть метод setRadioStationName или setRadioName.
     * Это гарантированно найдёт нужный класс без необходимости иметь APK.
     */
    private void scanClasses(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedBridge.log(TAG + ": ===== SCANNING APK CLASSES =====");
            XposedBridge.log(TAG + ": APK path = " + lpparam.appInfo.sourceDir);

            // Используем DexFile API чтобы получить ВСЕ классы из APK
            dalvik.system.DexFile dex = new dalvik.system.DexFile(lpparam.appInfo.sourceDir);
            java.util.Enumeration<String> entries = dex.entries();

            int totalScanned = 0;
            int relevantFound = 0;
            ClassLoader cl = lpparam.classLoader;

            while (entries.hasMoreElements()) {
                String className = entries.nextElement();
                totalScanned++;

                // Только классы NSMedia (не библиотеки androidx, kotlin, retrofit и т.д.)
                if (!className.startsWith("com.ecarx.")
                        && !className.startsWith("ecarx.")
                        && !className.startsWith("com.neusoft.")) {
                    continue;
                }

                // Пропускаем R классы и BuildConfig
                if (className.contains(".R$") || className.endsWith(".R")
                        || className.endsWith(".BuildConfig")) {
                    continue;
                }

                try {
                    Class<?> c = cl.loadClass(className);

                    // Ищем методы set*StationName / set*RadioName
                    boolean hasRelevant = false;
                    StringBuilder methods = new StringBuilder();

                    for (Method m : c.getDeclaredMethods()) {
                        String mn = m.getName();
                        // Ловим setRadioStationName, setRadioName, setStationName, и getтеры
                        if ((mn.equals("setRadioStationName") || mn.equals("setRadioName")
                                || mn.equals("setStationName") || mn.equals("setLastRadioName")
                                || mn.equals("getRadioStationName") || mn.equals("getRadioName")
                                || mn.equals("getStationName"))
                                && m.getParameterCount() <= 1) {
                            hasRelevant = true;
                            methods.append("\n    method: ").append(mn)
                                .append("(").append(m.getParameterCount())
                                .append(" args) returns ")
                                .append(m.getReturnType().getSimpleName());
                        }
                    }

                    if (hasRelevant) {
                        relevantFound++;
                        XposedBridge.log(TAG + ": >>> CLASS WITH RADIO_NAME: " + className + methods.toString());
                    }
                } catch (Throwable t) {
                    // class load error - skip
                }
            }

            XposedBridge.log(TAG + ": ===== SCAN DONE: " + totalScanned
                + " classes scanned, " + relevantFound + " with radio name methods =====");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": scanClasses error: " + t.getMessage());
            t.printStackTrace();
        }
    }

    /**
     * СТРАТЕГИЯ #1: Hook на PlaybackInfoWrapper и MediaInfoWrapper.
     * Это классы которые отправляются в MediaCenter (и виджет их потом видит).
     * Точные имена классов найдены через анализ DEX файла NSMedia.apk:
     *   - com.neusoft.sdk.mediacenter.PlaybackInfoWrapper (PlaybackInfoWrapper.java, classes9.dex)
     *   - com.neusoft.sdk.mediacenter.MediaInfoWrapper (MediaInfoWrapper.java, classes19.dex)
     *   - com.neusoft.sdk.mediacenter.AbstractMusicPlaybackInfo (classes10.dex)
     */
    private void hookMusicPlaybackInfoImpl(XC_LoadPackage.LoadPackageParam lpparam) {
        // ТОЧНЫЕ имена классов найденные через анализ DEX файла
        String[] possibleClassNames = {
            // ГЛАВНЫЕ цели (точные имена из DEX):
            "com.neusoft.sdk.mediacenter.PlaybackInfoWrapper",
            "com.neusoft.sdk.mediacenter.MediaInfoWrapper",
            "com.neusoft.sdk.mediacenter.AbstractMusicPlaybackInfo",
            "com.neusoft.sdk.mediacenter.MusicPlaybackInfo",
            "com.neusoft.sdk.mediacenter.control.bean.MusicPlaybackInfo",
            // Старые fallback на случай если ещё что-то найдётся
            "com.ecarx.multimedia.media.MusicPlaybackInfoImpl",
            "com.ecarx.multimedia.MusicPlaybackInfoImpl"
        };

        for (String className : possibleClassNames) {
            try {
                Class<?> targetClass = XposedHelpers.findClass(className, lpparam.classLoader);
                XposedBridge.log(TAG + ": ✓ found class " + className);
                hookClassMethods(targetClass, className);
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": class not found: " + className);
            }
        }
    }

    /**
     * Ставит hook на set/get для radioStationName на конкретном классе.
     */
    private void hookClassMethods(Class<?> targetClass, String classNameForLog) {
        // Hook на setRadioStationName
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
                                XposedBridge.log(TAG + ": [" + classNameForLog
                                    + ".setRadioStationName] '' -> '" + found
                                    + "' (freq=" + freq + ")");
                            }
                        }
                    }
                });
            XposedBridge.log(TAG + ":   ✓ hooked setRadioStationName on " + classNameForLog);
        } catch (Throwable t) {
            // no such method - ok
        }

        // Hook на getRadioStationName
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
                                XposedBridge.log(TAG + ": [" + classNameForLog
                                    + ".getRadioStationName()] '' -> '" + found
                                    + "' (freq=" + freq + ")");
                            }
                        }
                    }
                });
            XposedBridge.log(TAG + ":   ✓ hooked getRadioStationName on " + classNameForLog);
        } catch (Throwable t) {
            // no such method - ok
        }
    }

    /**
     * СТРАТЕГИЯ #2: Hook на RadioInfo.getName().
     * Этот класс используется и LunarisApp и системой.
     * Если getName() возвращает null/пусто, подменяем из базы.
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
                                XposedBridge.log(TAG + ": RadioInfo.getName() '' -> '"
                                    + found + "' (freq=" + freq + ")");
                            }
                        }
                    }
                });
            XposedBridge.log(TAG + ": hooked RadioInfo.getName successfully");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": RadioInfo.getName hook failed: " + t.getMessage());
        }
    }

    /**
     * СТРАТЕГИЯ #3: для отладки - перехватываем ВСЕ методы которые
     * содержат "RadioStationName" в имени по всему classloader-у NSMedia.
     * Это даст нам понимание какие методы вообще вызываются.
     */
    private void hookSetRadioStationName(XC_LoadPackage.LoadPackageParam lpparam) {
        // Это просто диагностика - смотрим в logcat что вызывается
        XposedBridge.log(TAG + ": diagnostic logging enabled");
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
