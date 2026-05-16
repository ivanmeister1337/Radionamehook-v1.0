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

        // НОВЫЙ v14: хукаем RemoteViews.setTextViewText - универсальный API
        // через который виджет ставит текст в TextView (R.id.tv_lyric = 0x7f0801c8).
        // Когда виджет хочет показать "FM 88.4" — подменяем на "Авторадио 88.4".
        if (isWidget) {
            hookRemoteViewsSetTextViewText(lpparam);
        }
    }

    /**
     * v14: Hook на android.widget.RemoteViews.setTextViewText(int viewId, CharSequence text).
     * Это базовый API Android'a для установки текста в виджете - его использует ВСЁ.
     * Мы перехватываем вызовы и для частоты в формате "FM 88.4" / "AM 675" / "88.4"
     * подменяем на "Имя 88.4" / "Имя 675".
     *
     * Это работает для всех layout-ов виджета сразу (fold на главном + unfold в полноэкр.),
     * потому что TextView tv_lyric (0x7f0801c8) одинаковый в обоих.
     */
    private void hookRemoteViewsSetTextViewText(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> rvClass = Class.forName("android.widget.RemoteViews");
            XposedHelpers.findAndHookMethod(rvClass, "setTextViewText",
                int.class, CharSequence.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            int viewId = (Integer) param.args[0];
                            CharSequence cs = (CharSequence) param.args[1];
                            if (cs == null) return;
                            String text = cs.toString();
                            if (text.isEmpty()) return;

                            // Ищем частоту в строке. Возможные форматы:
                            //   "FM 88.4"  "FM 88,4"  "88.4 MHz"  "88.4"
                            //   "AM 675"   "675 kHz"  "675"
                            String freq = extractFreq(text);
                            if (freq == null) return;

                            String found = StationsDatabase.findName(freq);
                            if (found == null) return;

                            // Если в строке уже есть наше имя — не дублируем
                            if (text.contains(found)) return;

                            // Подменяем: "FM 88.4" -> "Авторадио 88.4"
                            // Сохраняем частоту в конце, имя сначала
                            String newText = found + " " + freq;
                            param.args[1] = newText;

                            XposedBridge.log(TAG + ": >>> RemoteViews.setTextViewText("
                                + String.format("0x%08x", viewId) + ", \"" + text
                                + "\") -> \"" + newText + "\"");
                        } catch (Throwable t) {
                            // skip
                        }
                    }
                });
            XposedBridge.log(TAG + ": ✓ hooked RemoteViews.setTextViewText");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": RemoteViews.setTextViewText hook failed: " + t.getMessage());
        }
    }

    /**
     * Извлечь частоту из строки виджета.
     * Возвращает строку которую StationsDatabase.findName() поймёт,
     * либо null если частоты нет.
     */
    private static String extractFreq(String text) {
        // Регексп: число с возможной точкой/запятой, окружённое не-цифрами
        // Примеры что должно сработать:
        //   "FM 88.4"   -> "88.4"
        //   "FM 88,4"   -> "88,4" -> "88.4"
        //   "AM 675"    -> "675"
        //   "88.4 MHz"  -> "88.4"
        //   "103.7"     -> "103.7"
        try {
            // Сначала ищем число с точкой/запятой (FM формат)
            java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\d{2,3}[.,]\\d)")
                .matcher(text);
            if (m.find()) {
                return m.group(1).replace(',', '.');
            }
            // Иначе ищем целое число 100-2000 (AM формат)
            m = java.util.regex.Pattern
                .compile("\\b(\\d{3,4})\\b")
                .matcher(text);
            if (m.find()) {
                String n = m.group(1);
                int v = Integer.parseInt(n);
                if (v >= 100 && v <= 2000) return n;
            }
        } catch (Throwable t) { /* skip */ }
        return null;
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
                        // КРИТИЧЕСКОЕ: writeToParcel - вызывается перед AIDL передачей.
                        // Здесь надо подменить radioStationName на стороне NSMedia до того
                        // как объект уйдёт в виджет.
                        if (mn.equals("writeToParcel")
                                && hasRadioStationNameField(c)) {
                            hookWriteToParcel(c, className);
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

        // КРИТИЧЕСКИЙ HOOK: на ранней стадии в NSMedia, когда событие из RadioService
        // прилетает в RadioFragment - там можем заполнить имена всех станций сразу.
        hookRadioCollectListEvent(lpparam);
    }

    /**
     * САМЫЙ РАННИЙ HOOK: перехватываем событие RadioCollectListEvent в NSMedia.
     * Этот event приходит из RadioService с пустыми именами для всех станций.
     * Заполняем имена ДО того как они попадут в MediaCenter и виджет.
     */
    private void hookRadioCollectListEvent(XC_LoadPackage.LoadPackageParam lpparam) {
        // Стратегия 1: hook на EventBus.post() - универсальный перехват
        // Когда что-либо в NSMedia публикует событие через EventBus, мы его проверяем.
        // Если это RadioCollectListEvent или подобное - заполняем имена.
        try {
            Class<?> eventBus = lpparam.classLoader.loadClass("org.greenrobot.eventbus.EventBus");
            for (Method m : eventBus.getDeclaredMethods()) {
                if (m.getName().equals("post") && m.getParameterCount() == 1) {
                    try {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                try {
                                    Object event = param.args[0];
                                    if (event == null) return;
                                    String className = event.getClass().getName();
                                    // Только наши Radio события
                                    if (className.contains("Radio")
                                            && className.contains("Event")) {
                                        fixAllStationsInEvent(event);
                                    }
                                } catch (Throwable t) { /* skip */ }
                            }
                        });
                        XposedBridge.log(TAG + ":   ✓ hooked EventBus.post() for radio events");
                    } catch (Throwable t) { /* skip */ }
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": EventBus not found: " + t.getMessage());
        }

        // Стратегия 2: hook на конструктор RadioCollectListEvent
        try {
            Class<?> eventClass = lpparam.classLoader.loadClass(
                "com.ecarx.multimedia.eventbus.radio.RadioCollectListEvent");
            XposedBridge.log(TAG + ": ✓ found RadioCollectListEvent class");

            for (java.lang.reflect.Constructor<?> ctor : eventClass.getDeclaredConstructors()) {
                try {
                    XposedBridge.hookMethod(ctor, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                fixAllStationsInEvent(param.thisObject);
                            } catch (Throwable t) { /* skip */ }
                        }
                    });
                    XposedBridge.log(TAG + ":   ✓ hooked RadioCollectListEvent ctor("
                        + ctor.getParameterCount() + " args)");
                } catch (Throwable t) { /* skip */ }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": RadioCollectListEvent not found: " + t.getMessage());
        }

        // Стратегия 3: hook на ru.mark99.integrations.nsmedia.RadioCollectionHandler
        // Это работает ВСЕГДА (не зависит от состояния фрагмента)
        try {
            Class<?> mark99Handler = lpparam.classLoader.loadClass(
                "ru.mark99.integrations.nsmedia.RadioCollectionHandler");
            XposedBridge.log(TAG + ": ✓ found Mark99 RadioCollectionHandler");
            for (Method m : mark99Handler.getDeclaredMethods()) {
                XposedBridge.log(TAG + ":   Mark99 handler method: " + m.getName()
                    + "(" + m.getParameterCount() + ")");
                // Хукаем ВСЕ методы которые принимают объект (могут содержать список)
                if (m.getParameterCount() >= 1) {
                    try {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                try {
                                    for (Object arg : param.args) {
                                        if (arg != null) {
                                            tryFixAnyObject(arg);
                                        }
                                    }
                                } catch (Throwable t) { /* skip */ }
                            }
                        });
                    } catch (Throwable t) { /* skip */ }
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Mark99 handler not found: " + t.getMessage());
        }

        // Стратегия 4: hook на RadioFragment.onRadioCollectList (на всякий случай)
        try {
            Class<?> fragClass = lpparam.classLoader.loadClass(
                "com.ecarx.multimedia.module.radio.RadioFragment");
            for (Method m : fragClass.getDeclaredMethods()) {
                if (m.getName().equals("onRadioCollectList")
                        && m.getParameterCount() == 1) {
                    try {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                try {
                                    fixAllStationsInEvent(param.args[0]);
                                } catch (Throwable t) { /* skip */ }
                            }
                        });
                        XposedBridge.log(TAG + ":   ✓ hooked RadioFragment.onRadioCollectList");
                    } catch (Throwable t) { /* skip */ }
                }
            }
        } catch (Throwable t) { /* skip */ }
    }

    /**
     * Универсальная попытка исправить любой объект.
     * Если это List - проходимся по элементам, если это event - читаем поле list.
     */
    private void tryFixAnyObject(Object obj) {
        if (obj == null) return;
        try {
            if (obj instanceof java.util.List) {
                java.util.List<?> list = (java.util.List<?>) obj;
                int fixed = 0;
                for (Object item : list) {
                    if (item != null && fixRadioInfoLikeObject(item)) {
                        fixed++;
                    }
                }
                if (fixed > 0) {
                    XposedBridge.log(TAG + ": >>> Mark99 handler: fixed " + fixed
                        + " items in list");
                }
            } else {
                fixAllStationsInEvent(obj);
            }
        } catch (Throwable t) { /* skip */ }
    }

    /**
     * Пытается через reflection заполнить поле name у RadioInfo по полю frequency.
     */
    private boolean fixRadioInfoLikeObject(Object obj) {
        try {
            String freq = readRadioFreqFromAnyField(obj);
            if (freq == null) return false;
            String found = StationsDatabase.findName(freq);
            if (found == null) return false;

            for (String fname : new String[]{"name", "mName", "stationName", "mStationName",
                                              "radioStationName", "mRadioStationName"}) {
                try {
                    java.lang.reflect.Field f = obj.getClass().getDeclaredField(fname);
                    f.setAccessible(true);
                    String current = (String) f.get(obj);
                    if (current == null || current.isEmpty() || !current.equals(found)) {
                        f.set(obj, found);
                        return true;
                    }
                    return false;
                } catch (NoSuchFieldException e) { /* try next */ }
            }
        } catch (Throwable t) { /* skip */ }
        return false;
    }

    /**
     * Берёт RadioCollectListEvent, достаёт из него list (через reflection)
     * и для каждого RadioInfo с пустым name - подменяет name из нашей базы.
     */
    private void fixAllStationsInEvent(Object event) throws Exception {
        if (event == null) return;

        // Ищем поле list (или mList) типа List<RadioInfo>
        java.lang.reflect.Field listField = null;
        for (String fname : new String[]{"list", "mList", "stations", "mStations", "radioList"}) {
            try {
                listField = event.getClass().getDeclaredField(fname);
                break;
            } catch (NoSuchFieldException e) { /* try next */ }
        }
        if (listField == null) {
            // Попробуем найти любое поле типа List
            for (java.lang.reflect.Field f : event.getClass().getDeclaredFields()) {
                if (java.util.List.class.isAssignableFrom(f.getType())) {
                    listField = f;
                    break;
                }
            }
        }
        if (listField == null) return;

        listField.setAccessible(true);
        Object listObj = listField.get(event);
        if (!(listObj instanceof java.util.List)) return;

        java.util.List<?> list = (java.util.List<?>) listObj;
        int fixed = 0;
        for (Object radioInfo : list) {
            if (radioInfo == null) continue;
            try {
                // Читаем frequency и name
                String freq = readRadioFreqFromAnyField(radioInfo);
                if (freq == null) continue;

                // Имя в нашей базе
                String found = StationsDatabase.findName(freq);
                if (found == null) continue;

                // Подменяем поле name
                for (String fname : new String[]{"name", "mName", "stationName", "mStationName"}) {
                    try {
                        java.lang.reflect.Field nameField =
                            radioInfo.getClass().getDeclaredField(fname);
                        nameField.setAccessible(true);
                        String currentName = (String) nameField.get(radioInfo);
                        if (currentName == null || currentName.isEmpty()
                                || !currentName.equals(found)) {
                            nameField.set(radioInfo, found);
                            fixed++;
                        }
                        break;
                    } catch (NoSuchFieldException e) { /* try next */ }
                }
            } catch (Throwable t) { /* skip */ }
        }
        if (fixed > 0) {
            XposedBridge.log(TAG + ": >>> fixAllStationsInEvent: fixed " + fixed
                + " names in event " + event.getClass().getSimpleName());
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
     * Проверяет есть ли в классе поле radioStationName/stationName
     */
    private boolean hasRadioStationNameField(Class<?> c) {
        String[] fieldNames = {
            "radioStationName", "mRadioStationName",
            "stationName", "mStationName"
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
     * КРИТИЧЕСКИЙ HOOK: writeToParcel вызывается перед AIDL-сериализацией.
     * NSMedia упаковывает MediaInfoWrapper с radioStationName='' и отправляет виджету.
     * Виджет читает Parcel напрямую - в обход геттеров. Поэтому мы должны подменить
     * поле radioStationName ДО writeToParcel.
     */
    private void hookWriteToParcel(Class<?> targetClass, final String classNameForLog) {
        for (Method m : targetClass.getDeclaredMethods()) {
            if (!m.getName().equals("writeToParcel")) continue;
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            Object obj = param.thisObject;
                            if (obj == null) return;

                            // Находим частоту в объекте
                            String freq = readRadioFreqFromAnyField(obj);
                            if (freq == null || freq.isEmpty()) return;

                            // Находим имя в нашей базе
                            String found = StationsDatabase.findName(freq);
                            if (found == null) return;

                            // Подменяем поле radioStationName на нужное имя
                            // перед сериализацией.
                            for (String fieldName : new String[]{
                                "radioStationName", "mRadioStationName",
                                "stationName", "mStationName"}) {
                                try {
                                    java.lang.reflect.Field f =
                                        obj.getClass().getDeclaredField(fieldName);
                                    f.setAccessible(true);
                                    String current = (String) f.get(obj);
                                    if (current == null || current.isEmpty()
                                            || !current.equals(found)) {
                                        f.set(obj, found);
                                        XposedBridge.log(TAG + ": >>> writeToParcel patched "
                                            + classNameForLog + "." + fieldName
                                            + " '' -> '" + found + "' (freq=" + freq + ")");
                                    }
                                    break;
                                } catch (NoSuchFieldException e) {
                                    // try next field name
                                }
                            }
                        } catch (Throwable t) { /* skip */ }
                    }
                });
                XposedBridge.log(TAG + ":   ✓ hooked writeToParcel on " + classNameForLog);
            } catch (Throwable t) { /* skip */ }
        }
    }

    /**
     * Читает частоту из любого поля объекта (frequency/radioFrequency/freq и т.п.)
     */
    private String readRadioFreqFromAnyField(Object obj) {
        String[] fieldNames = {
            "radioFrequency", "mRadioFrequency",
            "frequency", "mFrequency",
            "freq", "mFreq", "mLastRadioFreq"
        };
        for (String name : fieldNames) {
            try {
                java.lang.reflect.Field f = obj.getClass().getDeclaredField(name);
                f.setAccessible(true);
                Object val = f.get(obj);
                if (val != null) {
                    String s = val.toString().trim();
                    if (!s.isEmpty()) {
                        return normalizeFreqValue(s);
                    }
                }
            } catch (Throwable t) { /* try next */ }
        }
        return null;
    }

    /**
     * Нормализует значение частоты в формат базы.
     * "95000" -> "95.0", "103400" -> "103.4", "95.0" -> "95.0".
     */
    private String normalizeFreqValue(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.isEmpty()) return null;
        try {
            double d = Double.parseDouble(s);
            if (d > 10000) {
                // Это герцы / килогерцы - переводим в МГц
                d = d / 1000.0;
            }
            // Округляем до 1 знака
            d = Math.round(d * 10.0) / 10.0;
            if (d == (int) d) {
                return ((int) d) + ".0";
            }
            return String.valueOf(d);
        } catch (NumberFormatException e) {
            return s;
        }
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
     * Используем ТОЧНОЕ имя класса найденное через анализ vdex файла.
     */
    private void tryKnownWidgetClasses(XC_LoadPackage.LoadPackageParam lpparam) {
        // НАЙДЕНО через анализ NSMediaWidget.vdex:
        //   ecarx.xsf.widget.holder.MusicPlayInfoHolder - хранит данные
        //   ecarx.xsf.widget.remoteviews.UnfoldRadioWidgetRemoteViews - рисует tv_radio_name
        //   и в нём есть метод setRadioStationName(String) - вот его и надо хукать!
        ClassLoader cl = lpparam.classLoader;

        // 1) Holder - храним для watchdog но это не главное
        try {
            Class<?> c = cl.loadClass("ecarx.xsf.widget.holder.MusicPlayInfoHolder");
            XposedBridge.log(TAG + ": ✓ widget class found: " + c.getName());
            hookWidgetMethods(c, c.getName());
            hookAllStringSettersAndGetters(c, c.getName());
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": MusicPlayInfoHolder not found");
        }

        // 2) ГЛАВНОЕ: UnfoldRadioWidgetRemoteViews.setRadioStationName(String)
        // Этот метод НАПРЯМУЮ рисует имя в tv_radio_name через RemoteViews API.
        try {
            Class<?> rv = cl.loadClass("ecarx.xsf.widget.remoteviews.UnfoldRadioWidgetRemoteViews");
            XposedBridge.log(TAG + ": ✓ found UnfoldRadioWidgetRemoteViews");
            hookRemoteViewsSetters(rv);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": UnfoldRadioWidgetRemoteViews not found: " + t.getMessage());
        }

        // 3) Также пробуем FoldWidgetRemoteViews - для свёрнутого вида
        try {
            Class<?> rv = cl.loadClass("ecarx.xsf.widget.remoteviews.FoldWidgetRemoteViews");
            XposedBridge.log(TAG + ": ✓ found FoldWidgetRemoteViews");
            hookRemoteViewsSetters(rv);
        } catch (Throwable t) { /* may not exist */ }
    }

    /**
     * Хукает setRadioStationName и setRadioFreq на RemoteViews классе виджета.
     * Это последняя точка перед отрисовкой на экран - если у нас тут есть freq,
     * мы можем посчитать правильное имя и установить его.
     */
    private void hookRemoteViewsSetters(final Class<?> rvClass) {
        // 1. setRadioStationName(String) - если пришло пустое имя, ищем по последней частоте
        for (Method m : rvClass.getDeclaredMethods()) {
            String mn = m.getName();

            if (mn.equals("setRadioStationName") && m.getParameterCount() == 1
                    && m.getParameterTypes()[0] == String.class) {
                try {
                    XposedHelpers.findAndHookMethod(rvClass, "setRadioStationName", String.class,
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                String name = (String) param.args[0];
                                String freq = lastSeenFreq;
                                if ((name == null || name.isEmpty()) && freq != null) {
                                    String found = StationsDatabase.findName(freq);
                                    if (found != null) {
                                        param.args[0] = found;
                                        XposedBridge.log(TAG + ": >>> RemoteViews."
                                            + "setRadioStationName('') -> '" + found
                                            + "' (lastFreq=" + freq + ")");
                                    }
                                } else if (name != null && !name.isEmpty() && freq != null) {
                                    // Иногда виджет получает "FM 95.0" или что-то такое - 
                                    // тоже подменяем если для этой частоты есть запись в базе
                                    String found = StationsDatabase.findName(freq);
                                    if (found != null && !name.equals(found)) {
                                        param.args[0] = found;
                                        XposedBridge.log(TAG + ": >>> RemoteViews."
                                            + "setRadioStationName('" + name + "') -> '"
                                            + found + "' (lastFreq=" + freq + ")");
                                    }
                                }
                            }
                        });
                    XposedBridge.log(TAG + ":   ✓ hooked setRadioStationName on RemoteViews");
                } catch (Throwable t) {
                    XposedBridge.log(TAG + ": setRadioStationName hook on RemoteViews failed: " + t.getMessage());
                }
            }

            // 2. setRadioFreq / setRadioFrequency - запоминаем частоту в global
            if ((mn.equals("setRadioFreq") || mn.equals("setRadioFrequency"))
                    && m.getParameterCount() >= 1) {
                try {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            // Сохраняем частоту в global чтобы setRadioStationName мог её прочитать
                            Object arg = param.args[0];
                            if (arg != null) {
                                lastSeenFreq = normalizeAnyFreq(arg.toString());
                            }
                        }
                    });
                    XposedBridge.log(TAG + ":   ✓ hooked " + mn + " on RemoteViews");
                } catch (Throwable t) { /* skip */ }
            }
        }
    }

    // Глобально хранится последняя установленная частота на RemoteViews
    // чтобы setRadioStationName мог её использовать когда имя пустое
    private static volatile String lastSeenFreq = null;

    private static String normalizeAnyFreq(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.isEmpty()) return null;
        // "103.4" "103.4 МГц" "95000" "FM 95.0"
        // Извлекаем число
        java.util.regex.Matcher m =
            java.util.regex.Pattern.compile("([0-9]+(?:\\.[0-9]+)?)").matcher(s);
        if (m.find()) {
            String num = m.group(1);
            try {
                double d = Double.parseDouble(num);
                if (d > 1000) {
                    // в кГц или Гц - переводим в МГц
                    if (d > 100000) d /= 1000.0; // Гц
                    d /= 1000.0; // кГц -> МГц
                    return String.valueOf(d);
                }
                return num;
            } catch (Exception e) {
                return num;
            }
        }
        return s;
    }

    /**
     * Точечный hook на найденные методы виджета.
     */
    private void hookWidgetMethods(Class<?> targetClass, final String classNameForLog) {
        // setLastUiRadioName(String) - устанавливает имя на виджете
        try {
            XposedHelpers.findAndHookMethod(targetClass, "setLastUiRadioName", String.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        String newName = (String) param.args[0];
                        if (newName == null || newName.isEmpty()) {
                            String freq = readFreqFromObject(param.thisObject);
                            String found = StationsDatabase.findName(freq);
                            if (found != null) {
                                param.args[0] = found;
                                XposedBridge.log(TAG + ": >>> WIDGET setLastUiRadioName('') -> '"
                                    + found + "' (freq=" + freq + ")");
                            }
                        }
                    }
                });
            XposedBridge.log(TAG + ":   ✓ hooked setLastUiRadioName on " + classNameForLog);
        } catch (Throwable t) {
            // нет такого метода - ок, попробуем другие
        }

        // getLastUiRadioName() - возвращает имя
        try {
            XposedHelpers.findAndHookMethod(targetClass, "getLastUiRadioName",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        String result = (String) param.getResult();
                        if (result == null || result.isEmpty()) {
                            String freq = readFreqFromObject(param.thisObject);
                            String found = StationsDatabase.findName(freq);
                            if (found != null) {
                                param.setResult(found);
                                XposedBridge.log(TAG + ": >>> WIDGET getLastUiRadioName() '' -> '"
                                    + found + "' (freq=" + freq + ")");
                            }
                        }
                    }
                });
            XposedBridge.log(TAG + ":   ✓ hooked getLastUiRadioName on " + classNameForLog);
        } catch (Throwable t) { /* skip */ }

        // ГЛАВНОЕ: hook на методы которые обновляют MusicPlayInfoHolder ЦЕЛИКОМ
        // Они принимают объект (от AIDL) и копируют поля в this.mLastUiRadioName и т.д.
        // Перехватываем ВСЕ методы set*/update* в этом классе и через reflection переписываем поле.
        for (Method m : targetClass.getDeclaredMethods()) {
            String mn = m.getName();
            // Все методы которые могут обновлять Holder
            if (mn.startsWith("set") || mn.startsWith("update")
                    || mn.startsWith("refresh") || mn.startsWith("notify")) {
                hookHolderUpdateMethod(m, classNameForLog);
            }
        }

        // WATCHDOG: запускаем фоновый поток, который проверяет поле mLastUiRadioName
        // и переписывает его если оно пустое. Это страхует от случаев когда что-то
        // ПОСЛЕ нашего hook'а сбрасывает поле обратно.
        startWatchdogIfNeeded(targetClass);
    }

    private static boolean watchdogStarted = false;
    private static final java.util.List<Object> trackedHolders =
        java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    /**
     * Watchdog: каждые 500мс перебирает все известные MusicPlayInfoHolder
     * и проверяет что mLastUiRadioName соответствует mLastRadioFreq из базы.
     */
    private void startWatchdogIfNeeded(Class<?> holderClass) {
        if (watchdogStarted) return;
        watchdogStarted = true;

        Thread t = new Thread(() -> {
            XposedBridge.log(TAG + ": watchdog started");
            while (true) {
                try {
                    Thread.sleep(500);
                    synchronized (trackedHolders) {
                        for (Object holder : trackedHolders) {
                            try {
                                fixHolderField(holder);
                            } catch (Throwable t1) { /* skip */ }
                        }
                    }
                } catch (InterruptedException e) {
                    break;
                } catch (Throwable e) {
                    // continue
                }
            }
        }, "RadioNameHook-Watchdog");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Через reflection читает mLastRadioFreq и переписывает mLastUiRadioName если нужно.
     */
    private void fixHolderField(Object holder) {
        try {
            String freq = readFreqFromObject(holder);
            if (freq == null || freq.isEmpty()) return;

            String found = StationsDatabase.findName(freq);
            if (found == null) return;

            java.lang.reflect.Field f;
            try {
                f = holder.getClass().getDeclaredField("mLastUiRadioName");
            } catch (NoSuchFieldException e) {
                return;
            }
            f.setAccessible(true);
            String current = (String) f.get(holder);
            if (current == null || current.isEmpty() || !current.equals(found)) {
                f.set(holder, found);
                // Не логируем чтобы не засорять лог
            }
        } catch (Throwable t) { /* skip */ }
    }

    /**
     * Хукаем метод обновления Holder'а - после выполнения через reflection
     * читаем mLastRadioFreq из this и переписываем mLastUiRadioName на наше имя.
     */
    private void hookHolderUpdateMethod(final Method method, final String classNameForLog) {
        try {
            StringBuilder sig = new StringBuilder();
            for (Class<?> p : method.getParameterTypes()) {
                if (sig.length() > 0) sig.append(", ");
                sig.append(p.getSimpleName());
            }
            final String sigStr = sig.toString();

            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object holder = param.thisObject;
                    if (holder == null) return;

                    // Регистрируем holder в watchdog (без дубликатов)
                    synchronized (trackedHolders) {
                        boolean exists = false;
                        for (Object o : trackedHolders) {
                            if (o == holder) { exists = true; break; }
                        }
                        if (!exists) trackedHolders.add(holder);
                    }

                    try {
                        String freq = readFreqFromObject(holder);
                        if (freq == null || freq.isEmpty()) return;
                        String found = StationsDatabase.findName(freq);
                        if (found == null) return;

                        java.lang.reflect.Field f;
                        try {
                            f = holder.getClass().getDeclaredField("mLastUiRadioName");
                        } catch (NoSuchFieldException e) {
                            try {
                                f = holder.getClass().getDeclaredField("lastUiRadioName");
                            } catch (NoSuchFieldException e2) {
                                return;
                            }
                        }
                        f.setAccessible(true);
                        String current = (String) f.get(holder);
                        if (current == null || current.isEmpty() || !current.equals(found)) {
                            f.set(holder, found);
                            XposedBridge.log(TAG + ": >>> WIDGET " + classNameForLog
                                + "." + method.getName() + "(" + sigStr + ") -> set mLastUiRadioName='"
                                + found + "' (freq=" + freq + ", was='" + current + "')");
                        }
                    } catch (Throwable t) { /* skip */ }
                }
            });
        } catch (Throwable t) { /* skip */ }
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
                        String freq = readFreqFromObject(param.thisObject);
                        String found = StationsDatabase.findName(freq);

                        // Если в нашей базе есть имя для этой частоты - подменяем ВСЕГДА.
                        // Это важно для CollectionAdapter, где список избранных приходит
                        // с пустыми или дефолтными именами ('Радио'). Кроме того, в логе
                        // мы видели что для текущей частоты result был '' (пусто),
                        // а для записей в списке избранных это может быть 'Радио' или null.
                        if (found != null && !found.equals(result)) {
                            param.setResult(found);
                            // Логируем только при реальной подмене
                            XposedBridge.log(TAG + ": >>> RadioInfo.getName() '"
                                + (result == null ? "null" : result)
                                + "' -> '" + found + "' (freq=" + freq + ")");
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
