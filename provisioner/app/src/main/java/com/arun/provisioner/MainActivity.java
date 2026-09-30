package com.arun.provisioner;

import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.Color;
import android.net.Uri;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import java.util.*;

public class MainActivity extends Activity {
    private static final String TERMUX = "com.termux";
    private static final String TERMUX_APK =
        "https://github.com/termux/termux-app/releases/download/v0.118.3/termux-app_v0.118.3%2Bgithub-debug_universal.apk";
    private static final String BOOTSTRAP =
        "curl -fsSL https://raw.githubusercontent.com/arunramakantsingh-coder/turmux-tailscal-debian/main/bootstrap/install.sh | bash";

    private DownloadManager dm;
    private long downloadId = -1;
    private Handler handler = new Handler(Looper.getMainLooper());
    private boolean installerLaunched = false;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        dm = (DownloadManager)getSystemService(DOWNLOAD_SERVICE);
        show("Ready");
        if (isTermuxInstalled()) {
            finishForTermux();
            return;
        }
        startDownloadIfNeeded();
        handler.post(poller);
    }

    @Override protected void onResume() {
        super.onResume();
        if (isTermuxInstalled()) finishForTermux();
    }

    private void show(String status) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(48,64,48,48);
        TextView title = new TextView(this);
        title.setText("Android Appliance Provisioner\n\nv0.3.0-alpha.1");
        title.setTextSize(25); title.setTextColor(Color.rgb(30,40,70));
        box.addView(title);
        TextView msg = new TextView(this);
        msg.setText("\n" + status + "\n\nStable Termux v0.118.3 will be downloaded from the official Termux GitHub release. Android will ask for installation approval.");
        msg.setTextSize(17);
        box.addView(msg);
        setContentView(box);
    }

    private void startDownloadIfNeeded() {
        try {
            SharedPreferences p = getSharedPreferences("p",0);
            downloadId = p.getLong("download_id",-1);
            if (downloadId > 0) {
                DownloadManager.Query q = new DownloadManager.Query().setFilterById(downloadId);
                android.database.Cursor c = dm.query(q);
                if (c != null && c.moveToFirst()) { c.close(); return; }
                if (c != null) c.close();
            }
            Uri uri = Uri.parse(TERMUX_APK);
            DownloadManager.Request req = new DownloadManager.Request(uri);
            req.setTitle("Termux v0.118.3");
            req.setDescription("Official stable Termux APK");
            req.setMimeType("application/vnd.android.package-archive");
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setAllowedOverMetered(true);
            req.setAllowedOverRoaming(true);
            downloadId = dm.enqueue(req);
            p.edit().putLong("download_id",downloadId).apply();
            show("Downloading official Termux v0.118.3…");
        } catch(Exception e) {
            show("Download could not start: " + e.getMessage());
        }
    }

    private final Runnable poller = new Runnable() {
        public void run() {
            if (isTermuxInstalled()) { finishForTermux(); return; }
            if (downloadId > 0) checkDownload();
            handler.postDelayed(this, 1200);
        }
    };

    private void checkDownload() {
        DownloadManager.Query q = new DownloadManager.Query().setFilterById(downloadId);
        android.database.Cursor c = dm.query(q);
        if (c == null) return;
        try {
            if (!c.moveToFirst()) return;
            int status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            if (status == DownloadManager.STATUS_SUCCESSFUL && !installerLaunched) {
                installerLaunched = true;
                Uri apk = dm.getUriForDownloadedFile(downloadId);
                if (apk != null) launchInstaller(apk);
            } else if (status == DownloadManager.STATUS_FAILED) {
                show("Termux download failed. Please restart the Provisioner.");
            }
        } finally { c.close(); }
    }

    private void launchInstaller(Uri apk) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(apk, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            show("Termux download complete. Android installation approval should appear.");
        } catch(Exception e) {
            show("Android blocked the installer launch. Open the completed Termux download from the notification, or enable Install unknown apps for this Provisioner.");
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName())));
            } catch(Exception ignored) {}
        }
    }

    private boolean isTermuxInstalled() {
        try { getPackageManager().getPackageInfo(TERMUX,0); return true; }
        catch(Exception e) { return false; }
    }

    private void finishForTermux() {
        handler.removeCallbacks(poller);
        getSystemService(android.content.ClipboardManager.class)
            .setPrimaryClip(android.content.ClipData.newPlainText("AIF appliance bootstrap", BOOTSTRAP));
        Toast.makeText(this, "Termux detected. Bootstrap command copied to clipboard.", Toast.LENGTH_LONG).show();
        Intent launch = getPackageManager().getLaunchIntentForPackage(TERMUX);
        if (launch != null) startActivity(launch);
        finish();
    }
}
