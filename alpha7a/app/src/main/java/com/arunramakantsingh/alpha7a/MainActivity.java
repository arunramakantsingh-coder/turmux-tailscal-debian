package com.arunramakantsingh.alpha7a;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Alpha7A - companion and provisioning controller for the Android Development Appliance.
 *
 * <p>The app owns three things: installing the official Termux app, letting the user supply the
 * Tailscale auth key used for provisioning, and reporting - honestly - what is actually running.
 */
public class MainActivity extends Activity {
    private static final String TERMUX = TermuxRunner.TERMUX_PACKAGE;
    private static final String RUN_PERMISSION = "com.termux.permission.RUN_COMMAND";

    // Dark palette. Contrast against SURFACE: TEXT 14.9:1, MUTED 8.1:1, OK 8.4:1, WARN 9.6:1, ERR 6.1:1.
    private static final int BG = 0xFF0B1020;
    private static final int SURFACE = 0xFF151C33;
    private static final int SURFACE_ALT = 0xFF1E2745;
    private static final int TEXT = 0xFFEEF2FF;
    private static final int MUTED = 0xFFA9B3D0;
    private static final int PRIMARY = 0xFF4F7CFF;
    private static final int OK = 0xFF45D483;
    private static final int WARN = 0xFFFFBD4A;
    private static final int ERR = 0xFFFF6B6B;
    private static final int IDLE = 0xFF6B7391;

    /**
     * Official Termux release asset, pinned after checking the termux/termux-app GitHub Releases
     * page on 2026-10-21:
     *   - v0.118.3 is the newest stable release. v0.119.0-beta.3 is a pre-release and is
     *     deliberately not used.
     *   - termux-app_v0.118.3+github-debug_universal.apk is the universal APK of that release, so
     *     it installs on every ABI. Termux v0.118.3 is built with minSdkVersion 24 (Android 7.0),
     *     so it is the Android 7+ build; the "android-5"/"android-7" asset split only exists in
     *     the v0.119.0-beta line.
     *   - The download was verified live: HTTP 200, content type
     *     application/vnd.android.package-archive, 117775723 bytes.
     *   - SHA-256, from the release's own termux-app_v0.118.3+github-debug_sha256sums:
     *     7600078440c3c34ef050bc009b00fc3215cb87ec4a449e01a696f74cf4249db2
     */
    private static final String TERMUX_APK_URL =
            "https://github.com/termux/termux-app/releases/download/v0.118.3/termux-app_v0.118.3+github-debug_universal.apk";
    private static final String TERMUX_APK_SHA256 =
            "7600078440c3c34ef050bc009b00fc3215cb87ec4a449e01a696f74cf4249db2";
    private static final String TERMUX_RELEASE_PAGE =
            "https://github.com/termux/termux-app/releases/tag/v0.118.3";
    private static final String APK_MIME = "application/vnd.android.package-archive";

    private static final String PREFS = "alpha7a";
    private static final String KEY_DOWNLOAD_ID = "termux_download_id";
    private static final String KEY_INSTALL_PROMPTED = "termux_install_prompted";
    private static final String KEY_TS_AUTHKEY = "tailscale_authkey";
    private static final String KEY_LOG = "activity_log";

    /** Component rows, in display order. */
    private static final String ROW_TERMUX = "Termux";
    private static final String ROW_TERMUX_SSH = "Termux SSH :8022";
    private static final String ROW_TAILSCALE_CLI = "Tailscale CLI";
    private static final String ROW_TAILSCALE = "Tailscale connection";
    private static final String ROW_DEBIAN = "Debian";
    private static final String ROW_DEBIAN_SSH = "Debian SSH :2222";

