package com.arunramakantsingh.alpha7a;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final String TERMUX = "com.termux";
    private static final String RUN_PERMISSION = "com.termux.permission.RUN_COMMAND";
    private static final int REQ = 7001;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        showUi();
    }

    private TextView text(String s, int size) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(size); t.setTextColor(Color.DKGRAY);
        t.setPadding(24, 18, 24, 18);
        return t;
    }

    private Button button(String s) {
        Button b = new Button(this); b.setText(s);
        b.setAllCaps(false);
        b.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        return b;
    }

    private void showUi() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(18, 18, 18, 18);

        TextView title = text("Alpha7A\nLightweight Android Development Appliance", 24);
        title.setGravity(Gravity.CENTER);
        box.addView(title);

        box.addView(text(
            "Model A uses the normal maintained Termux app. Alpha7A is only the companion/provisioning controller. " +
            "After the two Android/Termux security approvals below, the full provisioning script runs inside Termux.",
            16));

        TextView status = text(statusText(), 15);
        box.addView(status);

        Button openTermux = button("1. Open Termux");
        openTermux.setOnClickListener(v -> {
            copy("mkdir -p ~/.termux && printf '%s\\n' 'allow-external-apps=true' > ~/.termux/termux.properties");
            launch(TERMUX);
            Toast.makeText(this, "Command copied. Paste it in Termux, press Enter, then return here.", Toast.LENGTH_LONG).show();
        });
        box.addView(openTermux);

        Button permission = button("2. Grant Alpha7A: Run commands in Termux");
        permission.setOnClickListener(v -> {
            try {
                requestPermissions(new String[]{RUN_PERMISSION}, REQ);
            } catch (Exception e) {
                openAppSettings();
            }
        });
        box.addView(permission);

        Button settings = button("Open Alpha7A app permissions");
        settings.setOnClickListener(v -> openAppSettings());
        box.addView(settings);

        Button provision = button("3. START ALPHA7A PROVISIONING");
        provision.setOnClickListener(v -> provision());
        box.addView(provision);

        box.addView(text(
            "Expected test flow:\n" +
            "• Official Termux installed first\n" +
            "• allow-external-apps=true in Termux\n" +
            "• Android grants Alpha7A the Termux RUN_COMMAND permission\n" +
            "• Tap START once\n" +
            "• Termux performs package, Tailscale, Debian, SSH, proxy and web-page provisioning\n\n" +
            "The Alpha7A APK contains the test provisioning payload. Treat this test APK as sensitive because the configured Tailscale credential is embedded during the private build.",
            14));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);
        setContentView(scroll);
    }

    private String statusText() {
        boolean termux = false, granted = false;
        try { getPackageManager().getPackageInfo(TERMUX, 0); termux = true; } catch (Exception ignored) {}
        granted = checkSelfPermission(RUN_PERMISSION) == PackageManager.PERMISSION_GRANTED;
        return "Termux installed: " + termux + "\nAlpha7A RUN_COMMAND permission: " + granted;
    }

    private void provision() {
        if (!isTermuxInstalled()) {
            Toast.makeText(this, "Install the official Termux app first.", Toast.LENGTH_LONG).show();
            launch(TERMUX); return;
        }
        if (checkSelfPermission(RUN_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Grant Alpha7A the Termux RUN_COMMAND permission first.", Toast.LENGTH_LONG).show();
            return;
        }

        String script = loadPayload();
        Intent i = new Intent("com.termux.RUN_COMMAND");
        i.setComponent(new ComponentName(TERMUX, "com.termux.app.RunCommandService"));
        i.putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash");
        i.putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home");
        i.putExtra("com.termux.RUN_COMMAND_BACKGROUND", false);
        i.putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0");
        i.putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", "Alpha7A provisioning");
        i.putExtra("com.termux.RUN_COMMAND_STDIN", script);
        try {
            startService(i);
            Toast.makeText(this, "Provisioning started in Termux.", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Could not start Termux command: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private String loadPayload() {
        try {
            java.io.InputStream in = getAssets().open("setup.sh");
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            return out.toString("UTF-8");
        } catch (Exception e) {
            return "echo 'Alpha7A payload missing'; exit 1\n";
        }
    }

    private boolean isTermuxInstalled() {
        try { getPackageManager().getPackageInfo(TERMUX, 0); return true; }
        catch (Exception e) { return false; }
    }

    private void launch(String pkg) {
        Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i != null) startActivity(i);
    }

    private void copy(String s) {
        ClipboardManager cm = (ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("Alpha7A Termux setup", s));
    }

    private void openAppSettings() {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()));
        startActivity(i);
    }

    @Override protected void onResume() { super.onResume(); showUi(); }
}
