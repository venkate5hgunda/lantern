import { useCallback, useEffect, useRef, useState, useSyncExternalStore, type FormEvent } from "react";
import { PeerApi } from "./api";
import { CameraConnection } from "./connection";
import { describeError, normalizeOrigin, type CameraEvent, type SavedPeer } from "./protocol";
import { CameraExtras } from "./CameraExtras";
import { loadFeatures } from "./features";
import { loadPeers, savePeers } from "./storage";

function CameraIcon() {
  return (
    <svg width="24" height="24" viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <rect x="3" y="6" width="13" height="12" rx="3" stroke="currentColor" strokeWidth="1.6" />
      <path d="m16 10 5-3v10l-5-3" stroke="currentColor" strokeWidth="1.6" strokeLinejoin="round" />
      <circle cx="7" cy="10" r="1" fill="currentColor" />
    </svg>
  );
}

function PairForm({ initialOrigin, onPaired, onCancel }: {
  initialOrigin: string; onPaired(peer: SavedPeer): void; onCancel(): void;
}) {
  const [origin, setOrigin] = useState(initialOrigin);
  const [code, setCode] = useState("");
  const [viewerName, setViewerName] = useState("Browser viewer");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const operation = useRef<AbortController | null>(null);
  useEffect(() => () => operation.current?.abort(), []);

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (busy) return;
    setBusy(true); setError(null);
    const abort = new AbortController();
    operation.current = abort;
    try {
      const api = new PeerApi(normalizeOrigin(origin));
      const info = await api.info(abort.signal);
      const grant = await api.pair(code, viewerName, abort.signal);
      if (grant.deviceId !== info.deviceId) throw new Error("Device identity changed during pairing. Try again.");
      if (abort.signal.aborted) return;
      setCode("");
      onPaired({
        origin: api.origin, deviceId: info.deviceId, name: info.name,
        token: grant.token, expiresAt: grant.expiresAt,
      });
    } catch (cause) {
      if (!abort.signal.aborted) setError(describeError(cause));
    } finally {
      if (!abort.signal.aborted) setBusy(false);
    }
  }

  return (
    <section className="pair-panel" aria-labelledby="pair-title">
      <div className="section-heading">
        <div><p className="eyebrow">PHYSICAL PAIRING</p><h2 id="pair-title">Add a trusted camera</h2></div>
        <button className="quiet" onClick={() => { operation.current?.abort(); onCancel(); }}>Cancel</button>
      </div>
      <p className="muted">Trust the camera certificate first. Generate a fresh code on the phone, and approve this viewer's origin there.</p>
      <form onSubmit={(event) => { void submit(event); }}>
        <label htmlFor="camera-origin">Camera HTTPS address</label>
        <input id="camera-origin" type="url" required autoFocus value={origin}
          placeholder="https://front-door.nord:8443" autoComplete="off" spellCheck={false}
          onChange={(event) => setOrigin(event.target.value)} disabled={busy} />
        <div className="form-columns">
          <div>
            <label htmlFor="pair-code">Six-digit pairing code</label>
            <input id="pair-code" type="text" inputMode="numeric" pattern="[0-9]{6}"
              minLength={6} maxLength={6} required autoComplete="off" value={code}
              onChange={(event) => setCode(event.target.value)} disabled={busy} />
          </div>
          <div>
            <label htmlFor="viewer-name">Name this viewer</label>
            <input id="viewer-name" required maxLength={80} autoComplete="off" value={viewerName}
              onChange={(event) => setViewerName(event.target.value)} disabled={busy} />
          </div>
        </div>
        {error && <p role="alert" className="error">{error}</p>}
        <div className="form-footer">
          <p className="small muted">Credentials stay in this browser tab's session. Configure LAN/Meshnet-only network access separately.</p>
          <button type="submit" className="primary" disabled={busy}>{busy ? "Pairing..." : "Pair camera"}</button>
        </div>
      </form>
    </section>
  );
}

