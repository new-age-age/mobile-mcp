package com.jusato.codecatcher;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final int COLOR_NAVY = 0xff1b2a3d;
    private static final int COLOR_WHITE = 0xffffffff;
    private static final int COLOR_BG = 0xfff5f7fa;
    private static final int COLOR_GREY = 0xff5b6675;

    private Switch switchPermissions;

    static final class PermissionsClickListener implements View.OnClickListener {
        private final MainActivity activity;

        PermissionsClickListener(MainActivity activity) {
            this.activity = activity;
        }

        @Override
        public void onClick(View v) {
            activity.openPermissionSettings();
        }
    }

    static final class PrefSwitchListener implements CompoundButton.OnCheckedChangeListener {
        private final MainActivity activity;
        private final String prefKey;

        PrefSwitchListener(MainActivity activity, String prefKey) {
            this.activity = activity;
            this.prefKey = prefKey;
        }

        @Override
        public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
            activity.getSharedPreferences(CodeCatcherService.PREFS, MODE_PRIVATE)
                    .edit().putBoolean(prefKey, isChecked).apply();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SharedPreferences prefs = getSharedPreferences(CodeCatcherService.PREFS, MODE_PRIVATE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_BG);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setBackgroundColor(COLOR_NAVY);
        header.setPadding(60, 80, 60, 80);
        TextView title = new TextView(this);
        title.setText("Code Catcher");
        title.setTextColor(COLOR_WHITE);
        title.setTextSize(24f);
        header.addView(title);
        root.addView(header);

        switchPermissions = addToggleRow(root, prefs, "Enable Permissions", null, false);
        switchPermissions.setOnClickListener(new PermissionsClickListener(this));
        addToggleRow(root, prefs, "Code Catch", CodeCatcherService.PREF_CODE_CATCH, true);
        addToggleRow(root, prefs, "Auto Paste", CodeCatcherService.PREF_AUTO_PASTE, false);

        TextView help = new TextView(this);
        help.setText("Volume+ ou tecla 353 captura o código (12 dígitos, sem -).\n\n"
                + "Auto Paste OFF: só copia para o clipboard.\n"
                + "Auto Paste ON: copia, toca em 配達 → 手入力 → campo 伝票番号を入力, cola + ENTER, espera 1 s e (sem toque na tela) toca em 配完.");
        help.setTextColor(COLOR_GREY);
        help.setTextSize(14f);
        help.setPadding(60, 40, 60, 40);
        root.addView(help);

        setContentView(root);
    }

    private Switch addToggleRow(LinearLayout parent, SharedPreferences prefs, String label,
                                final String prefKey, boolean def) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(60, 50, 60, 50);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView text = new TextView(this);
        text.setText(label);
        text.setTextColor(COLOR_NAVY);
        text.setTextSize(18f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.weight = 1f;
        row.addView(text, lp);

        Switch sw = new Switch(this);
        if (prefKey != null) {
            sw.setChecked(prefs.getBoolean(prefKey, def));
            sw.setOnCheckedChangeListener(new PrefSwitchListener(this, prefKey));
        }
        row.addView(sw);
        parent.addView(row);
        return sw;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (switchPermissions != null) {
            switchPermissions.setChecked(isAccessibilityServiceEnabled());
        }
    }

    private boolean isAccessibilityServiceEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return enabled != null
                && enabled.contains(getPackageName() + "/" + CodeCatcherService.class.getName());
    }

    void openPermissionSettings() {
        if (isAccessibilityServiceEnabled()) {
            switchPermissions.setChecked(true);
            Toast.makeText(this, "Permissão já concedida", Toast.LENGTH_SHORT).show();
            return;
        }
        switchPermissions.setChecked(false);
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            Toast.makeText(this, "Ative 'Code Catcher' em Acessibilidade", Toast.LENGTH_LONG).show();
        } catch (Exception ignored) {
        }
    }
}
