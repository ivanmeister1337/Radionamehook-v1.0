package com.belgee.radionamehook;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Простое активити которое показывает работает ли модуль.
 *
 * Если LSPosed framework видит модуль - isModuleActive() вернёт true.
 * Это делается через перехват одной из наших функций самим framework.
 */
public class MainActivity extends Activity {

    // Эта функция перехватывается LSPosed framework - так модуль узнаёт что framework активен
    private static boolean isModuleActive() {
        return false;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 48, 48, 48);

        TextView statusView = new TextView(this);
        statusView.setTextSize(24);
        statusView.setGravity(Gravity.CENTER);
        if (isModuleActive()) {
            statusView.setText("✓ Модуль активен");
            statusView.setTextColor(Color.rgb(0, 150, 0));
        } else {
            statusView.setText("✗ Модуль НЕ активен");
            statusView.setTextColor(Color.RED);
        }
        layout.addView(statusView);

        TextView infoView = new TextView(this);
        infoView.setTextSize(16);
        infoView.setPadding(0, 48, 0, 0);
        infoView.setMovementMethod(new ScrollingMovementMethod());
        infoView.setText(
            "Для работы модуля:\n" +
            "1. Открой LSPosed Manager\n" +
            "2. В разделе 'Модули' включи 'Radio Name Hook'\n" +
            "3. Открой настройки модуля → раздел 'Область'\n" +
            "4. Выбери приложение: com.ecarx.multimedia\n" +
            "5. Перезагрузи устройство\n" +
            "\n" +
            "База станций находится в:\n" +
            "/sdcard/RadioNames/stations.json\n" +
            "\n" +
            "Формат JSON:\n" +
            "{\n" +
            "  \"fm\": {\n" +
            "    \"103.4\": \"Русское Радио\",\n" +
            "    \"104.2\": \"Авторадио\"\n" +
            "  },\n" +
            "  \"am\": {\n" +
            "    \"675\": \"Маяк\"\n" +
            "  }\n" +
            "}\n" +
            "\n" +
            "Для отладки смотри logcat:\n" +
            "adb logcat | grep RadioNameHook"
        );
        layout.addView(infoView);

        setContentView(layout);
    }
}