function EventHistory({ events, connection, loading }: {
  events: CameraEvent[]; connection: CameraConnection; loading: boolean;
}) {
  const [preview, setPreview] = useState<{ event: CameraEvent; url: string } | null>(null);
  const [loadingId, setLoadingId] = useState<string | null>(null);
  const [previewError, setPreviewError] = useState<string | null>(null);
  const operation = useRef<AbortController | null>(null);
  const previewUrl = useRef<string | null>(null);
  useEffect(() => () => {
    operation.current?.abort();
    if (previewUrl.current) URL.revokeObjectURL(previewUrl.current);
  }, []);
  const clearPreview = () => {
    operation.current?.abort();
    operation.current = null;
    if (previewUrl.current) URL.revokeObjectURL(previewUrl.current);
    previewUrl.current = null;
    setPreview(null); setLoadingId(null);
  };

  async function view(event: CameraEvent) {
    clearPreview();
    setPreviewError(null);
    const abort = new AbortController();
    operation.current = abort;
    setLoadingId(event.id);
    try {
      const blob = await connection.snapshot(event.id, abort.signal);
      if (abort.signal.aborted) return;
      const url = URL.createObjectURL(blob);
      previewUrl.current = url;
      setPreview({ event, url });
    } catch (error) {
      if (!abort.signal.aborted) setPreviewError(describeError(error));
    } finally {
      if (operation.current === abort) setLoadingId(null);
    }
  }

  return (
    <details className="history">
      <summary>Event history <span className="muted">{events.length} recent</span></summary>
      <div className="history-body">
        {previewError && <p className="error" role="alert">{previewError}</p>}
        <button className="quiet" disabled={loading} onClick={() => { void connection.refresh(); }}>
          {loading ? "Refreshing..." : "Refresh events"}
        </button>
        {loading && <p role="status" className="small muted">Reading camera history...</p>}
        {!loading && events.length === 0 && <p className="small muted">No saved events. Take a snapshot while this camera is armed.</p>}
        {events.length > 0 && (
          <ol className="event-list">
            {events.map((event) => (
              <li key={event.id}>
                <div>
                  <strong>{event.trigger === "person" ? "Person detected" : event.trigger === "scheduled" ? "Automatic snapshot" : "Manual snapshot"}</strong>
                  <time dateTime={event.createdAt}>{new Date(event.createdAt).toLocaleString()}</time>
                  {event.confidence !== null && <span className="small muted">{Math.round(event.confidence * 100)}% model confidence</span>}
                </div>
                <button className="quiet" onClick={() => { void view(event); }} disabled={loadingId === event.id}>
                  {loadingId === event.id ? "Loading..." : "View snapshot"}
                </button>
              </li>
            ))}
          </ol>
        )}
        {preview && (
          <figure className="snapshot-preview">
            <img src={preview.url} alt={`${preview.event.trigger === "person" ? "Person detection" : "Manual"} snapshot captured ${new Date(preview.event.createdAt).toLocaleString()}`} />
            <figcaption>
              <a href={preview.url} download={`lantern-${preview.event.id}.jpg`}>Download snapshot</a>
              <button className="quiet" onClick={clearPreview}>Close snapshot</button>
            </figcaption>
          </figure>
        )}
      </div>
    </details>
  );
}

