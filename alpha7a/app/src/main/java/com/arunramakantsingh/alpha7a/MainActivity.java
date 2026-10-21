package com.arunramakantsingh.alpha7a;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.util.Base64;
import android.widget.EditText;
import android.view.Gravity;
import android.view.View;
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

    /**
     * Official Termux release asset, pinned after checking the termux/termux-app GitHub
     * Releases page on 2026-10-21:
     *   - v0.118.3 is the newest stable release. v0.119.0-beta.3 is a pre-release and is
     *     deliberately not used.
     *   - termux-app_v0.118.3+github-debug_universal.apk is the universal APK of that
     *     release, so it installs on every ABI. Termux v0.118.3 is built with
     *     minSdkVersion 24 (Android 7.0), so it is the Android 7+ build; the
     *     "android-5"/"android-7" asset split only exists in the v0.119.0-beta line.
     *   - The download was verified live: HTTP 200, content type
     *     application/vnd.android.package-archive, 117775723 bytes.
     *   - SHA-256, from the release's own termux-app_v0.118.3+github-debug_sha256sums:
     *     7600078440c3c34ef050bc009b00fc3215cb87ec4a449e01a696f74cf4249db2
     * Bump the tag and the asset name together when a new stable Termux release appears.
     */
    private static final String TERMUX_APK_URL =
            "https://github.com/termux/termux-app/releases/download/v0.118.3/termux-app_v0.118.3+github-debug_universal.apk";
    private static final String TERMUX_RELEASE_PAGE =
            "https://github.com/termux/termux-app/releases/tag/v0.118.3";
    private static final String APK_MIME = "application/vnd.android.package-archive";
    private static final String PREFS = "alpha7a";
    private static final String KEY_DOWNLOAD_ID = "termux_download_id";
    private static final String KEY_INSTALL_PROMPTED = "termux_install_prompted";

    private TextView downloadStatus;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override public void run() { updateDownloadStatus(); }
    };

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

        Button installTermux = button("1. Install official Termux");
        installTermux.setOnClickListener(v -> startTermuxInstall());
        box.addView(installTermux);

        downloadStatus = text("", 14);
        downloadStatus.setVisibility(View.GONE);
        box.addView(downloadStatus);

        Button openTermux = button("2. Configure Termux external commands");
        openTermux.setOnClickListener(v -> {
            copy("mkdir -p ~/.termux && printf '%s\\n' 'allow-external-apps=true' > ~/.termux/termux.properties");
            launch(TERMUX);
            Toast.makeText(this, "Command copied. Paste it in Termux, press Enter, then return here.", Toast.LENGTH_LONG).show();
        });
        box.addView(openTermux);

        Button permission = button("3. Grant Alpha7A: Run commands in Termux");
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

        Button provision = button("4. START ALPHA7A PROVISIONING");
        provision.setOnClickListener(v -> provision());
        box.addView(provision);

        box.addView(text(
            "Expected test flow:\n" +
            "• Official Termux APK downloaded from the official release and installed first\n" +
            "• allow-external-apps=true in Termux\n" +
            "• Android grants Alpha7A the Termux RUN_COMMAND permission\n" +
            "• Tap START once\n" +
            "• Termux performs package, Tailscale, Debian, SSH, proxy and web-page provisioning\n\n" +
            "The provisioning payload is included in the APK, but no Tailscale auth key is embedded. Enter a device auth key in the first-run dialog; the app supplies it to Termux only when provisioning starts.",
            14));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);
        setContentView(scroll);
    }

    private String statusText() {
        boolean termux = false, granted = false;
        try { getPackageManager().getPackageInfo(TERMUX, 0); termux = true; } catch (Exception ignored) {}
        granted = checkSelfPermission(RUN_PERMISSION) == PackageManager.PERMISSION_GRANTED;
        return "Alpha7A " + appVersion() + "\nTermux installed: " + termux + "\nAlpha7A RUN_COMMAND permission: " + granted;
    }

    /** Code name Alpha7A, release version from the package manifest. */
    private String appVersion() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            long code = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? info.getLongVersionCode() : info.versionCode;
            return "v" + info.versionName + " (" + code + ")";
        } catch (Exception e) {
            return "version unknown";
        }
    }

    private void provision() {
        if (!isTermuxInstalled()) {
            Toast.makeText(this, "Install official Termux first, then return here.", Toast.LENGTH_LONG).show();
            startTermuxInstall();
            return;
        }
        if (checkSelfPermission(RUN_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Grant Alpha7A the Termux RUN_COMMAND permission first.", Toast.LENGTH_LONG).show();
            return;
        }
        runProvisioning();
    }

    /**
     * Downloads the official Termux APK with Android's DownloadManager and then hands the
     * file to the system package installer. The user still has to approve the install in
     * the normal Android prompt; nothing here bypasses Android's security checks.
     */
    private void startTermuxInstall() {
        if (isTermuxInstalled()) {
            Toast.makeText(this, "Termux is already installed.", Toast.LENGTH_LONG).show();
            launch(TERMUX);
            return;
        }
        DownloadManager dm = downloadManager();
        if (dm == null) {
            Toast.makeText(this, "This device does not provide the Android download service.", Toast.LENGTH_LONG).show();
            openTermuxReleasePage();
            return;
        }
        long id = prefs().getLong(KEY_DOWNLOAD_ID, -1L);
        if (id != -1L) {
            DownloadState state = queryDownload(dm, id);
            if (state != null && state.status == DownloadManager.STATUS_SUCCESSFUL) {
                promptInstall(dm, id);
                return;
            }
            if (state != null && isActive(state.status)) {
                updateDownloadStatus();
                return;
            }
            clearDownload();
        }
        try {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(TERMUX_APK_URL));
            request.setTitle("Termux");
            request.setDescription("Downloading the official Termux APK");
            request.setMimeType(APK_MIME);
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            long newId = dm.enqueue(request);
            prefs().edit().putLong(KEY_DOWNLOAD_ID, newId).putBoolean(KEY_INSTALL_PROMPTED, false).apply();
            Toast.makeText(this, "Downloading the official Termux APK...", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Could not start the Termux download: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        updateDownloadStatus();
    }

    private void updateDownloadStatus() {
        handler.removeCallbacks(ticker);
        if (downloadStatus == null) return;
        DownloadManager dm = downloadManager();
        long id = prefs().getLong(KEY_DOWNLOAD_ID, -1L);
        if (dm == null || id == -1L) {
            downloadStatus.setText("");
            downloadStatus.setVisibility(View.GONE);
            return;
        }
        DownloadState state = queryDownload(dm, id);
        if (state == null) {
            // The record is gone: cancelled in the system download UI or cleaned up.
            clearDownload();
            showDownloadStatus("Download cancelled. Tap the install button to try again.");
            return;
        }
        switch (state.status) {
            case DownloadManager.STATUS_PENDING:
                showDownloadStatus("Waiting to download the official Termux APK...");
                scheduleTick();
                break;
            case DownloadManager.STATUS_RUNNING:
                showDownloadStatus("Downloading Termux... " + progress(state.done, state.total));
                scheduleTick();
                break;
            case DownloadManager.STATUS_PAUSED:
                showDownloadStatus("Download paused. It resumes automatically when the connection allows.");
                scheduleTick();
                break;
            case DownloadManager.STATUS_SUCCESSFUL:
                showDownloadStatus("Termux downloaded (" + megabytes(state.done) + "). Approve the install to continue.");
                if (!prefs().getBoolean(KEY_INSTALL_PROMPTED, false)) {
                    prefs().edit().putBoolean(KEY_INSTALL_PROMPTED, true).apply();
                    promptInstall(dm, id);
                }
                break;
            default:
                showDownloadStatus("Download failed (code " + state.reason + "). Tap the install button to try again.");
                clearDownload();
                break;
        }
    }

    private void promptInstall(DownloadManager dm, long id) {
        Uri apk = null;
        try { apk = dm.getUriForDownloadedFile(id); } catch (Exception ignored) {}
        if (apk == null) {
            Toast.makeText(this, "The downloaded Termux APK is no longer available. Install it from the official release page.", Toast.LENGTH_LONG).show();
            openTermuxReleasePage();
            return;
        }
        Intent install = new Intent(Intent.ACTION_INSTALL_PACKAGE);
        install.setDataAndType(apk, APK_MIME);
        install.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(install);
            Toast.makeText(this, "Approve the installation in the Android installer, then return to Alpha7A.", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Intent view = new Intent(Intent.ACTION_VIEW);
            view.setDataAndType(apk, APK_MIME);
            view.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try {
                startActivity(view);
            } catch (Exception e2) {
                Toast.makeText(this, "Could not open the Android installer. Install Termux from the official release page instead.", Toast.LENGTH_LONG).show();
                openTermuxReleasePage();
            }
        }
    }

    private void openTermuxReleasePage() {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(TERMUX_RELEASE_PAGE))); }
        catch (Exception ignored) {}
    }

    private DownloadState queryDownload(DownloadManager dm, long id) {
        Cursor cursor = null;
        try {
            cursor = dm.query(new DownloadManager.Query().setFilterById(id));
            if (cursor == null || !cursor.moveToFirst()) return null;
            DownloadState state = new DownloadState();
            state.status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            state.done = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
            state.total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
            state.reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
            return state;
        } catch (Exception e) {
            return null;
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    private boolean isActive(int status) {
        return status == DownloadManager.STATUS_PENDING
                || status == DownloadManager.STATUS_RUNNING
                || status == DownloadManager.STATUS_PAUSED;
    }

    private String progress(long done, long total) {
        if (total > 0) {
            long percent = Math.min(100, (done * 100) / total);
            return percent + "% (" + megabytes(done) + " / " + megabytes(total) + ")";
        }
        return megabytes(done) + " of " + megabytes(total) + " downloaded";
    }

    private String megabytes(long bytes) {
        return String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0);
    }

    private void showDownloadStatus(String message) {
        if (downloadStatus == null) return;
        downloadStatus.setText(message);
        downloadStatus.setVisibility(View.VISIBLE);
    }

    private void scheduleTick() {
        handler.postDelayed(ticker, 700);
    }

    private DownloadManager downloadManager() {
        return (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private void clearDownload() {
        prefs().edit().putLong(KEY_DOWNLOAD_ID, -1L).putBoolean(KEY_INSTALL_PROMPTED, false).apply();
    }

    private static final class DownloadState {
        int status = -1;
        long done;
        long total;
        int reason;
    }

    private void runProvisioning() {
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

    @Override protected void onResume() { super.onResume(); showUi(); updateDownloadStatus(); }

    @Override protected void onPause() { super.onPause(); handler.removeCallbacks(ticker); }
}
