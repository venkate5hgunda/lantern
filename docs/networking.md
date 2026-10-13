# LAN, Meshnet and static hosting

Network exposure, private transports and hosting the viewer bundle.

[← Back to the README](../README.md)

Meshnet is an optional private transport, not application authorization.
Enable it on both the camera and a remote viewer, configure the appropriate
peer access permissions and use certificate-covered Meshnet addresses.
Ordinary NordVPN VPN access is not equivalent to Meshnet access. Current
physical-device Meshnet media routes are **not verified**.

The application has no public relay, but **does not enforce a host firewall
or prove that every selected media route is LAN/Meshnet-only**. Do not expose
port 8443, configure router forwarding/UPnP, or deploy on publicly reachable
interfaces and call that isolated. Configure OS/router IPv4 **and IPv6**
inbound policy, verify mobile-data behavior, disable public forwarding and
inspect actual selected ICE candidate pairs before treating a setup as
private. Deployment isolation is an outstanding acceptance gate.
The policy must cover UDP 8444 and negotiated WebRTC ports as well as HTTPS;
STUN address discovery is deliberately not application authentication.

GitHub Pages can host `web/dist/` as static files only, using Vite's relative
asset paths. No credentials, certificates, snapshots or streams belong in
the published files. An origin such as `https://<account>.github.io` must be
explicitly approved on every camera. Some browsers additionally require
local-network permission and trusted certificates for HTTPS-to-private-network
requests; firmware/policy can prevent them even with correct CORS.
Peer-hosting avoids making Pages availability a prerequisite.
