package com.arunramakantsingh.alpha7a;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
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
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Alpha7A - companion and provisioning controller for the Android Development Appliance.
 *
 * <p>The app owns three things: installing the official Termux app, holding the appliance
 * configuration (SSH accounts, ports and the optional Tailscale auth key), and reporting -
 * honestly - what is actually running.
 */
public class MainActivity extends Activity {
    private static final String TERMUX = TermuxRunner.TERMUX_PACKAGE;
    private static final String TAILSCALE_APP = "com.tailscale.ipn";
    private static final String RUN_PERMISSION = "com.termux.permission.RUN_COMMAND";
    private static final int REQ_PERMISSION = 7001;

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
     *     so it is the Android 7+ build.
     *   - Verified live: HTTP 200, application/vnd.android.package-archive, 117775723 bytes.
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
    private static final String KEY_TS_MODE = "tailscale_mode";
    private static final String KEY_LOG = "activity_log";
    private static final String KEY_TERMUX_PASSWORD = "termux_password";
    private static final String KEY_DEBIAN_USER = "debian_user";
    private static final String KEY_DEBIAN_PASSWORD = "debian_password";
    private static final String KEY_TERMUX_PORT = "termux_port";
    private static final String KEY_DEBIAN_PORT = "debian_port";
    private static final String KEY_TERMUX_USER = "termux_user";
    private static final String KEY_RUN_STARTED = "run_started";
    private static final String KEY_PHASE = "phase";
    private static final String KEY_TERMUX_VERIFIED = "termux_verified";

    private static final String ROW_TERMUX = "Termux";
    private static final String ROW_TERMUX_SSH = "Termux SSH";
    private static final String ROW_TAILSCALE = "Tailscale";
    private static final String ROW_DEBIAN = "Debian";
    private static final String ROW_DEBIAN_SSH = "Debian SSH";
    private static final String ROW_PROXY = "Proxy and status page";

    private final Map<String, ApplianceState.ComponentState> componentStates = new LinkedHashMap<>();
    private final Map<String, String> componentDetails = new LinkedHashMap<>();

