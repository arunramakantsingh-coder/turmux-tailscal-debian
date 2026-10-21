package com.arunramakantsingh.alpha7a;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs a shell script inside the official Termux app and returns its output to this app.
 *
 * <p>Uses Termux's documented external command interface (termux-app wiki, "RUN_COMMAND Intent"):
 * <ul>
 *   <li>{@code com.termux.RUN_COMMAND} intent, explicitly targeted at
 *       {@code com.termux.app.RunCommandService}.</li>
 *   <li>{@code com.termux.RUN_COMMAND_BACKGROUND=true} so the command runs in the background
 *       instead of a visible terminal session, and so stdout and stderr are returned
 *       separately rather than as a merged terminal transcript.</li>
 *   <li>{@code com.termux.RUN_COMMAND_PENDING_INTENT} to receive the result. Termux delivers a
 *       bundle under the key {@code result} containing {@code stdout}, {@code stderr},
 *       {@code exitCode}, {@code err} and {@code errmsg}.</li>
 * </ul>
 *
 * <p>Termux requires {@code allow-external-apps=true} in {@code ~/.termux/termux.properties}
 * and the user granted {@code com.termux.permission.RUN_COMMAND} to this app. Both are checked
 * by Termux; a refusal is reported as a rejection, never as a successful run.
 */
public final class TermuxRunner {
    public static final String TERMUX_PACKAGE = "com.termux";
    public static final String TERMUX_RUN_COMMAND_ACTION = "com.termux.RUN_COMMAND";
    public static final String TERMUX_SERVICE = "com.termux.app.RunCommandService";
    public static final String TERMUX_BASH = "/data/data/com.termux/files/usr/bin/bash";
    public static final String TERMUX_HOME = "/data/data/com.termux/files/home";

    /** Action prefix of the per-request result broadcast. */
    static final String ACTION_RESULT = "com.arunramakantsingh.alpha7a.RUN_COMMAND_RESULT";

    /** TERMUX_SERVICE.EXTRA_PLUGIN_RESULT_BUNDLE - the bundle Termux attaches to the result. */
    static final String BUNDLE_KEY = "result";
    static final String KEY_STDOUT = "stdout";
    static final String KEY_STDERR = "stderr";
    static final String KEY_EXIT_CODE = "exitCode";
    static final String KEY_ERR = "err";
    static final String KEY_ERRMSG = "errmsg";
    static final String KEY_STDOUT_ORIGINAL_LENGTH = "stdout_original_length";
    static final String KEY_STDERR_ORIGINAL_LENGTH = "stderr_original_length";

    /** How long a single command may run before it is reported as failed. */
    public static final long DEFAULT_TIMEOUT_MS = 30L * 60L * 1000L;

    private static final Map<String, Callback> WAITING = new ConcurrentHashMap<>();
    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** Result of one command run inside Termux. */
    public static final class Result {
        public final ApplianceState.Outcome outcome;
        public final String stdout;
        public final String stderr;
        public final String errMsg;
        public final int exitCode;
        public final int errCode;
        public final boolean truncated;

        Result(ApplianceState.Outcome outcome, String stdout, String stderr, String errMsg,
               int exitCode, int errCode, boolean truncated) {
            this.outcome = outcome;
            this.stdout = stdout == null ? "" : stdout;
            this.stderr = stderr == null ? "" : stderr;
            this.errMsg = errMsg == null ? "" : errMsg;
            this.exitCode = exitCode;
            this.errCode = errCode;
            this.truncated = truncated;
        }

        public boolean ok() {
            return outcome == ApplianceState.Outcome.OK;
        }

        /** True when the command was refused because a prerequisite is missing. */
        public boolean rejected() {
            return outcome == ApplianceState.Outcome.REJECTED;
        }

        /** The most useful text for a human: errors take precedence over output. */
        public String summary() {
            if (!ok()) {
                if (!errMsg.isEmpty()) return errMsg;
                if (!stderr.isEmpty()) return tail(stderr);
                return "The command failed with exit code " + exitCode + ".";
            }
            return tail(stdout);
        }

        /** The last lines of a blob, for compact display. */
        public static String tail(String text) {
            if (text == null) return "";
            String t = text.trim();
            if (t.length() <= 4000) return t;
            return "..." + t.substring(t.length() - 4000);
        }
    }

    public interface Callback {
        void onResult(Result result);
    }

    private TermuxRunner() {
    }