function CameraCard({ peer, onRemove, onExpired, onWarning }: {
  peer: SavedPeer; onRemove(): void; onExpired(): void; onWarning(message: string): void;
}) {
  const [connection] = useState(() => new CameraConnection(
    new PeerApi(peer.origin, peer.token), peer.deviceId, onExpired, onWarning,
  ));
  const state = useSyncExternalStore(connection.subscribe, connection.getSnapshot);
  const video = useRef<HTMLVideoElement | null>(null);
  const [features] = useState(loadFeatures);
  const [sound, setSound] = useState(false);
  const [playbackBlocked, setPlaybackBlocked] = useState(false);
  const [confirmRemove, setConfirmRemove] = useState(false);
  useEffect(() => {
    void connection.refresh();
    return () => { void connection.stop(); };
  }, [connection]);
  useEffect(() => {
    const element = video.current;
    if (!element) return;
    element.srcObject = state.stream;
    if (state.stream) {
      void element.play().then(() => setPlaybackBlocked(false), () => setPlaybackBlocked(true));
    }
    return () => { element.srcObject = null; };
  }, [state.stream]);
  useEffect(() => {
    if (!state.audio) setSound(false);
  }, [state.audio]);

  const connecting = state.connection === "connecting";
  const connected = state.connection === "connected";
  const name = state.info?.name ?? peer.name;

  return (
    <article className="camera-card" aria-label={`${name} camera`}>
      <div className="camera-heading">
        <div><h2>{name}</h2><p className="endpoint">{peer.origin}</p></div>
        <span className={`badge ${connected ? "live" : ""}`}>
          {connected ? "LIVE" : connecting ? "CONNECTING" : state.info?.armed ? "ARMED" : state.info ? "DISARMED" : "UNCONFIRMED"}
        </span>
      </div>
      <div className="video-container">
        <video ref={video} autoPlay playsInline muted={!sound} aria-label={`${name} live stream`} />
        {!state.stream && (
          <div className="video-empty">
            <CameraIcon />
            <strong>{connecting ? "Connecting directly to camera..." : "Live video stays on demand"}</strong>
            <span>{connecting ? "Negotiating an authenticated media session" : "Camera capture stays armed without an open viewer"}</span>
          </div>
        )}
        {playbackBlocked && state.stream && (
          <button className="play-overlay primary" onClick={() => {
            void video.current?.play().then(() => setPlaybackBlocked(false), () => setPlaybackBlocked(true));
          }}>Tap to play live video</button>
        )}
      </div>
      <div className="camera-controls">
        {!connected && !connecting && <button className="primary" onClick={() => { void connection.connect(false); }}>Watch</button>}
        {(connected || connecting) && <button onClick={() => { void connection.disconnect(); }}>Disconnect</button>}
        <button disabled={connecting || state.info?.capabilities.audio === false}
          onClick={() => { void connection.connect(true); }}>
          {state.audio ? "Restart audio session" : "Connect with audio"}
        </button>
        <button disabled={!connected || !state.audio} aria-pressed={sound}
          onClick={() => {
            const nextSound = !sound;
            setSound(nextSound);
            if (video.current) video.current.muted = !nextSound;
            void video.current?.play().then(() => setPlaybackBlocked(false), () => setPlaybackBlocked(true));
          }}>{sound ? "Mute camera" : "Listen"}</button>
        <button disabled={!connected || !state.audio || state.microphonePending}
          aria-pressed={state.microphone} className={state.microphone ? "talking" : ""}
          onClick={() => { void connection.setMicrophone(!state.microphone); }}>
          {state.microphonePending ? "Requesting microphone..." : state.microphone ? "Stop talking" : "Talk"}
        </button>
        <button disabled={state.snapshotting || !state.info?.armed || state.info.capabilities.snapshots === false}
          onClick={() => { void connection.capture(); }}>{state.snapshotting ? "Saving..." : "Take snapshot"}</button>
      </div>
      {state.microphone && <p className="mic-notice" role="status">Your microphone is transmitting to this camera.</p>}
      {state.error && (
        <div className="error-banner" role="alert">
          <p>{state.error}</p><button className="quiet" onClick={() => connection.clearError()}>Dismiss</button>
        </div>
      )}
      {features.transportDetails && <p className="mic-notice">Live transport: standard WebRTC (DTLS-SRTP). Saved images: JPEG. {state.info?.capabilities.rollingSnapshots ? "Rolling snapshots supported." : "Manual snapshots only."}</p>}
      <CameraExtras peer={peer} info={state.info} connection={connection} alerts={features.doorbellAlerts} onExpired={onExpired} />
      <EventHistory connection={connection} events={state.events} loading={state.loadingEvents} />
      <div className="camera-footer">
        <span className="small muted">{state.info?.capabilities.personDetection ? "On-device person detection" : "Local snapshot storage"}</span>
        {!confirmRemove
          ? <button className="quiet" onClick={() => setConfirmRemove(true)}>Remove camera</button>
          : <div className="remove-confirm">
            <span className="small">Forget this viewer's credentials?</span>
            <button className="danger" onClick={() => { void connection.stop().then(onRemove); }}>Remove</button>
            <button className="quiet" onClick={() => setConfirmRemove(false)}>Keep</button>
          </div>}
      </div>
    </article>
  );
}

