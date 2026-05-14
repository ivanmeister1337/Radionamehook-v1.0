package com.belgee.radionamehook;

import android.util.Log;
import de.robv.android.xposed.XposedBridge;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.HashMap;
import java.util.Map;

/**
 * База станций. Читает JSON-файл с тем же форматом что и старое RadioNameApp.
 *
 * Формат файла /sdcard/RadioNames/stations.json:
 * {
 *   "fm": {
 *     "103.4": "Русское Радио",
 *     "104.2": "Авторадио",
 *     ...
 *   },
 *   "am": {
 *     "675": "Маяк",
 *     ...
 *   }
 * }
 */
public class StationsDatabase {
    private static final String TAG = "RadioNameHook";
    private static final String JSON_PATH = "/sdcard/RadioNames/stations.json";

    private static Map<String, String> fmStations = new HashMap<>();
    private static Map<String, String> amStations = new HashMap<>();
    private static long lastLoadTime = 0;
    private static final long RELOAD_INTERVAL_MS = 30000; // перезагружаем JSON раз в 30 сек

    public static void init() {
        loadFromFile();
    }

    private static synchronized void loadFromFile() {
        try {
            File file = new File(JSON_PATH);
            if (!file.exists()) {
                XposedBridge.log(TAG + ": stations.json not found at " + JSON_PATH);
                return;
            }

            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }

            JSONObject root = new JSONObject(sb.toString());
            fmStations.clear();
            amStations.clear();

            if (root.has("fm")) {
                JSONObject fm = root.getJSONObject("fm");
                fm.keys().forEachRemaining(key -> {
                    try {
                        fmStations.put(key, fm.getString(key));
                    } catch (Throwable t) { /* skip */ }
                });
            }
            if (root.has("am")) {
                JSONObject am = root.getJSONObject("am");
                am.keys().forEachRemaining(key -> {
                    try {
                        amStations.put(key, am.getString(key));
                    } catch (Throwable t) { /* skip */ }
                });
            }

            lastLoadTime = System.currentTimeMillis();
            XposedBridge.log(TAG + ": loaded " + fmStations.size() + " FM + "
                + amStations.size() + " AM stations from JSON");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": load JSON error: " + t.getMessage());
        }
    }

    /**
     * Найти название станции по частоте.
     * @param freq частота - может быть в разных форматах: "103.4", "103400", "103.4 МГц", "675", "675 кГц"
     * @return название или null если не найдено
     */
    public static String findName(String freq) {
        if (freq == null || freq.isEmpty()) return null;

        // Перезагружаем JSON если давно не обновляли
        if (System.currentTimeMillis() - lastLoadTime > RELOAD_INTERVAL_MS) {
            loadFromFile();
        }

        // Нормализуем частоту
        String normalized = normalizeFreq(freq);
        if (normalized == null) return null;

        // Сначала пробуем FM
        String result = fmStations.get(normalized);
        if (result != null) return result;

        // Потом AM
        result = amStations.get(normalized);
        if (result != null) return result;

        return null;
    }

    /**
     * Привести любой формат частоты к ключу JSON.
     * "103400" -> "103.4"
     * "103.4 МГц" -> "103.4"
     * "103.4" -> "103.4"
     * "675 кГц" -> "675"
     * "675" -> "675"
     */
    private static String normalizeFreq(String freq) {
        try {
            // Убираем суффиксы (МГц, кГц, MHz, kHz)
            String cleaned = freq.replaceAll("[^0-9.]", "").trim();
            if (cleaned.isEmpty()) return null;

            // Если есть точка - это уже FM формат
            if (cleaned.contains(".")) {
                return cleaned;
            }

            // Если число большое (> 10000) - значит в килогерцах FM (103400 -> 103.4)
            int num = Integer.parseInt(cleaned);
            if (num > 10000) {
                return String.valueOf(num / 1000.0).replaceAll("\\.?0+$", "")
                    + (num % 100 == 0 ? ".0" : "");
            }

            // Иначе - AM, возвращаем как есть
            return cleaned;
        } catch (Throwable t) {
            return null;
        }
    }
}