    private LinearLayout componentsContainer;
    private LinearLayout actionsContainer;
    private TextView phaseLabel;
    private TextView stageLabel;
    private TextView elapsedLabel;
    private TextView technicalDetail;
    private TextView logView;
    private ProgressBar progressBar;
    private EditText keyInput;
    private EditText termuxPasswordInput;
    private EditText debianUserInput;
    private EditText debianPasswordInput;
    private EditText termuxPortInput;
    private EditText debianPortInput;
    private Button modeAppButton;
    private Button modeCliButton;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override public void run() { refreshElapsed(); updateDownloadStatus(); }
    };

    // ---------------------------------------------------------------- lifecycle

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().getDecorView().setBackgroundColor(BG);
        showUi();
    }

    @Override protected void onResume() {
        super.onResume();
        showUi();
        updateDownloadStatus();
        detectLocalComponents();
    }

    @Override protected void onPause() {
        super.onPause();
        handler.removeCallbacks(ticker);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQ_PERMISSION) return;
        boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        appendLog(granted ? "Termux granted Alpha7A the right to run commands."
                : "The Termux permission was not granted. Alpha7A cannot run commands yet.");
        if (granted) {
            verifyTermuxIntegration();
        } else {
            setComponent(ROW_TERMUX, ApplianceState.ComponentState.NEEDS_ACTION,
                    "Grant “Run commands in Termux” in the app info page");
        }
    }

    // ---------------------------------------------------------------- user interface

    private TextView text(String s, int size, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setPadding(20, 10, 20, 10);
        return t;
    }

    private Button button(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTextColor(TEXT);
        b.setBackgroundColor(SURFACE_ALT);
        b.setPadding(20, 12, 20, 12);
        b.setMinHeight(48);
        return b;
    }

    /** Primary action: filled with the accent colour. */
    private Button primaryButton(String s) {
        Button b = button(s);
        b.setBackgroundColor(PRIMARY);
        b.setTextColor(0xFFFFFFFF);
        b.setTextSize(15);
        b.setMinHeight(52);
        return b;
    }

    /** Secondary action: outlined. */
    private Button secondaryButton(String s) {
        Button b = button(s);
        b.setBackgroundColor(SURFACE_ALT);
        return b;
    }

    /** Contextual action: quiet text style. */
    private Button quietButton(String s) {
        Button b = button(s);
        b.setBackgroundColor(SURFACE);
        b.setTextColor(PRIMARY);
        b.setMinHeight(40);
        return b;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundColor(SURFACE);
        c.setPadding(16, 8, 16, 12);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 0, 0, 12);
        c.setLayoutParams(lp);
        return c;
    }

    private void showUi() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(14, 14, 14, 14);
        box.setBackgroundColor(BG);

        // Header.
        TextView title = text("Alpha7A", 24, TEXT);
        box.addView(title);
        TextView build = text("version " + shortBuild(), 13, MUTED);
        box.addView(build);
        phaseLabel = text(currentPhase().label, 15, phaseColor());
        box.addView(phaseLabel);

        // Progress card.
        LinearLayout progress = card();
        progress.addView(text("CURRENT OPERATION", 12, MUTED));
        stageLabel = text(currentStageDescription(), 15, TEXT);
        progress.addView(stageLabel);
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setIndeterminate(true);
        progressBar.setPadding(0, 6, 0, 6);
        progress.addView(progressBar);
        elapsedLabel = text(elapsedText(), 13, MUTED);
        progress.addView(elapsedLabel);
        technicalDetail = text(technicalDetailText(), 12, MUTED);
        technicalDetail.setVisibility(View.GONE);
        TextView toggle = quietButton("Technical detail");
        toggle.setOnClickListener(v -> technicalDetail.setVisibility(
                technicalDetail.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        progress.addView(toggle);
        box.addView(progress);

        // Components.
        LinearLayout components = card();
        components.addView(text("COMPONENTS", 12, MUTED));
        componentsContainer = new LinearLayout(this);
        componentsContainer.setOrientation(LinearLayout.VERTICAL);
        components.addView(componentsContainer);
        box.addView(components);

        // Actions.
        LinearLayout actions = card();
        actions.addView(text("ACTIONS", 12, MUTED));
        actionsContainer = new LinearLayout(this);
        actionsContainer.setOrientation(LinearLayout.VERTICAL);
        actions.addView(actionsContainer);
        box.addView(actions);

        // Tailscale.
        LinearLayout tsCard = card();
        tsCard.addView(text("TAILSCALE", 12, MUTED));
        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        modeAppButton = modeButton(ApplianceState.TailscaleMode.OFFICIAL_APP);
        modeCliButton = modeButton(ApplianceState.TailscaleMode.TERMUX_CLI);
        modeRow.addView(modeAppButton);
        modeRow.addView(modeCliButton);
        tsCard.addView(modeRow);
        styleModeButtons();
        TextView modeHint = text(modeHint(), 13, MUTED);
        tsCard.addView(modeHint);
        keyInput = field("tskey-auth-...", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        keyInput.setText(savedTailscaleKey());
        tsCard.addView(keyInput);
        LinearLayout tsButtons = new LinearLayout(this);
        tsButtons.setOrientation(LinearLayout.HORIZONTAL);
        Button saveKey = quietButton("Save key");
        saveKey.setOnClickListener(v -> saveTailscaleKey());
        Button clearKey = quietButton("Clear");
        clearKey.setOnClickListener(v -> {
            prefs().edit().remove(KEY_TS_AUTHKEY).apply();
            keyInput.setText("");
            appendLog("Tailscale key cleared.");
        });
        tsButtons.addView(saveKey);
        tsButtons.addView(clearKey);
        tsCard.addView(tsButtons);
        box.addView(tsCard);

        // SSH configuration.
        LinearLayout ssh = card();
        ssh.addView(text("SSH CONFIGURATION", 12, MUTED));
        ssh.addView(text("Termux account (the user name is assigned by Termux itself):", 13, MUTED));
        termuxPortInput = field(String.valueOf(sshConfig().termuxPort()), InputType.TYPE_CLASS_NUMBER);
        ssh.addView(labeled("Termux SSH port", termuxPortInput));
        termuxPasswordInput = field("", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        ssh.addView(labeled("Termux password (blank keeps Termux's own)", termuxPasswordInput));
        debianUserInput = field(sshConfig().debianUser(), InputType.TYPE_CLASS_TEXT);
        ssh.addView(labeled("Debian user", debianUserInput));
        debianPasswordInput = field("", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        ssh.addView(labeled("Debian password (required)", debianPasswordInput));
        debianPortInput = field(String.valueOf(sshConfig().debianPort()), InputType.TYPE_CLASS_NUMBER);
        ssh.addView(labeled("Debian SSH port", debianPortInput));
        Button saveSsh = quietButton("Save configuration");
        saveSsh.setOnClickListener(v -> saveSshConfig());
        ssh.addView(saveSsh);
        ssh.addView(text("Changing the Debian user name adds another account; the previous one is kept.", 12, MUTED));
        box.addView(ssh);

        // Activity.
        LinearLayout activity = card();
        activity.addView(text("ACTIVITY", 12, MUTED));
        logView = text(savedLog(), 13, MUTED);
        logView.setTypeface(android.graphics.Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        activity.addView(logView);
        Button clearLog = quietButton("Clear log");
        clearLog.setOnClickListener(v -> {
            prefs().edit().remove(KEY_LOG).apply();
            logView.setText("");
        });
        activity.addView(clearLog);
        box.addView(activity);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.addView(box);
        setContentView(scroll);

        renderComponents();
        renderActions();
    }

    private void styleModeButtons() {
        ApplianceState.TailscaleMode mode = currentMode();
        if (modeAppButton == null || modeCliButton == null) return;
        modeAppButton.setBackgroundColor(mode == ApplianceState.TailscaleMode.OFFICIAL_APP ? PRIMARY : SURFACE_ALT);
        modeAppButton.setTextColor(mode == ApplianceState.TailscaleMode.OFFICIAL_APP ? 0xFFFFFFFF : TEXT);
        modeCliButton.setBackgroundColor(mode == ApplianceState.TailscaleMode.TERMUX_CLI ? PRIMARY : SURFACE_ALT);
        modeCliButton.setTextColor(mode == ApplianceState.TailscaleMode.TERMUX_CLI ? 0xFFFFFFFF : TEXT);
    }

    private Button modeButton(ApplianceState.TailscaleMode mode) {
        Button b = secondaryButton(mode.label);
        b.setOnClickListener(v -> {
            prefs().edit().putString(KEY_TS_MODE, mode.name()).apply();
            showUi();
            appendLog("Tailscale mode set to " + mode.label + ".");
        });
        return b;
    }

    private EditText field(String value, int inputType) {
        EditText e = new EditText(this);
        e.setText(value);
        e.setInputType(inputType);
        e.setTextColor(TEXT);
        e.setHintTextColor(IDLE);
        e.setBackgroundColor(SURFACE_ALT);
        e.setPadding(20, 10, 20, 10);
        e.setSingleLine(true);
        return e;
    }

    private View labeled(String label, View field) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        TextView l = text(label, 13, MUTED);
        l.setPadding(20, 8, 20, 0);
        row.addView(l);
        row.addView(field);
        return row;
    }

    private String shortBuild() {
        String v = "";
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            v = info.versionName == null ? "" : info.versionName;
        } catch (Exception ignored) {
        }
        return v.isEmpty() ? "dev" : v;
    }

    // ---------------------------------------------------------------- components

    private void setComponent(String name, ApplianceState.ComponentState state, String detail) {
        componentStates.put(name, state);
        componentDetails.put(name, detail == null ? "" : detail);
        renderComponents();
    }

    private void renderComponents() {
        if (componentsContainer == null) return;
        componentsContainer.removeAllViews();
        if (componentStates.isEmpty()) {
            componentsContainer.addView(text("Nothing checked yet.", 14, MUTED));
            return;
        }
        for (Map.Entry<String, ApplianceState.ComponentState> entry : componentStates.entrySet()) {
            String name = entry.getKey();
            ApplianceState.ComponentState state = entry.getValue();
            String detail = componentDetails.containsKey(name) ? componentDetails.get(name) : "";

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackgroundColor(SURFACE_ALT);
            row.setPadding(16, 10, 16, 10);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
            rlp.setMargins(0, 0, 0, 6);
            row.setLayoutParams(rlp);

            TextView dot = text("●", 14, stateColor(state));
            row.addView(dot);
            TextView label = text(name, 14, TEXT);
            label.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
            row.addView(label);
            TextView value = text(state.label, 13, stateColor(state));
            value.setGravity(Gravity.END);
            row.addView(value);
            componentsContainer.addView(row);

            if (!detail.isEmpty()) {
                TextView d = text(detail, 12, MUTED);
                d.setPadding(30, 0, 16, 4);
                componentsContainer.addView(d);
            }
        }
    }

    private int stateColor(ApplianceState.ComponentState state) {
        switch (state) {
            case CONNECTED: return OK;
            case NEEDS_ACTION: return WARN;
            case FAILED: return ERR;
            case INSTALLING:
            case CONFIGURING: return PRIMARY;
            default: return IDLE;
        }
    }

    private int phaseColor() {
        switch (currentPhase()) {
            case COMPLETE: return OK;
            case FAILED: return ERR;
            case PREREQUISITE_REQUIRED: return WARN;
            case RUNNING:
            case VERIFYING: return PRIMARY;
            default: return MUTED;
        }
    }

    // ---------------------------------------------------------------- actions

    private void renderActions() {
        if (actionsContainer == null) return;
        actionsContainer.removeAllViews();
        boolean termuxInstalled = TermuxRunner.isTermuxInstalled(this);
        boolean authorised = prefs().getBoolean(KEY_TERMUX_VERIFIED, false);
        ApplianceState.Phase phase = currentPhase();

        Button primary;
        if (!termuxInstalled) {
            primary = primaryButton("Install official Termux");
            primary.setOnClickListener(v -> startTermuxInstall());
        } else if (!authorised) {
            primary = primaryButton("Authorise Termux");
            primary.setOnClickListener(v -> requestTermuxPermission());
        } else if (phase == ApplianceState.Phase.FAILED) {
            primary = primaryButton("Retry provisioning");
            primary.setOnClickListener(v -> startProvisioning());
        } else if (phase == ApplianceState.Phase.COMPLETE) {
            primary = primaryButton("Run provisioning again");
            primary.setOnClickListener(v -> startProvisioning());
        } else {
            primary = primaryButton("Start provisioning");
            primary.setOnClickListener(v -> startProvisioning());
        }
        actionsContainer.addView(primary);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button openTermux = secondaryButton("Open terminal");
        openTermux.setOnClickListener(v -> launch(TERMUX));
        Button refresh = secondaryButton("Refresh status");
        refresh.setOnClickListener(v -> detectComponents());
        row.addView(openTermux);
        row.addView(refresh);
        actionsContainer.addView(row);

        if (!authorised && termuxInstalled) {
            Button allow = quietButton("Allow external commands in Termux");
            allow.setOnClickListener(v -> allowExternalApps());
            actionsContainer.addView(allow);
        }
        if (currentMode() == ApplianceState.TailscaleMode.OFFICIAL_APP) {
            Button openTs = quietButton("Open Tailscale app");
            openTs.setOnClickListener(v -> launch(TAILSCALE_APP));
            actionsContainer.addView(openTs);
        } else {
            Button installTs = quietButton("Install Tailscale CLI in Termux");
            installTs.setOnClickListener(v -> installTailscaleCli());
            actionsContainer.addView(installTs);
        }
    }

    private String modeHint() {
        if (currentMode() == ApplianceState.TailscaleMode.OFFICIAL_APP) {
            return "The official app signs in on the phone and holds the VPN. Alpha7A can see that a "
                    + "tunnel is up, but it cannot read which node is signed in - open the app to check.";
        }
        return "The CLI runs inside Termux without the VPN slot. Alpha7A can read its full state, but "
                + "the two modes must not run at the same time: they would give the phone two identities.";
    }

    // ---------------------------------------------------------------- onboarding

    private void requestTermuxPermission() {
        appendLog("Asking Android for the Termux command permission.");
        try {
            requestPermissions(new String[]{RUN_PERMISSION}, REQ_PERMISSION);
        } catch (Exception e) {
            openAppPermissions();
        }
    }

    /** Fallback: the app info page, where Termux's permission appears under Additional permissions. */
    private void openAppPermissions() {
        appendLog("Opening the app info page for additional permissions.");
        try {
            Intent i = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception ignored) {
        }
    }

    private void allowExternalApps() {
        copy("mkdir -p ~/.termux && printf '%s\\n' 'allow-external-apps=true' > ~/.termux/termux.properties");
        launch(TERMUX);
        appendLog("Copied the allow-external-apps command. Paste it in Termux, press Enter, then return.");
        toast("Command copied. Paste it in Termux, press Enter, then return here.");
    }

    /** Proves the integration works by running a real command. */
    private void verifyTermuxIntegration() {
        TermuxRunner.run(this, "Alpha7A connection test", "Alpha7A is checking that it can run commands in Termux.",
                "echo alpha7a-ok\n", result -> {
                    if (result.ok() && result.stdout.contains("alpha7a-ok")) {
                        prefs().edit().putBoolean(KEY_TERMUX_VERIFIED, true).apply();
                        appendLog("Termux integration verified.");
                        setComponent(ROW_TERMUX, ApplianceState.ComponentState.CONNECTED,
                                "v" + TermuxRunner.termuxVersion(this) + " · commands allowed");
                        renderActions();
                    } else {
                        prefs().edit().putBoolean(KEY_TERMUX_VERIFIED, false).apply();
                        String why = result.rejected() ? "Termux refused the command." : "The test command did not complete.";
                        appendLog(why + " " + result.summary());
                        setComponent(ROW_TERMUX, ApplianceState.ComponentState.NEEDS_ACTION,
                                "Allow external commands in Termux, then try again");
                        renderActions();
                    }
                });
    }

    // ---------------------------------------------------------------- detection

    private void detectLocalComponents() {
        if (!TermuxRunner.isTermuxInstalled(this)) {
            setComponent(ROW_TERMUX, ApplianceState.ComponentState.NOT_INSTALLED, "");
            return;
        }
        setComponent(ROW_TERMUX, ApplianceState.ComponentState.CONNECTED,
                "v" + TermuxRunner.termuxVersion(this)
                        + (prefs().getBoolean(KEY_TERMUX_VERIFIED, false) ? " · commands allowed" : " · not authorised"));
    }

    /** Probes Termux for the real state of every appliance component. */
    private void detectComponents() {
        if (!TermuxRunner.isTermuxInstalled(this)) {
            setComponent(ROW_TERMUX, ApplianceState.ComponentState.NOT_INSTALLED, "");
            return;
        }
        if (!prefs().getBoolean(KEY_TERMUX_VERIFIED, false)) {
            appendLog("Termux is not authorised yet, so the components cannot be checked.");
            setComponent(ROW_TERMUX, ApplianceState.ComponentState.NEEDS_ACTION,
                    "Authorise Alpha7A to run commands in Termux");
            return;
        }
        setComponent(ROW_TERMUX, ApplianceState.ComponentState.CONNECTED,
                "v" + TermuxRunner.termuxVersion(this) + " · commands allowed");

        int tPort = sshConfig().termuxPort();
        int dPort = sshConfig().debianPort();
        String probe =
                "echo \"alpha7a:probe:start\"\n" +
                "echo \"termux_user=$(id -un)\"\n" +
                "if command -v pkg >/dev/null 2>&1; then echo \"termux_packages=ok\"; else echo \"termux_packages=missing\"; fi\n" +
                "if command -v tailscale >/dev/null 2>&1; then echo \"ts_cli=present\"; " +
                "else if command -v tailscale-cli >/dev/null 2>&1; then echo \"ts_cli=present\"; else echo \"ts_cli=missing\"; fi; fi\n" +
                "if pgrep -x tailscaled >/dev/null 2>&1; then echo \"ts_daemon=running\"; else echo \"ts_daemon=stopped\"; fi\n" +
                "st=$(tailscale status --json 2>/dev/null | grep -o '\"BackendState\":\"[^\"]*\"' | head -1)\n" +
                "echo \"ts_backend=${st:-unknown}\"\n" +
                "ip=$(tailscale ip -4 2>/dev/null | head -1)\n" +
                "echo \"ts_ip=${ip:-none}\"\n" +
                "if proot-distro login debian -- true >/dev/null 2>&1; then echo \"debian=present\"; else echo \"debian=absent\"; fi\n" +
                "if command -v ss >/dev/null 2>&1; then\n" +
                "  ss -lnt 2>/dev/null | grep -qE ':" + tPort + "\\b' && echo \"termux_ssh=listening\" || echo \"termux_ssh=down\"\n" +
                "  ss -lnt 2>/dev/null | grep -qE ':" + dPort + "\\b' && echo \"debian_ssh=listening\" || echo \"debian_ssh=down\"\n" +
                "else echo \"termux_ssh=unknown\"; echo \"debian_ssh=unknown\"; fi\n" +
                "if command -v tailscale-socks5 >/dev/null 2>&1; then echo \"proxy=present\"; else echo \"proxy=missing\"; fi\n" +
                "echo \"alpha7a:probe:end\"\n";

        appendLog("Checking the appliance components.");
        TermuxRunner.run(this, "Alpha7A status check", "Alpha7A is checking which appliance components are present.",
                probe, result -> {
                    if (!result.ok()) {
                        appendLog("The status check did not complete. " + result.summary());
                        if (result.rejected()) {
                            setComponent(ROW_TERMUX, ApplianceState.ComponentState.NEEDS_ACTION,
                                    "Termux refused the command");
                        }
                        return;
                    }
                    applyProbe(result.stdout);
                });
    }

    private void applyProbe(String out) {
        Map<String, String> kv = new LinkedHashMap<>();
        for (String line : out.split("\n")) {
            int i = line.indexOf('=');
            if (i > 0) kv.put(line.substring(0, i).trim(), line.substring(i + 1).trim());
        }
        String termuxUser = kv.get("termux_user");
        if (termuxUser != null && !termuxUser.isEmpty()) {
            prefs().edit().putString(KEY_TERMUX_USER, termuxUser).apply();
        }
        setComponent(ROW_TERMUX, ApplianceState.ComponentState.CONNECTED,
                "v" + TermuxRunner.termuxVersion(this) + " · commands allowed");

        if ("ok".equals(kv.get("termux_packages"))) {
            setComponent(ROW_TERMUX, ApplianceState.ComponentState.CONNECTED,
                    "v" + TermuxRunner.termuxVersion(this) + " · packages installed");
        } else {
            setComponent(ROW_TERMUX, ApplianceState.ComponentState.NEEDS_ACTION,
                    "Termux packages are not installed yet");
        }

        setComponent(ROW_TERMUX_SSH, sshState(kv.get("termux_ssh")),
                "port " + sshConfig().termuxPort());

        if (currentMode() == ApplianceState.TailscaleMode.TERMUX_CLI) {
            setComponent(ROW_TAILSCALE, tailscaleCliState(kv), tailscaleCliDetail(kv));
        } else {
            setComponent(ROW_TAILSCALE, officialAppState(), officialAppDetail());
        }

        if ("present".equals(kv.get("debian"))) {
            setComponent(ROW_DEBIAN, ApplianceState.ComponentState.CONNECTED, "proot-distro can start the userland");
        } else {
            setComponent(ROW_DEBIAN, ApplianceState.ComponentState.NOT_INSTALLED, "installed by provisioning");
        }
        setComponent(ROW_DEBIAN_SSH, sshState(kv.get("debian_ssh")),
                "port " + sshConfig().debianPort());
        setComponent(ROW_PROXY, "present".equals(kv.get("proxy"))
                        ? ApplianceState.ComponentState.CONNECTED : ApplianceState.ComponentState.NOT_INSTALLED,
                "SOCKS5 on 1055 · status page on 8080");

        appendLog("Component check finished.");
    }

    private ApplianceState.ComponentState sshState(String value) {
        if ("listening".equals(value)) return ApplianceState.ComponentState.CONNECTED;
        if ("down".equals(value)) return ApplianceState.ComponentState.DISCONNECTED;
        return ApplianceState.ComponentState.UNKNOWN;
    }

    private ApplianceState.ComponentState tailscaleCliState(Map<String, String> kv) {
        if (!"present".equals(kv.get("ts_cli"))) return ApplianceState.ComponentState.NOT_INSTALLED;
        String backend = kv.get("ts_backend");
        if (backend != null && backend.contains("Running")) {
            return ApplianceState.ComponentState.CONNECTED;
        }
        if (backend != null && backend.contains("NeedsLogin")) {
            return ApplianceState.ComponentState.NEEDS_ACTION;
        }
        if (!"running".equals(kv.get("ts_daemon"))) {
            return ApplianceState.ComponentState.DISCONNECTED;
        }
        return ApplianceState.ComponentState.UNKNOWN;
    }

    private String tailscaleCliDetail(Map<String, String> kv) {
        String ip = kv.get("ts_ip");
        if (ip == null || ip.isEmpty() || "none".equals(ip)) return "no address yet";
        return ip;
    }

    // ---------------------------------------------------------------- Tailscale mode A

    private boolean officialAppInstalled() {
        try {
            getPackageManager().getPackageInfo(TAILSCALE_APP, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean vpnActive() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            Network active = cm.getActiveNetwork();
            if (active == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(active);
            return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
        } catch (Exception e) {
            return false;
        }
    }

    /** A 100.64.0.0/10 address on a local interface is evidence that a tunnel is up. */
    private String cgnatAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (!ni.isUp()) continue;
                Enumeration<InetAddress> addresses = ni.getInetAddresses();
                while (addresses != null && addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    String text = address.getHostAddress();
                    if (text == null || !text.startsWith("100.")) continue;
                    String[] parts = text.split("\\.");
                    if (parts.length < 2) continue;
                    try {
                        int second = Integer.parseInt(parts[1]);
                        if (second >= 64 && second <= 127) return text;
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private ApplianceState.ComponentState officialAppState() {
        if (!officialAppInstalled()) return ApplianceState.ComponentState.NOT_INSTALLED;
        String address = cgnatAddress();
        if (address != null) return ApplianceState.ComponentState.CONNECTED;
        if (vpnActive()) return ApplianceState.ComponentState.CONNECTED;
        return ApplianceState.ComponentState.NEEDS_ACTION;
    }

    private String officialAppDetail() {
        if (!officialAppInstalled()) return "install the official Tailscale app";
        String address = cgnatAddress();
        if (address != null) return address + " · tunnel up, identity unverified";
        if (vpnActive()) return "VPN active, identity unverified";
        return "sign in and connect the official app";
    }

    // ---------------------------------------------------------------- provisioning

    private void startProvisioning() {
        if (!TermuxRunner.isTermuxInstalled(this)) {
            toast("Install the official Termux app first.");
            startTermuxInstall();
            return;
        }
        if (!prefs().getBoolean(KEY_TERMUX_VERIFIED, false)) {
            appendLog("Termux is not authorised yet.");
            setComponent(ROW_TERMUX, ApplianceState.ComponentState.NEEDS_ACTION,
                    "Authorise Alpha7A to run commands in Termux");
            renderActions();
            return;
        }
        if (!sshConfig().hasDebianPassword()) {
            appendLog("No Debian password is configured, so provisioning was not started.");
            toast("Set the Debian password in the SSH configuration first.");
            return;
        }
        String payload = loadPayload();
        if (payload.startsWith("echo 'Alpha7A payload missing'")) {
            toast("The provisioning payload is missing from this build.");
            return;
        }
        prefs().edit().putLong(KEY_RUN_STARTED, System.currentTimeMillis()).apply();
        setPhase(ApplianceState.Phase.RUNNING);
        appendLog("Provisioning started in Termux.");
        TermuxRunner.run(this, "Alpha7A provisioning",
                "Alpha7A is provisioning the appliance: Termux packages, Tailscale, Debian and SSH.",
                buildPayload(payload, "provision"), result -> onProvisioningResult(result));
    }

    private void installTailscaleCli() {
        if (!prefs().getBoolean(KEY_TERMUX_VERIFIED, false)) {
            toast("Authorise Alpha7A to run commands in Termux first.");
            return;
        }
        appendLog("Installing the Tailscale client in Termux.");
        setPhase(ApplianceState.Phase.RUNNING);
        TermuxRunner.run(this, "Alpha7A Tailscale install",
                "Alpha7A is installing the official Tailscale client inside Termux.",
                buildPayload(loadPayload(), "tailscale-cli"), result -> {
                    if (result.ok()) {
                        appendLog("Tailscale client stage finished.");
                    } else {
                        appendLog("Tailscale client stage failed. " + result.summary());
                    }
                    detectComponents();
                });
    }

    private void onProvisioningResult(TermuxRunner.Result result) {
        if (result.rejected()) {
            setPhase(ApplianceState.Phase.PREREQUISITE_REQUIRED);
            setComponent(ROW_TERMUX, ApplianceState.ComponentState.NEEDS_ACTION,
                    "Termux refused the command: " + result.summary());
            appendLog("Termux refused the command. " + result.summary());
            renderActions();
            return;
        }
        if (!result.ok()) {
            setPhase(ApplianceState.Phase.FAILED);
            appendLog("Provisioning failed (exit " + result.exitCode + "). " + result.summary());
            if (result.truncated) appendLog("The output was truncated, so the tail is shown.");
            renderActions();
            detectComponents();
            return;
        }
        setPhase(ApplianceState.Phase.VERIFYING);
        appendLog("Provisioning finished. Verifying the services.");
        if (result.truncated) appendLog("The output was truncated, so the tail is shown.");
        detectComponents();
    }

    private String buildPayload(String payload, String stage) {
        StringBuilder sb = new StringBuilder();
        sb.append(sshConfig().toExports());
        String key = savedTailscaleKey();
        if (!key.isEmpty()) {
            sb.append("export TS_AUTHKEY='")
                    .append(ApplianceState.SshConfig.shellQuote(key)).append("'\n");
        }
        sb.append("export ALPHA7A_STAGE='").append(stage).append("'\n");
        sb.append(payload);
        return sb.toString();
    }

    // ---------------------------------------------------------------- configuration

    private ApplianceState.SshConfig sshConfig() {
        ApplianceState.SshConfig config = new ApplianceState.SshConfig();
        config.termuxPassword(prefs().getString(KEY_TERMUX_PASSWORD, ""));
        config.debianUser(prefs().getString(KEY_DEBIAN_USER, ApplianceState.SshConfig.DEFAULT_DEBIAN_USER));
        config.debianPassword(prefs().getString(KEY_DEBIAN_PASSWORD, ""));
        config.termuxPort(prefs().getInt(KEY_TERMUX_PORT, ApplianceState.SshConfig.DEFAULT_TERMUX_PORT));
        config.debianPort(prefs().getInt(KEY_DEBIAN_PORT, ApplianceState.SshConfig.DEFAULT_DEBIAN_PORT));
        return config;
    }

    private void saveSshConfig() {
        int termuxPort = parsePort(termuxPortInput, ApplianceState.SshConfig.DEFAULT_TERMUX_PORT);
        int debianPort = parsePort(debianPortInput, ApplianceState.SshConfig.DEFAULT_DEBIAN_PORT);
        if (termuxPort <= 0 || termuxPort > 65535 || debianPort <= 0 || debianPort > 65535) {
            toast("The SSH ports must be between 1 and 65535.");
            return;
        }
        String debianUser = debianUserInput.getText().toString().trim();
        if (debianUser.isEmpty()) debianUser = ApplianceState.SshConfig.DEFAULT_DEBIAN_USER;
        prefs().edit()
                .putString(KEY_TERMUX_PASSWORD, termuxPasswordInput.getText().toString())
                .putString(KEY_DEBIAN_USER, debianUser)
                .putString(KEY_DEBIAN_PASSWORD, debianPasswordInput.getText().toString())
                .putInt(KEY_TERMUX_PORT, termuxPort)
                .putInt(KEY_DEBIAN_PORT, debianPort)
                .apply();
        appendLog("SSH configuration saved.");
        toast("Configuration saved.");
    }

    private int parsePort(EditText field, int fallback) {
        try {
            return Integer.parseInt(field.getText().toString().trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private void saveTailscaleKey() {
        if (keyInput == null) return;
        String key = keyInput.getText().toString().trim();
        if (key.isEmpty()) {
            prefs().edit().remove(KEY_TS_AUTHKEY).apply();
            appendLog("Tailscale key cleared.");
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

    private String savedTailscaleKey() {
        return prefs().getString(KEY_TS_AUTHKEY, "");
    }

    private ApplianceState.TailscaleMode currentMode() {
        String name = prefs().getString(KEY_TS_MODE, ApplianceState.TailscaleMode.OFFICIAL_APP.name());
        return ApplianceState.TailscaleMode.OFFICIAL_APP.name().equals(name)
                ? ApplianceState.TailscaleMode.OFFICIAL_APP
                : ApplianceState.TailscaleMode.TERMUX_CLI;
    }

    // ---------------------------------------------------------------- phase and log

    private ApplianceState.Phase currentPhase() {
        String name = prefs().getString(KEY_PHASE, "");
        if (name.length() > 0) {
            for (ApplianceState.Phase phase : ApplianceState.Phase.values()) {
                if (phase.name().equals(name)) return phase;
            }
        }
        if (!TermuxRunner.isTermuxInstalled(this)) return ApplianceState.Phase.PREREQUISITE_REQUIRED;
        if (!prefs().getBoolean(KEY_TERMUX_VERIFIED, false)) return ApplianceState.Phase.PREREQUISITE_REQUIRED;
        return ApplianceState.Phase.READY;
    }

    private void setPhase(ApplianceState.Phase phase) {
        prefs().edit().putString(KEY_PHASE, phase.name()).apply();
        if (phaseLabel != null) {
            phaseLabel.setText(phase.label);
            phaseLabel.setTextColor(phaseColor());
        }
        if (stageLabel != null && phase != ApplianceState.Phase.RUNNING) {
            stageLabel.setText(currentStageDescription());
        }
    }

    private String currentStageDescription() {
        ApplianceState.Phase phase = currentPhase();
        switch (phase) {
            case PREREQUISITE_REQUIRED:
                return TermuxRunner.isTermuxInstalled(this)
                        ? "Alpha7A still needs permission to run commands in Termux"
                        : "Install the official Termux app to continue";
            case RUNNING:
                return "Running the setup inside Termux";
            case VERIFYING:
                return "Checking that the services are really running";
            case FAILED:
                return "The last run failed. Open the activity log for the reason.";
            case COMPLETE:
                return "The appliance is provisioned and verified";
            default:
                return "Ready. Start provisioning when you are happy with the configuration.";
        }
    }

    private String elapsedText() {
        long started = prefs().getLong(KEY_RUN_STARTED, 0L);
        if (started <= 0L) return "Not started yet";
        long seconds = (System.currentTimeMillis() - started) / 1000L;
        if (seconds < 60L) return "Elapsed " + seconds + " s";
        long minutes = seconds / 60L;
        return "Elapsed " + minutes + " min " + (seconds % 60L) + " s";
    }

    private String technicalDetailText() {
        String user = prefs().getString(KEY_TERMUX_USER, "");
        StringBuilder sb = new StringBuilder();
        sb.append("Termux: ").append(TermuxRunner.isTermuxInstalled(this)
                ? TermuxRunner.termuxVersion(this) : "not installed").append("\n");
        sb.append("Termux account: ").append(user.isEmpty() ? "unknown" : user).append("\n");
        sb.append("Commands allowed: ").append(prefs().getBoolean(KEY_TERMUX_VERIFIED, false)).append("\n");
        sb.append("Tailscale mode: ").append(currentMode().label).append("\n");
        sb.append("Official app installed: ").append(officialAppInstalled()).append("\n");
        sb.append("VPN active: ").append(vpnActive()).append("\n");
        sb.append("Tunnelled address: ").append(cgnatAddress() == null ? "none" : cgnatAddress()).append("\n");
        sb.append("Termux SSH port: ").append(sshConfig().termuxPort()).append("\n");
        sb.append("Debian SSH port: ").append(sshConfig().debianPort()).append("\n");
        sb.append("Termux APK sha256: ").append(TERMUX_APK_SHA256).append("\n");
        return sb.toString();
    }

    private void refreshElapsed() {
        if (elapsedLabel != null) elapsedLabel.setText(elapsedText());
        if (technicalDetail != null && technicalDetail.getVisibility() == View.VISIBLE) {
            technicalDetail.setText(technicalDetailText());
        }
    }

    private void appendLog(String line) {
        if (line == null || line.isEmpty()) return;
        String redacted = redact(line);
        String current = savedLog();
        if (current.endsWith(redacted)) {
            // The same message again: count it instead of repeating it.
            int count = 1;
            String[] lines = current.split("\n");
            for (int i = lines.length - 2; i >= 0; i--) {
                if (lines[i].endsWith(redacted)) count++;
                else break;
            }
            current = current.substring(0, current.length() - redacted.length()) + redacted + " (x" + count + ")";
        } else {
            current = current.isEmpty() ? redacted : current + "\n" + redacted;
        }
        String[] lines = current.split("\n");
        if (lines.length > 150) {
            StringBuilder trimmed = new StringBuilder();
            for (int i = lines.length - 150; i < lines.length; i++) {
                if (trimmed.length() > 0) trimmed.append("\n");
                trimmed.append(lines[i]);
            }
            current = trimmed.toString();
        }
        prefs().edit().putString(KEY_LOG, current).apply();
        if (logView != null) logView.setText(current);
    }

    /** Never write a key or a password into the log. */
    private String redact(String line) {
        String out = line;
        String key = savedTailscaleKey();
        if (!key.isEmpty()) out = out.replace(key, "<redacted>");
        String password = sshConfig().debianPassword();
        if (!password.isEmpty()) out = out.replace(password, "<redacted>");
        String termuxPassword = sshConfig().termuxPassword();
        if (!termuxPassword.isEmpty()) out = out.replace(termuxPassword, "<redacted>");
        return out;
    }

    private String savedLog() {
        return prefs().getString(KEY_LOG, "");
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
        DownloadManager dm = downloadManager();
        long id = prefs().getLong(KEY_DOWNLOAD_ID, -1L);
        if (dm == null || id == -1L) {
            restoreStageLabel();
            return;
        }
        DownloadState state = queryDownload(dm, id);
        if (state == null) {
            clearDownload();
            restoreStageLabel();
            return;
        }
        switch (state.status) {
            case DownloadManager.STATUS_PENDING:
                showDownload("Waiting for the network before the Termux download starts...", 0, true);
                scheduleTick();
                break;
            case DownloadManager.STATUS_RUNNING: {
                int percent = state.total > 0 ? (int) Math.min(100, (state.done * 100) / state.total) : 0;
                showDownload("Downloading Termux… " + percent + "% ("
                        + megabytes(state.done) + " / " + megabytes(state.total) + ")", percent, state.total <= 0);
                scheduleTick();
                break;
            }
            case DownloadManager.STATUS_PAUSED:
                showDownload("Download paused. It continues automatically when the connection allows.", 0, true);
                scheduleTick();
                break;
            case DownloadManager.STATUS_SUCCESSFUL:
                showDownload("Termux downloaded (" + megabytes(state.done) + "). Approve the install in the next screen.", 100, false);
                if (!prefs().getBoolean(KEY_INSTALL_PROMPTED, false)) {
                    prefs().edit().putBoolean(KEY_INSTALL_PROMPTED, true).apply();
                    promptInstall(dm, id);
                }
                break;
            default:
                showDownload("The Termux download failed. Tap Install to try again.", 0, true);
                clearDownload();
                break;
        }
    }

    private void showDownload(String message, int percent, boolean indeterminate) {
        if (stageLabel != null) stageLabel.setText(message);
        if (progressBar != null) {
            progressBar.setIndeterminate(indeterminate);
            if (!indeterminate) progressBar.setProgress(percent);
        }
    }

    private void restoreStageLabel() {
        if (stageLabel != null) stageLabel.setText(currentStageDescription());
        if (progressBar != null) {
            progressBar.setIndeterminate(true);
            progressBar.setProgress(0);
        }
    }

    private void scheduleTick() {
        handler.postDelayed(ticker, 700);
    }

    private String megabytes(long bytes) {
        return String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0);
    }

    private void promptInstall(DownloadManager dm, long id) {
        if (!canRequestPackageInstalls()) {
            appendLog("Android has not allowed Alpha7A to install packages yet.");
            toast("Allow “install unknown apps” for Alpha7A, then try again.");
            try {
                Intent i = new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName()));
                startActivity(i);
            } catch (Exception ignored) {
            }
            return;
        }
        Uri apk = null;
        try { apk = dm.getUriForDownloadedFile(id); } catch (Exception ignored) {}
        if (apk == null) {
            toast("The downloaded Termux APK is no longer available. Install it from the official release page.");
            openTermuxReleasePage();
            return;
        }
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

    private boolean canRequestPackageInstalls() {
        try {
            return getPackageManager().canRequestPackageInstalls();
        } catch (Exception e) {
            return true;
        }
    }

    private void openTermuxReleasePage() {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(TERMUX_RELEASE_PAGE))); }
        catch (Exception ignored) {}
    }

    // ---------------------------------------------------------------- helpers

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

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private DownloadManager downloadManager() {
        return (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
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

    private static final class DownloadState {
        int status = -1;
        long done;
        long total;
        int reason;
    }
}
