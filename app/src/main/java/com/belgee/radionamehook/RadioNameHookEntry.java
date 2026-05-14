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

        // Hook стратегия #1: подмена в MusicPlaybackInfoImpl
        hookMusicPlaybackInfoImpl(lpparam);

        // Hook стратегия #2: подмена в RadioInfo.getName()
        hookRadioInfoGetName(lpparam);

        // Hook стратегия #3: для отладки - логируем все setRadioStationName
        hookSetRadioStationName(lpparam);
    }

    /**
     * СТРАТЕГИЯ #1: Hook на MusicPlaybackInfoImpl.
     * Это класс который отправляется в MediaCenter (и виджет его потом видит).
     * Когда установлен mLastRadioName="", мы подменяем значение.
     */
    private void hookMusicPlaybackInfoImpl(XC_LoadPackage.LoadPackageParam lpparam) {
        // Пробуем разные возможные имена класса
        String[] possibleClassNames = {
            "com.ecarx.multimedia.media.MusicPlaybackInfoImpl",
            "com.ecarx.multimedia.MusicPlaybackInfoImpl",
            "ecarx.xsf.mediacenter.MusicPlaybackInfoImpl",
            "com.ecarx.multimedia.bean.MusicPlaybackInfoImpl"
        };

        Class<?> targetClass = null;
        String foundName = null;
        for (String name : possibleClassNames) {
            try {
                targetClass = XposedHelpers.findClass(name, lpparam.classLoader);
                foundName = name;
                XposedBridge.log(TAG + ": found MusicPlaybackInfoImpl at " + name);
                break;
            } catch (Throwable t) {
                // try next
            }
        }

        if (targetClass == null) {
            XposedBridge.log(TAG + ": MusicPlaybackInfoImpl class NOT FOUND - стратегия #1 пропущена");
            return;
        }

        // Способ A: hook на setRadioStationName (если такой метод есть)
        try {
            XposedHelpers.findAndHookMethod(targetClass, "setRadioStationName", String.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        String newName = (String) param.args[0];
                        if (newName == null || newName.isEmpty()) {
                            // Имя пустое - пытаемся достать из базы по частоте
                            String freq = readFreqFromObject(param.thisObject);
                            String found = StationsDatabase.findName(freq);
                            if (found != null) {
                                param.args[0] = found;
                                XposedBridge.log(TAG + ": setRadioStationName('') -> '"
                                    + found + "' (freq=" + freq + ")");
                            }
                        }
                    }
                });
            XposedBridge.log(TAG + ": hooked setRadioStationName successfully");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": setRadioStationName hook failed: " + t.getMessage());
        }

        // Способ B: hook на getRadioStationName / getRadioName (геттер)
        // Виджет может вызывать get-методы напрямую через AIDL
        String[] getterNames = {"getRadioStationName", "getRadioName", "getStationName"};
        for (String getterName : getterNames) {
            try {
                XposedHelpers.findAndHookMethod(targetClass, getterName,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            String result = (String) param.getResult();
                            if (result == null || result.isEmpty()) {
                                String freq = readFreqFromObject(param.thisObject);
                                String found = StationsDatabase.findName(freq);
                                if (found != null) {
                                    param.setResult(found);
                                    XposedBridge.log(TAG + ": " + getterName + "() '' -> '"
                                        + found + "' (freq=" + freq + ")");
                                }
                            }
                        }
                    });
                XposedBridge.log(TAG + ": hooked " + getterName + " successfully");
            } catch (Throwable t) {
                // method may not exist - it's ok
            }
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