export default function App() {
  const [initial] = useState(loadPeers);
  const [peers, setPeers] = useState(initial.peers);
  const [warning, setWarning] = useState(initial.warning);
  const [pairOrigin, setPairOrigin] = useState<string | null>(null);
  const [showSetup, setShowSetup] = useState(false);
  const warn = useCallback((message: string) => setWarning(message), []);
  const persist = useCallback((update: (previous: SavedPeer[]) => SavedPeer[]) => {
    setPeers((previous) => {
      const next = update(previous);
      const problem = savePeers(next);
      if (problem) queueMicrotask(() => setWarning(problem));
      return next;
    });
  }, []);

  function paired(peer: SavedPeer) {
    const existing = peers.find((item) => item.origin === peer.origin);
    if (existing && existing.deviceId !== peer.deviceId) {
      setWarning("This endpoint has a different camera identity. Remove its old entry before pairing the replacement.");
      return;
    }
    if (!existing && peers.length >= 16) {
      setWarning("This viewer supports at most 16 paired cameras.");
      return;
    }
    persist((previous) => [...previous.filter((item) => item.origin !== peer.origin), peer]);
    setPairOrigin(null);
  }

  return (
    <div className="app-shell">
      <header className="topbar">
        <a className="brand" href="./" aria-label="Lantern home"><span className="brand-mark"><CameraIcon /></span><span>lantern</span></a>
        <span className="privacy-label">PRIVATE BY DESIGN <span aria-hidden="true">/</span> NO CAMERA HUB</span>
        <button className="primary" onClick={() => setPairOrigin("")}>Add camera</button>
      </header>
      <main>
        <section className="intro">
          <div><p className="eyebrow">YOUR CAMERAS. YOUR NETWORK.</p><h1>A window into home.</h1>
            <p className="muted">Independent cameras, direct viewing. LAN/Meshnet-only access requires network configuration and route verification.</p></div>
          <div className="fleet-count"><strong>{peers.length}</strong><span>paired {peers.length === 1 ? "camera" : "cameras"}</span></div>
        </section>
        {warning && <div className="error-banner" role="alert"><p>{warning}</p><button className="quiet" onClick={() => setWarning(null)}>Dismiss</button></div>}
        {pairOrigin !== null && <PairForm key={pairOrigin} initialOrigin={pairOrigin} onPaired={paired} onCancel={() => setPairOrigin(null)} />}
        {peers.length === 0 && pairOrigin === null && (
          <section className="welcome" aria-labelledby="welcome-title">
            <div className="welcome-visual"><CameraIcon /></div>
            <p className="eyebrow">NO SHARED SERVER. PAIRED VIEWERS.</p>
            <h2 id="welcome-title">Start with one trusted camera.</h2>
            <p className="muted">Install Lantern on an Android phone, provision its HTTPS identity, and pair this browser using the code on its screen.</p>
            <button className="primary" onClick={() => setPairOrigin("")}>Pair your first camera</button>
          </section>
        )}
        <div className="camera-grid">
          {peers.map((peer) => peer.token ? (
            <CameraCard key={`${peer.origin}:${peer.token}`} peer={peer} onWarning={warn}
              onRemove={() => persist((previous) => previous.filter((item) => item.origin !== peer.origin))}
              onExpired={() => {
                persist((previous) => previous.map((item) => item.origin === peer.origin ? { ...item, token: null, expiresAt: null } : item));
                setWarning(`${peer.name}: credentials expired or were revoked. Pair this browser again.`);
              }} />
          ) : (
            <article key={peer.origin} className="camera-card unpaired" aria-label={`${peer.name} camera`}>
              <h2>{peer.name}</h2><p className="endpoint">{peer.origin}</p>
              <p className="muted">This viewer's authorization has expired or been revoked.</p>
              <div className="camera-controls">
                <button className="primary" onClick={() => setPairOrigin(peer.origin)}>Pair again</button>
                <button className="quiet" onClick={() => persist((previous) => previous.filter((item) => item.origin !== peer.origin))}>Remove camera</button>
              </div>
            </article>
          ))}
        </div>
        <section className="setup-note">
          <button className="setup-toggle" aria-expanded={showSetup} aria-controls="setup-details" onClick={() => setShowSetup((value) => !value)}>
            Connection and privacy checklist <span aria-hidden="true">{showSetup ? "-" : "+"}</span>
          </button>
          {showSetup && (
            <div id="setup-details" className="setup-details">
              <p><strong>Trust HTTPS on every viewer.</strong> Import the household CA and enable TLS trust. Meshnet does not issue browser certificates.</p>
              <p><strong>Approve this origin on each camera:</strong> <code>{location.origin}</code>. GitHub Pages and other camera-hosted viewers are separate origins.</p>
              <p><strong>For remote viewing:</strong> enable Meshnet on both devices and grant the viewer remote-access permission. Keep camera-level pairing enabled.</p>
              <p><strong>Doorbell audio:</strong> start an audio session, tap Listen, then Talk. Only one audio session per camera is allowed. Closed browser tabs do not receive reliable doorbell alerts.</p>
              <p><strong>Initial milestone:</strong> local snapshots and direct live sessions. Peer replication, cloud backup and owner recognition are not implemented yet.</p>
              <p><strong>Remove versus revoke:</strong> removing a camera forgets this tab's credentials. Revoke the viewer grant on the phone to invalidate it.</p>
            </div>
          )}
        </section>
      </main>
      <footer className="page-footer"><span>Lantern / personal camera network</span><span>Media travels directly to your viewer. No analytics.</span></footer>
    </div>
  );
}
