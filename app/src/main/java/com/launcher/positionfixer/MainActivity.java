package com.launcher.positionfixer;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Minimal settings UI so the module can be opened from LSPosed Manager.
 */
public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#121212"));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(32), dp(24), dp(32));
        root.setBackgroundColor(Color.parseColor("#121212"));

        TextView title = text("Launcher Position Fixer", 22, Color.WHITE, true);
        title.setPadding(0, 0, 0, dp(8));
        root.addView(title);

        TextView version = text("v0.2.1  •  Pure Java LSPosed Module", 14, Color.parseColor("#AAAAAA"), false);
        version.setPadding(0, 0, 0, dp(24));
        root.addView(version);

        root.addView(section("What it does"));
        root.addView(body(
                "When Ice Box freezes an app (DISABLED_USER), the launcher icon may disappear or move.\n\n" +
                "This module saves the exact home-screen / folder position and restores it after unfreeze."
        ));

        root.addView(section("Scope (required)"));
        root.addView(body(
                "In LSPosed Manager → Modules → this module → Scope:\n\n" +
                "✓  com.android.launcher\n\n" +
                "Do NOT scope Ice Box, SystemUI, or system_server."
        ));

        root.addView(section("How to use"));
        root.addView(body(
                "1. Enable the module in LSPosed\n" +
                "2. Scope only to com.android.launcher\n" +
                "3. Reboot (or force-stop the launcher)\n" +
                "4. Freeze / unfreeze apps with Ice Box as usual"
        ));

        root.addView(section("Logs"));
        root.addView(body("Filter Logcat with tag:\nLauncherPositionFixer"));

        TextView footer = text("No settings needed. The module works automatically.", 13, Color.parseColor("#888888"), false);
        footer.setPadding(0, dp(28), 0, 0);
        root.addView(footer);

        scroll.addView(root);
        setContentView(scroll);
    }

    private TextView section(String s) {
        TextView t = text(s, 16, Color.parseColor("#90CAF9"), true);
        t.setPadding(0, dp(18), 0, dp(6));
        return t;
    }

    private TextView body(String s) {
        TextView t = text(s, 14, Color.parseColor("#DDDDDD"), false);
        t.setLineSpacing(dp(2), 1.15f);
        return t;
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(null, Typeface.BOLD);
        t.setGravity(Gravity.START);
        return t;
    }

    private int dp(int value) {
        float d = getResources().getDisplayMetrics().density;
        return Math.round(value * d);
    }
}
