package com.arunramakantsingh.alpha7a;

/**
 * Pure state model for the Alpha7A appliance dashboard.
 *
 * <p>Deliberately free of Android imports so the state machine, the SSH configuration and the
 * result classification can be unit tested on a plain JVM.
 */
public final class ApplianceState {

    /** Overall provisioning phase. */
    public enum Phase {
        NOT_READY("Not ready"),
        PREREQUISITE_REQUIRED("Prerequisite required"),
        READY("Ready"),
        RUNNING("Running"),
        VERIFYING("Verifying"),
        COMPLETE("Complete"),
        FAILED("Failed");

        public final String label;

        Phase(String label) {
            this.label = label;
        }
    }

    /** Truthful per-component state. A component is only CONNECTED after a real check. */
    public enum ComponentState {
        NOT_INSTALLED("Not installed"),
        NEEDS_ACTION("Needs action"),
        INSTALLING("Installing"),
        CONFIGURING("Configuring"),
        CONNECTED("Connected"),
        DISCONNECTED("Disconnected"),
        FAILED("Failed"),
        UNKNOWN("Unknown");

        public final String label;

        ComponentState(String label) {
            this.label = label;
        }
    }

    /** How the appliance reaches the Tailscale network. The two modes are mutually exclusive. */
    public enum TailscaleMode {
        OFFICIAL_APP("Official Tailscale app"),
        TERMUX_CLI("Tailscale CLI in Termux");

        public final String label;

        TailscaleMode(String label) {
            this.label = label;
        }
    }

    /** Classification of one command result. */
    public enum Outcome {
        OK,
        FAILED,
        REJECTED;

        public boolean isSuccess() {
            return this == OK;
        }
    }

    /**
     * The SSH configuration, owned by the app.
     *
     * <p>Termux's own user name is assigned by Termux at install time and cannot be changed, so
     * only the Termux password and port are configurable. The Debian account is fully
     * configurable.
     */
    public static final class SshConfig {
        public static final int DEFAULT_TERMUX_PORT = 8022;
        public static final int DEFAULT_DEBIAN_PORT = 2222;
        public static final String DEFAULT_DEBIAN_USER = "admin";

        private String termuxPassword = "";
        private String debianUser = DEFAULT_DEBIAN_USER;
        private String debianPassword = "";
        private int termuxPort = DEFAULT_TERMUX_PORT;
        private int debianPort = DEFAULT_DEBIAN_PORT;

        public String termuxPassword() { return termuxPassword; }
        public void termuxPassword(String v) { termuxPassword = v == null ? "" : v.trim(); }

        public String debianUser() { return debianUser; }
        public void debianUser(String v) {
            String t = v == null ? "" : v.trim();
            debianUser = t.isEmpty() ? DEFAULT_DEBIAN_USER : t;
        }

        public String debianPassword() { return debianPassword; }
        public void debianPassword(String v) { debianPassword = v == null ? "" : v; }

        public int termuxPort() { return termuxPort; }
        public void termuxPort(int v) { termuxPort = v; }

        public int debianPort() { return debianPort; }
        public void debianPort(int v) { debianPort = v; }

        public boolean hasTermuxPassword() { return !termuxPassword.isEmpty(); }
        public boolean hasDebianPassword() { return !debianPassword.isEmpty(); }

        /** True when the ports differ, which invalidates any cached SSH verification. */
        public boolean portsChangedFrom(int termux, int debian) {
            return termuxPort != termux || debianPort != debian;
        }

        public boolean portsValid() {
            return termuxPort > 0 && termuxPort < 65536 && debianPort > 0 && debianPort < 65536;
        }

        /** The Debian account name must be usable by useradd and by sshd. */
        public boolean debianUserValid() {
            String u = debianUser;
            if (u.isEmpty() || u.length() > 32) return false;
            for (int i = 0; i < u.length(); i++) {
                char c = u.charAt(i);
                boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                        || (c >= '0' && c <= '9') || c == '_' || c == '-';
                if (!ok) return false;
            }
            return true;
        }

        /**
         * The shell exports handed to the provisioning script. Values are single quoted and
         * embedded quotes are escaped, so a password can never break out of the assignment.
         * Blank values are omitted so the script can tell "not supplied" from "empty".
         */
        public String toExports() {
            StringBuilder sb = new StringBuilder();
            export(sb, "TERMUX_SSH_PORT", String.valueOf(termuxPort));
            export(sb, "DEBIAN_SSH_PORT", String.valueOf(debianPort));
            export(sb, "DEBIAN_USER", debianUser);
            if (hasTermuxPassword()) export(sb, "TERMUX_PASSWORD", termuxPassword);
            if (hasDebianPassword()) export(sb, "DEBIAN_PASSWORD", debianPassword);
            return sb.toString();
        }

        private static void export(StringBuilder sb, String name, String value) {
            sb.append("export ").append(name).append("='").append(shellQuote(value)).append("'\n");
        }

        /** Escapes a value for use inside single quotes in a POSIX shell. */
        public static String shellQuote(String value) {
            if (value == null) return "";
            return value.replace("'", "'\\''");
        }
    }

    /** One stage of provisioning. */
    public static final class Stage {
        public final String name;
        public final String description;
        public final boolean percentKnown;

        public Stage(String name, String description, boolean percentKnown) {
            this.name = name;
            this.description = description;
            this.percentKnown = percentKnown;
        }
    }

    /** The ordered stages of a full provisioning run. */
    public static final Stage[] STAGES = {
            new Stage("Termux packages", "Updating package lists and installing OpenSSH, proot-distro and the build tools", false),
            new Stage("Termux SSH", "Generating host keys and starting sshd on the configured port", false),
            new Stage("Tailscale", "Installing, configuring and authenticating the Tailscale client", false),
            new Stage("Debian", "Installing the Debian userland and the development package set", false),
            new Stage("Debian account", "Creating the Debian user and applying the SSH configuration", false),
            new Stage("Debian SSH", "Starting sshd inside the Debian userland", false),
            new Stage("Proxy and status page", "Configuring the SOCKS5 proxy and the local operations page", false),
            new Stage("Verification", "Checking the SSH listeners and the Tailscale state", false),
    };

    /**
     * Decides whether a command was accepted by Termux, ran and failed, or was refused before
     * it ever started.
     */
    public static Outcome classify(int exitCode, int errCode, String errMsg, boolean rejectedByPlatform) {
        if (rejectedByPlatform) return Outcome.REJECTED;
        if (exitCode == 0 && errCode == 0) return Outcome.OK;
        if (exitCode == -1 && errCode == -1) return Outcome.REJECTED;
        return Outcome.FAILED;
    }

    /**
     * Recognises the Termux refusal that means "external apps are not allowed yet" or "the
     * permission is not granted", so the app can ask for the prerequisite instead of retrying.
     */
    public static boolean isAuthorisationProblem(String message) {
        if (message == null) return false;
        String m = message.toLowerCase(java.util.Locale.US);
        return m.contains("allow-external-apps")
                || m.contains("not allowed to start service")
                || m.contains("without permission com.termux.permission.run_command")
                || m.contains("permission_run_command")
                || m.contains("securityexception");
    }

    /** The two Tailscale modes must not run at the same time on one phone. */
    public static boolean modesConflict(TailscaleMode selected, boolean officialAppInstalled, boolean cliInstalled) {
        if (selected == TailscaleMode.OFFICIAL_APP) {
            return officialAppInstalled && cliInstalled;
        }
        return officialAppInstalled && cliInstalled;
    }
}
