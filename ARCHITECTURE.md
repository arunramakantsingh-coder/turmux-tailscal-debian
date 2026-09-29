# Android Development Appliance — Architecture

## 1. Purpose

Turn a no-root Android phone into a reproducible, remotely reachable development and networking appliance.

## 2. Layers

### Android
Physical host and Android lifecycle.

### Termux host layer
Responsible for:
- SSH on 8022
- Tailscale CLI/daemon
- userspace networking
- proot-distro
- service lifecycle
- wake-lock/startup helpers
- provisioning

### Debian workload layer
Debian is installed with proot-distro and provides:
- SSH on 2222
- Python/pip/venv
- Git
- build toolchain
- networking diagnostics
- proxychains4
- application and AI development workloads

## 3. Network model

One phone has one Tailscale identity/IP.

The same Tailscale address exposes:
- <tailscale-host>:8022 -> Termux
- <tailscale-host>:2222 -> Debian

Tailscale runs only in Termux. Debian uses the host networking environment.

## 4. Tailscale

- Termux userspace mode
- reusable/pre-authorized auth key supplied at runtime
- QR authentication fallback when key authentication fails
- initial hostname: <model>-<random4>
- hostname can later be renamed/fixed from the Tailscale admin console

## 5. Proxy

The userspace Tailscale endpoint is treated as a SOCKS5 proxy.

The architecture uses proxychains4 as an explicit per-process mechanism:
proxychains4 <command>

Proxychains configuration is generated locally and is never committed.

## 6. SSH

Termux:
- port 8022
- Android-assigned Termux username
- password supplied locally

Debian:
- port 2222
- user admin
- password supplied locally

## 7. Debian installation policy

- Install Debian only when missing.
- Run apt update.
- Install the required package set.
- Do not run apt upgrade.
- Re-running provisioning should be safe.

## 8. Provisioning

The GitHub repository is the source of truth.

The provisioning entry point is bootstrap/setup.sh.

Runtime secrets:
- TS_AUTHKEY
- TERMUX_PASSWORD
- DEBIAN_PASSWORD

The setup must never print or commit those values.

## 9. Future lifecycle

1. Bootstrap
2. Provision
3. Start services
4. Test on lab phone
5. Update/repair
6. Fleet provisioning
7. Optional Termux:Boot / Widget / Shizuku integration

## 10. Phase-1 non-goals

- Android APK self-installation
- root access
- GUI desktop
- automatic fleet management
- production secret management
- Cloudflare dependency