    private final Map<String, Status> statuses = new LinkedHashMap<>();
    private TextView downloadStatus;
    private LinearLayout statusContainer;
    private TextView logView;
    private EditText keyInput;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override public void run() { updateDownloadStatus(); }
    };

    /** One row of the component status list. */
    private static final class Status {
        String state = "Not checked";
        String detail = "";
        int color = IDLE;
    }

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().getDecorView().setBackgroundColor(BG);
        showUi();
    }

    // ---------------------------------------------------------------- user interface

    private TextView text(String s, int size, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setPadding(24, 14, 24, 14);
        return t;
    }

    private Button button(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(TEXT);
        b.setBackgroundColor(SURFACE_ALT);
        b.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        return b;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundColor(SURFACE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 0, 0, 24);
        c.setLayoutParams(lp);
        return c;
    }

    private void showUi() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(18, 18, 18, 18);
        box.setBackgroundColor(BG);

        TextView title = text("Alpha7A", 26, TEXT);
        title.setGravity(Gravity.CENTER);
        box.addView(title);
        TextView subtitle = text("Android Development Appliance  •  " + appVersion(), 14, MUTED);
        subtitle.setGravity(Gravity.CENTER);
        box.addView(subtitle);

        // Termux installation card.
        LinearLayout install = card();
        install.addView(text("Termux", 17, TEXT));
        install.addView(text(termuxSummary(), 14, MUTED));
        install.addView(text("Official asset SHA-256: " + TERMUX_APK_SHA256, 11, IDLE));
        downloadStatus = text("", 14, MUTED);
        downloadStatus.setVisibility(View.GONE);
        install.addView(downloadStatus);
        Button installButton = button("1. Install official Termux");
        installButton.setOnClickListener(v -> startTermuxInstall());
        install.addView(installButton);
        Button openTermux = button("Open Termux");
        openTermux.setOnClickListener(v -> launch(TERMUX));
        install.addView(openTermux);
        Button allowExternal = button("2. Allow Alpha7A to run commands in Termux");
        allowExternal.setOnClickListener(v -> {
            copy("mkdir -p ~/.termux && printf '%s\\n' 'allow-external-apps=true' > ~/.termux/termux.properties");
            launch(TERMUX);
            appendLog("Copied the allow-external-apps command. Paste it in Termux, press Enter, then return to Alpha7A.");
            toast("Command copied. Paste it in Termux, press Enter, then return here.");
        });
        install.addView(allowExternal);
        box.addView(install);

        // Component status card.
        LinearLayout statusCard = card();
        statusCard.addView(text("Component status", 17, TEXT));
        statusContainer = new LinearLayout(this);
        statusContainer.setOrientation(LinearLayout.VERTICAL);
        statusCard.addView(statusContainer);
        Button detect = button("Refresh status");
        detect.setOnClickListener(v -> detectComponents());
        statusCard.addView(detect);
        box.addView(statusCard);

        // Provisioning card.
        LinearLayout provision = card();
        provision.addView(text("Provisioning", 17, TEXT));
        provision.addView(text("Runs the full setup inside Termux: packages, Tailscale, Debian and both SSH services. Android will ask you to approve the Termux permission the first time.", 14, MUTED));
        Button start = button("3. Start provisioning");
        start.setOnClickListener(v -> startProvisioning());
        provision.addView(start);
        box.addView(provision);

        // Tailscale key card.
        LinearLayout keyCard = card();
        keyCard.addView(text("Tailscale auth key", 17, TEXT));
        keyCard.addView(text("Optional. Overrides the key embedded in this build for the next provisioning run. It is stored in this app's private storage and is never written to the public page or the repository.", 14, MUTED));
        keyInput = new EditText(this);
        keyInput.setHint("tskey-auth-...");
        keyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        keyInput.setTextColor(TEXT);
        keyInput.setHintTextColor(MUTED);
        keyInput.setBackgroundColor(SURFACE_ALT);
        keyInput.setPadding(24, 14, 24, 14);
        keyInput.setText(savedTailscaleKey());
        keyCard.addView(keyInput);
        Button saveKey = button("Save key");
        saveKey.setOnClickListener(v -> saveTailscaleKey());
        keyCard.addView(saveKey);
        Button clearKey = button("Clear key");
        clearKey.setOnClickListener(v -> clearTailscaleKey());
        keyCard.addView(clearKey);
        box.addView(keyCard);

        // Activity log card.
        LinearLayout logCard = card();
        logCard.addView(text("Activity log", 17, TEXT));
        logView = text(savedLog(), 13, MUTED);
        logView.setTypeface(android.graphics.Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        logCard.addView(logView);
        Button clearLog = button("Clear log");
        clearLog.setOnClickListener(v -> {
            prefs().edit().remove(KEY_LOG).apply();
            logView.setText("");
        });
        logCard.addView(clearLog);
        box.addView(logCard);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.addView(box);
        setContentView(scroll);

        renderStatuses();
    }

    private void renderStatuses() {
        if (statusContainer == null) return;
        statusContainer.removeAllViews();
        for (Map.Entry<String, Status> entry : statuses.entrySet()) {
            Status s = entry.getValue();
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackgroundColor(SURFACE_ALT);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.setMargins(0, 0, 0, 8);
            row.setLayoutParams(lp);

            TextView name = text(entry.getKey(), 14, TEXT);
            name.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
            row.addView(name);

            TextView state = text(s.state, 13, s.color);
            state.setGravity(Gravity.END);
            row.addView(state);
            statusContainer.addView(row);

            if (!s.detail.isEmpty()) {
                TextView detail = text("    " + s.detail, 12, MUTED);
                statusContainer.addView(detail);
            }
        }
        if (statuses.isEmpty()) {
            TextView none = text("Nothing checked yet. Tap \"Refresh status\".", 14, MUTED);
            statusContainer.addView(none);
        }
    }

    private void setStatus(String row, String state, String detail, int color) {
        Status s = statuses.get(row);
        if (s == null) {
            s = new Status();
            statuses.put(row, s);
        }
        s.state = state;
        s.detail = detail == null ? "" : detail;
        s.color = color;
        renderStatuses();
    }

    // ---------------------------------------------------------------- status probing

    /** Runs a probe inside Termux and reports what the appliance actually has. */
    private void detectComponents() {
        if (!TermuxRunner.isTermuxInstalled(this)) {
            setStatus(ROW_TERMUX, "Not installed", "Install the official Termux app first.", ERR);
            setStatus(ROW_TAILSCALE_CLI, "Not checked", "", IDLE);
            setStatus(ROW_TAILSCALE, "Not checked", "", IDLE);
            return;
        }
        setStatus(ROW_TERMUX, "Installed", "v" + TermuxRunner.termuxVersion(this), OK);
        setStatus(ROW_TAILSCALE_CLI, "Checking", "", PRIMARY);
        appendLog("Probing Termux for the userspace Tailscale client...");

        String probe =
                "echo \"alpha7a:probe:start\"\n" +
                "if command -v tailscale >/dev/null 2>&1; then\n" +
                "  echo \"cli=present\"\n" +
                "  echo \"cli_path=$(command -v tailscale)\"\n" +
                "  echo \"cli_version=$(tailscale version 2>/dev/null | head -1)\"\n" +
                "else\n" +
                "  echo \"cli=missing\"\n" +
                "fi\n" +
                "if command -v pgrep >/dev/null 2>&1 && pgrep -x tailscaled >/dev/null 2>&1; then echo \"daemon=running\"; else echo \"daemon=stopped\"; fi\n" +
                "st=$(tailscale status --json 2>/dev/null | grep -o '\"BackendState\":\"[^\"]*\"' | head -1)\n" +
                "echo \"backend=${st:-unknown}\"\n" +
                "ip=$(tailscale ip -4 2>/dev/null | head -1)\n" +
                "echo \"ip=${ip:-none}\"\n" +
                "if tailscale status >/dev/null 2>&1; then echo \"status=ok\"; else echo \"status=unavailable\"; fi\n" +
                "if command -v proot-distro >/dev/null 2>&1 && proot-distro login debian -- true >/dev/null 2>&1; then echo \"debian=present\"; else echo \"debian=absent\"; fi\n" +
                "if command -v ss >/dev/null 2>&1; then\n" +
                "  ss -lnt 2>/dev/null | grep -qE ':8022\\b' && echo \"termux_ssh=listening\" || echo \"termux_ssh=down\"\n" +
                "  ss -lnt 2>/dev/null | grep -qE ':2222\\b' && echo \"debian_ssh=listening\" || echo \"debian_ssh=down\"\n" +
                "else echo \"termux_ssh=unknown\"; echo \"debian_ssh=unknown\"; fi\n" +
                "echo \"alpha7a:probe:end\"\n";

        TermuxRunner.run(this, "Alpha7A status probe", "Alpha7A is checking which appliance components are present.",
                probe, result -> {
                    appendLog("Probe finished (exit " + result.exitCode + ").");
                    applyProbe(result);
                });
    }

    private void applyProbe(TermuxRunner.Result result) {
        String out = result.stdout;
        if (out.isEmpty()) {
            String why = result.errMsg.isEmpty() ? result.stderr : result.errMsg;
            setStatus(ROW_TAILSCALE_CLI, "Check failed", TermuxRunner.Result.tail(why), ERR);
            setStatus(ROW_TAILSCALE, "Unknown", "", IDLE);
            setStatus(ROW_DEBIAN, "Unknown", "", IDLE);
            return;
        }
        Map<String, String> kv = new LinkedHashMap<>();
        for (String line : out.split("\n")) {
            int i = line.indexOf('=');
            if (i > 0) kv.put(line.substring(0, i).trim(), line.substring(i + 1).trim());
        }

        if ("present".equals(kv.get("cli"))) {
            setStatus(ROW_TAILSCALE_CLI, "Detected",
                    (kv.get("cli_path") == null ? "" : kv.get("cli_path"))
                            + (kv.get("cli_version") == null ? "" : "  " + kv.get("cli_version")), OK);
        } else {
            setStatus(ROW_TAILSCALE_CLI, "Not installed",
                    "The userspace Tailscale client is missing. Provisioning installs it.", WARN);
        }

        String backend = kv.get("backend");
        if (backend != null && backend.contains("\"Running\"")) {
            setStatus(ROW_TAILSCALE, "Connected",
                    (kv.get("ip") == null ? "" : kv.get("ip") + "  ")
                            + ("running".equals(kv.get("daemon")) ? "" : "daemon not running"), OK);
        } else if (backend != null && backend.contains("NeedsLogin")) {
            setStatus(ROW_TAILSCALE, "Needs login", "The Tailscale node is not authenticated.", WARN);
        } else if ("ok".equals(kv.get("status"))) {
            setStatus(ROW_TAILSCALE, "Reachable", backend == null ? "" : backend, WARN);
        } else {
            setStatus(ROW_TAILSCALE, "Not connected",
                    "No authenticated Tailscale session was detected.", IDLE);
        }

        if ("present".equals(kv.get("debian"))) {
            setStatus(ROW_DEBIAN, "Present", "proot-distro can start the Debian userland.", OK);
        } else {
            setStatus(ROW_DEBIAN, "Not installed", "Debian is installed by provisioning.", IDLE);
        }

        String termuxSsh = kv.get("termux_ssh");
        if ("listening".equals(termuxSsh)) {
            setStatus(ROW_TERMUX_SSH, "Listening", "verified on port 8022", OK);
        } else if ("down".equals(termuxSsh)) {
            setStatus(ROW_TERMUX_SSH, "Not listening", "port 8022 is closed", IDLE);
        } else {
            setStatus(ROW_TERMUX_SSH, "Unknown", "could not check", IDLE);
        }

        String debianSsh = kv.get("debian_ssh");
        if ("listening".equals(debianSsh)) {
            setStatus(ROW_DEBIAN_SSH, "Listening", "verified on port 2222", OK);
        } else if ("down".equals(debianSsh)) {
            setStatus(ROW_DEBIAN_SSH, "Not listening", "port 2222 is closed", IDLE);
        } else {
            setStatus(ROW_DEBIAN_SSH, "Unknown", "could not check", IDLE);
        }
    }

    // ---------------------------------------------------------------- provisioning

    private void startProvisioning() {
        if (!TermuxRunner.isTermuxInstalled(this)) {
            toast("Install the official Termux app first.");
            startTermuxInstall();
            return;
        }
        if (checkSelfPermission(RUN_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            setStatus(ROW_TERMUX, "Action needed", "Approve the Termux permission dialog when it appears.", WARN);
            toast("Termux will ask you to allow Alpha7A to run commands. Tap \"Open Termux\" if you do not see the prompt.");
        }
        String payload = loadPayload();
        if (payload.startsWith("echo 'Alpha7A payload missing'")) {
            toast("The provisioning payload is missing from this build.");
            return;
        }
        String key = savedTailscaleKey();
        StringBuilder script = new StringBuilder();
        if (!key.isEmpty()) {
            script.append("export TS_AUTHKEY='").append(shellSingleQuote(key)).append("'\n");
            appendLog("Using the Tailscale key stored in this app.");
        } else {
            appendLog("No key stored in the app: the key embedded in this build will be used.");
        }
        script.append(payload);

        appendLog("Provisioning started in Termux.");
        TermuxRunner.run(this, "Alpha7A provisioning", "Alpha7A is provisioning the appliance: Termux packages, Tailscale, Debian and SSH.",
                script.toString(), result -> {
                    appendLog("Provisioning finished with exit code " + result.exitCode
                            + (result.truncated ? " (output truncated)" : "") + ".");
                    appendLog(result.summary());
                    detectComponents();
                });
    }

    private String shellSingleQuote(String value) {
        return value.replace("'", "'\\''");
    }

    // ---------------------------------------------------------------- Termux install

    private void startTermuxInstall() {
        if (TermuxRunner.isTermuxInstalled(this)) {
            toast("Termux is already installed.");
            launch(TERMUX);
            return;
        }
        DownloadManager dm = downloadManager();
        if (dm == null) {
            toast("This device does not provide the Android download service.");
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
            appendLog("Started the download of the official Termux APK.");
        } catch (Exception e) {
            toast("Could not start the Termux download: " + e.getMessage());
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
                showDownloadStatus("Termux downloaded (" + megabytes(state.done) + "). The next screen is the Android installer: approve the install there.");
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
            toast("The downloaded Termux APK is no longer available. Install it from the official release page.");
            openTermuxReleasePage();
            return;
        }
        // Android always requires the user to confirm the install; there is no way around it.
        Intent install = new Intent(Intent.ACTION_VIEW);
        install.setDataAndType(apk, APK_MIME);
        install.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(install);
            appendLog("Handed the Termux APK to the Android package installer. Approve the install to continue.");
        } catch (Exception e) {
            try {
                Intent install2 = new Intent(Intent.ACTION_INSTALL_PACKAGE);
                install2.setDataAndType(apk, APK_MIME);
                install2.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(install2);
            } catch (Exception e2) {
                toast("Could not open the Android installer. Install Termux from the official release page instead.");
                openTermuxReleasePage();
            }
        }
    }

    private void openTermuxReleasePage() {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(TERMUX_RELEASE_PAGE))); }
        catch (Exception ignored) {}
    }

    // ---------------------------------------------------------------- helpers

    private String termuxSummary() {
        if (!TermuxRunner.isTermuxInstalled(this)) {
            return "Not installed. Alpha7A downloads the official APK from the termux/termux-app releases.";
        }
        return "Installed (v" + TermuxRunner.termuxVersion(this) + ").";
    }

    private String appVersion() {
        try {
            PackageManager pm = getPackageManager();
            PackageInfo info = pm.getPackageInfo(getPackageName(), 0);
            long code = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P
                    ? info.getLongVersionCode() : info.versionCode;
            return "v" + info.versionName + " (" + code + ")";
        } catch (Exception e) {
            return "version unknown";
        }
    }

    private String loadPayload() {
        try {
            java.io.InputStream in = getAssets().open("setup.sh");
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            return out.toString("UTF-8");
        } catch (Exception e) {
            return "echo 'Alpha7A payload missing'; exit 1\n";
        }
    }

    private void saveTailscaleKey() {
        if (keyInput == null) return;
        String key = keyInput.getText().toString().trim();
        if (key.isEmpty()) {
            clearTailscaleKey();
            return;
        }
        if (!key.startsWith("tskey-")) {
            toast("That does not look like a Tailscale auth key.");
            return;
        }
        prefs().edit().putString(KEY_TS_AUTHKEY, key).apply();
        appendLog("Tailscale key stored in the app's private storage.");
        toast("Key saved. It is used by the next provisioning run.");
    }

    private void clearTailscaleKey() {
        prefs().edit().remove(KEY_TS_AUTHKEY).apply();
        if (keyInput != null) keyInput.setText("");
        appendLog("Tailscale key cleared.");
        toast("Key cleared.");
    }

    private String savedTailscaleKey() {
        return prefs().getString(KEY_TS_AUTHKEY, "");
    }

    private void appendLog(String line) {
        String current = savedLog();
        String updated = current.isEmpty() ? line : current + "\n" + line;
        String[] lines = updated.split("\n");
        if (lines.length > 200) {
            StringBuilder trimmed = new StringBuilder();
            for (int i = lines.length - 200; i < lines.length; i++) {
                if (trimmed.length() > 0) trimmed.append("\n");
                trimmed.append(lines[i]);
            }
            updated = trimmed.toString();
        }
        prefs().edit().putString(KEY_LOG, updated).apply();
        if (logView != null) logView.setText(updated);
    }

    private String savedLog() {
        return prefs().getString(KEY_LOG, "");
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
        return megabytes(done) + " downloaded";
    }

    private String megabytes(long bytes) {
        return String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0);
    }

    private void launch(String pkg) {
        Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i != null) startActivity(i);
    }

    private void copy(String value) {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("Alpha7A Termux setup", value));
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private static final class DownloadState {
        int status = -1;
        long done;
        long total;
        int reason;
    }

    @Override protected void onResume() {
        super.onResume();
        showUi();
        updateDownloadStatus();
        if (TermuxRunner.isTermuxInstalled(this)) {
            setStatus(ROW_TERMUX, "Installed", "v" + TermuxRunner.termuxVersion(this), OK);
        } else {
            setStatus(ROW_TERMUX, "Not installed", "", ERR);
        }
    }

    @Override protected void onPause() {
        super.onPause();
        handler.removeCallbacks(ticker);
    }
}
