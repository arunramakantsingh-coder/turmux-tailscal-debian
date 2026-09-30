# Model 1 — Fresh Phone Provisioning

1. Start with a fresh Android phone.
2. Open this GitHub Pages site.
3. Install the official Termux application.
4. Open Termux.
5. Run the displayed provisioning command.
6. The repository bootstrap provisions the complete appliance.

## Android boundary

A normal browser page cannot silently invoke arbitrary Termux shell commands. No ADB, companion APK, MDM, or privileged Android integration is required for Model 1.

## Security

Runtime secrets are supplied during provisioning. They are not stored in the repository.

## Version

Current web/provisioning baseline: 0.2.0-alpha.1