    public static boolean isTermuxInstalled(Context context) {
        try {
            context.getPackageManager().getPackageInfo(TERMUX_PACKAGE, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** The Termux version string, or "unknown" when Termux is not installed. */
    public static String termuxVersion(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(TERMUX_PACKAGE, 0);
            return info.versionName == null ? "unknown" : info.versionName;
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * Runs {@code script} in Termux and delivers the result to {@code callback} on the main
     * thread. Returns false when the command could not be started at all.
     */
    public static boolean run(Activity activity, String label, String description, String script,
                              Callback callback) {
        return run(activity, label, description, script, callback, DEFAULT_TIMEOUT_MS);
    }

    public static boolean run(Activity activity, String label, String description, String script,
                              Callback callback, long timeoutMs) {
        if (!isTermuxInstalled(activity)) {
            deliverError(callback, "Termux is not installed.");
            return false;
        }
        final String id = "r" + SEQUENCE.incrementAndGet();
        WAITING.put(id, callback);

        Intent target = new Intent(activity, CommandResultReceiver.class);
        target.setAction(ACTION_RESULT + "." + id);
        target.setPackage(activity.getPackageName());

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        // Termux has to be able to attach the result bundle, so the intent must stay mutable.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags |= PendingIntent.FLAG_MUTABLE;
        }
        PendingIntent pending;
        try {
            pending = PendingIntent.getBroadcast(activity, id.hashCode(), target, flags);
        } catch (Exception e) {
            WAITING.remove(id);
            deliverError(callback, "Could not create the Termux callback: " + e.getMessage());
            return false;
        }

        Intent intent = new Intent(TERMUX_RUN_COMMAND_ACTION);
        intent.setComponent(new ComponentName(TERMUX_PACKAGE, TERMUX_SERVICE));
        intent.putExtra("com.termux.RUN_COMMAND_PATH", TERMUX_BASH);
        intent.putExtra("com.termux.RUN_COMMAND_WORKDIR", TERMUX_HOME);
        intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
        intent.putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", label);
        intent.putExtra("com.termux.RUN_COMMAND_COMMAND_DESCRIPTION", description);
        intent.putExtra("com.termux.RUN_COMMAND_STDIN", script);
        intent.putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pending);
        try {
            activity.startService(intent);
        } catch (Exception e) {
            WAITING.remove(id);
            deliverRejected(callback, "Termux refused the command: " + e.getMessage());
            return false;
        }
        // A command that never reports back must not leave the UI hanging forever.
        MAIN.postDelayed(new Runnable() {
            @Override public void run() {
                Callback waiting = WAITING.remove(id);
                if (waiting != null) {
                    waiting.onResult(new Result(ApplianceState.Outcome.FAILED, "", "",
                            "The command did not report back within the time limit.", -1, 0, false));
                }
            }
        }, timeoutMs);
        return true;
    }

    private static void deliverError(Callback callback, String message) {
        if (callback == null) return;
        MAIN.post(new Runnable() {
            @Override public void run() {
                callback.onResult(new Result(ApplianceState.Outcome.FAILED, "", message, message, -1, -1, false));
            }
        });
    }

    private static void deliverRejected(Callback callback, String message) {
        if (callback == null) return;
        MAIN.post(new Runnable() {
            @Override public void run() {
                callback.onResult(new Result(ApplianceState.Outcome.REJECTED, "", message, message, -1, -1, false));
            }
        });
    }

    /** Called by {@link CommandResultReceiver} once Termux delivered a result bundle. */
    static void deliver(String id, Result result) {
        final Callback callback = WAITING.remove(id);
        if (callback == null) return;
        MAIN.post(new Runnable() {
            @Override public void run() {
                callback.onResult(result);
            }
        });
    }

    /** Parses the bundle Termux attached to the result intent. */
    static Result parse(Bundle bundle) {
        if (bundle == null) {
            return new Result(ApplianceState.Outcome.FAILED, "", "",
                    "Termux did not return a result bundle.", -1, -1, false);
        }
        String stdout = bundle.getString(KEY_STDOUT, "");
        String stderr = bundle.getString(KEY_STDERR, "");
        int exitCode = bundle.getInt(KEY_EXIT_CODE, -1);
        int errCode = bundle.getInt(KEY_ERR, 0);
        String errMsg = bundle.getString(KEY_ERRMSG, "");
        boolean truncated = false;
        try {
            int stdoutLength = Integer.parseInt(bundle.getString(KEY_STDOUT_ORIGINAL_LENGTH, "0"));
            int stderrLength = Integer.parseInt(bundle.getString(KEY_STDERR_ORIGINAL_LENGTH, "0"));
            truncated = stdoutLength > stdout.length() || stderrLength > stderr.length();
        } catch (Exception ignored) {
            // The length keys are optional.
        }
        boolean authorisation = ApplianceState.isAuthorisationProblem(errMsg)
                || ApplianceState.isAuthorisationProblem(stderr);
        ApplianceState.Outcome outcome = ApplianceState.classify(exitCode, errCode, errMsg, authorisation);
        return new Result(outcome, stdout, stderr, errMsg, exitCode, errCode, truncated);
    }
}
