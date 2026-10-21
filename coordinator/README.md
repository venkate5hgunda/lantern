# Managed setup and signaling coordinator

This is a tested backend foundation for the approved Sites setup mode, not the current Android transport. It is not deployed or advertised as a working few-click camera flow yet. The native outbound client, guided enrollment, viewer transport adapter, deployment migrations and physical-network gate must ship together before enabling managed mode.

Target journey: install app → grant permissions → enter/scan the phone's short-lived code in the signed-in Site → camera appears. No per-phone TLS certificate, viewer-origin list or inbound HTTPS address. The local/direct mode remains supported.

The standard Worker export in `worker.mjs` uses the Sites `DB` D1 binding. `schema.sql` is the initial schema specification; integrate it into the Sites generated migration journal before deploying. Do not create tables during requests. The handler assumes a Sites dispatch boundary that strips user-supplied identity headers and supplies authenticated `oai-authenticated-user-id`. It must not be exposed directly on arbitrary hosting.

Browser owner routes need ChatGPT sign-in. Device registration and device-token polling need service-accessible routes; a private Sites audience alone does not permit phone background HTTP. Before native integration, qualify a supported public audience with owner routes enforcing the trusted identity and device routes enforcing per-device capability tokens. Do not install a Site-wide bypass credential on phones or place it in browser source.

Registration issues a random device token and a five-minute claim code. Only hashes are stored. A claimed token polls bounded signaling commands and posts immutable results. Owner device lists and jobs are scoped by authenticated identity. Registration, claim attempts, owner requests and device polling are rate limited; queues are bounded and expired metadata is cleaned in bounded batches. Signaling carries offers, ICE and leases only; it is not an arbitrary HTTP proxy and does not upload images or recordings. TLS protects signaling; camera/browser DTLS-SRTP protects live media.

Local tests exercise actual SQL with Node 24's built-in SQLite via a small D1 adapter:

```sh
node coordinator/worker.test.mjs
```

No dependency installation is required. Tests cover isolation, one-time claim, credentials, signaling roundtrip, queue limits, invalid commands and request limits. They do not prove Sites dispatch authentication, hosted D1 behavior, NAT traversal or Android background operation. Those are separate integration gates.
