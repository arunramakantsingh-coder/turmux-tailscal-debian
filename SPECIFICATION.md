# Provisioning Specification

## Functional requirements

FR-01: Verify/install required Termux packages.

FR-02: Install/verify the Termux Tailscale CLI implementation and start its userspace daemon.

FR-03: Generate an initial hostname from Android model plus four random alphanumeric characters.

FR-04: Prefer TS_AUTHKEY. If authentication fails, expose the login URL and QR fallback.

FR-05: Install Debian with proot-distro only when missing.

FR-06: Install development and networking packages without apt upgrade.

FR-07: Configure Termux SSH on 8022 and Debian SSH on 2222. Debian account is admin.

FR-08: Generate a local proxychains4 configuration for the Tailscale userspace SOCKS5 endpoint.

FR-09: Never store secrets in tracked files.

FR-10: Re-running provisioning must verify/update state instead of blindly rebuilding the platform.

## Acceptance criteria

A lab phone is provisioned when:
1. Termux SSH listens on 8022.
2. Debian exists.
3. Debian SSH listens on 2222.
4. admin exists in Debian.
5. Tailscale reports a usable local state.
6. The generated hostname is visible locally.
7. Proxychains configuration exists locally.
8. The setup summary does not print secret values.

## Security acceptance

The repository must contain no auth key, password, private SSH key, or API token.
