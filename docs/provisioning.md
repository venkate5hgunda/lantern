# Provision and pair

Create a private household CA, issue a certificate per phone and pair viewers.

[← Back to the README](../README.md)

Use a private, offline household CA and a separate identity for each phone.
Commands prompt for passwords; do not put household passwords in command
arguments, source files, tests or browser URLs. The output paths must not
already exist. The ignored `provisioning/` directory is a default local
location, **not** a location to publish or commit.

```sh
python3 scripts/provision.py init-ca --out provisioning/household-ca
python3 scripts/provision.py create-peer \
  --ca provisioning/household-ca --out provisioning/front-door \
  --name "Front door" \
  --san front-door.nord --san 192.168.1.20
```

Replace the names and addresses with the phone's actual LAN/Meshnet DNS names
and IPs. Every address used in a browser must appear in that phone's
certificate SANs. Reserve LAN addresses or reprovision when they change.

1. Keep the encrypted household CA signing key offline. Install only its
   public `ca-cert.pem` as a trusted CA on each viewer. On iOS, enable full
   trust for the installed CA; TV/browser firmware may not support this
   configuration and must be qualified. Do not bypass certificate warnings.
2. Sideload the APK. Transfer only this phone's `device.p12` privately to the
   phone, import it through Lantern's file picker, then remove the transfer
   copy. The application imports the private key into Android Keystore.
3. Follow the five-step phone setup: name the camera, import its identity,
   allow access, start the camera, then connect and verify a viewer.
   Optional external browser origins are in **Camera settings**, not required
   for the camera's own hosted viewer. Importing a replacement identity
   restarts HTTPS and terminates existing sessions.
4. Open the camera at `https://<certificate-name-or-ip>:8443/`. Approve a
   different viewer's exact origin on every camera it will access. Paths are
   not part of origins.
5. Generate a fresh code on the phone, enter that camera's HTTPS address and
   the six-digit code in the viewer, then Watch. Use Connect with audio,
   Listen and Talk explicitly when needed. Remove camera forgets the tab's
   credentials; **revoke in Camera settings** to terminate its authorization.

Pairing codes last 120 seconds, allow five total failures and are consumed on
success. Grants expire within 30 days. Credentials live in session storage,
not persistent local storage. Closing a tab is not revocation.
There are at most three sessions per camera, one with audio. Unreachable or
abandoned sessions expire after 30 seconds.
