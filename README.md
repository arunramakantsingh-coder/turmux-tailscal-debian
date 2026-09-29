# Termux + Tailscale + Debian Appliance

A reproducible, no-root Android appliance built from Termux, Tailscale userspace networking, and Debian via proot-distro.

## Architecture

Android -> Termux host layer -> Tailscale userspace networking -> Debian proot workload layer.

The phone exposes two SSH services over the same Tailscale identity:
- Termux SSH: TCP/8022
- Debian SSH: TCP/2222

Tailscale authentication and SSH passwords are supplied at runtime. No secrets are stored in this repository.

## Repository

- ARCHITECTURE.md — architecture and design decisions
- SPECIFICATION.md — functional requirements and acceptance criteria
- bootstrap/setup.sh — provisioning entry point
- bootstrap/config.example — safe configuration template
- tests/validate.sh — repository validation

## Security

This repository is public. Never commit Tailscale auth keys, passwords, private keys, tokens, or other device-specific secrets.

## Status

Phase 1: repository baseline and provisioning entry point. The lab phone is the integration-test target.
