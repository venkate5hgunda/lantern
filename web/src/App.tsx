import {
  useCallback, useEffect, useRef, useState, useSyncExternalStore,
  type ButtonHTMLAttributes, type FormEvent, type ReactNode,
} from "react";
import { PeerApi } from "./api";
import { CameraConnection } from "./connection";
import { describeError, normalizeOrigin, type CameraEvent, type SavedPeer } from "./protocol";
import { loadPeers, savePeers } from "./storage";

const iconPath = (name: string) => `./assets/icons/${name}.svg`;
const cameraIcon = iconPath("camera");
const caretRightIcon = iconPath("chevron-right");
const cloudOffIcon = iconPath("cloud-off");
const deviceMobileIcon = iconPath("device-mobile");
const lampIcon = iconPath("lamp");
const microphoneIcon = iconPath("microphone");
const phoneOffIcon = iconPath("phone-off");
const playIcon = iconPath("play");
const plusIcon = iconPath("plus");
const shieldCheckIcon = iconPath("shield-check");
const videoIcon = iconPath("video");
const volumeIcon = iconPath("volume");
const wifiIcon = iconPath("wifi");

function UiIcon({ src, className = "" }: { src: string; className?: string }) {
  return <img className={`ui-icon ${className}`} src={src} alt="" aria-hidden="true" />;
}

function Brand() {
  return (
    <a className="brand" href="./" aria-label="Lantern home">
      <span className="brand-mark"><UiIcon src={lampIcon} /></span>
      <span><strong>lantern</strong><small>PRIVATE BY DESIGN</small></span>
    </a>
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
      onPaired({ origin: api.origin, deviceId: info.deviceId, name: info.name, token: grant.token, expiresAt: grant.expiresAt });
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
          <div><label htmlFor="pair-code">Six-digit pairing code</label>
            <input id="pair-code" type="text" inputMode="numeric" pattern="[0-9]{6}" minLength={6} maxLength={6}
              required autoComplete="off" value={code} onChange={(event) => setCode(event.target.value)} disabled={busy} /></div>
          <div><label htmlFor="viewer-name">Name this viewer</label>
            <input id="viewer-name" required maxLength={80} autoComplete="off" value={viewerName}
              onChange={(event) => setViewerName(event.target.value)} disabled={busy} /></div>
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

function EventHistory({ events, connection, loading }: { events: CameraEvent[]; connection: CameraConnection; loading: boolean }) {
  const [preview, setPreview] = useState<{ event: CameraEvent; url: string } | null>(null);
  const [loadingId, setLoadingId] = useState<string | null>(null);
  const [previewError, setPreviewError] = useState<string | null>(null);
  const operation = useRef<AbortController | null>(null);
  const previewUrl = useRef<string | null>(null);
  useEffect(() => () => { operation.current?.abort(); if (previewUrl.current) URL.revokeObjectURL(previewUrl.current); }, []);
  const clearPreview = () => {
    operation.current?.abort(); operation.current = null;
    if (previewUrl.current) URL.revokeObjectURL(previewUrl.current);
    previewUrl.current = null; setPreview(null); setLoadingId(null);
  };

  async function view(event: CameraEvent) {
    clearPreview(); setPreviewError(null);
    const abort = new AbortController(); operation.current = abort; setLoadingId(event.id);
    try {
      const blob = await connection.snapshot(event.id, abort.signal);
      if (abort.signal.aborted) return;
      const url = URL.createObjectURL(blob); previewUrl.current = url; setPreview({ event, url });
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
        <button className="quiet" disabled={loading} onClick={() => { void connection.refresh(); }}>{loading ? "Refreshing..." : "Refresh events"}</button>
        {loading && <p role="status" className="small muted">Reading camera history...</p>}
        {!loading && events.length === 0 && <p className="small muted">No saved events. Take a snapshot while this camera is armed.</p>}
        {events.length > 0 && <ol className="event-list">{events.map((event) => (
          <li key={event.id}><div><strong>{event.trigger === "person" ? "Person detected" : "Manual snapshot"}</strong>
            <time dateTime={event.createdAt}>{new Date(event.createdAt).toLocaleString()}</time>
            {event.confidence !== null && <span className="small muted">{Math.round(event.confidence * 100)}% model confidence</span>}</div>
            <button className="quiet" onClick={() => { void view(event); }} disabled={loadingId === event.id}>{loadingId === event.id ? "Loading..." : "View snapshot"}</button></li>
        ))}</ol>}
        {preview && <figure className="snapshot-preview">
          <img src={preview.url} alt={`${preview.event.trigger === "person" ? "Person detection" : "Manual"} snapshot captured ${new Date(preview.event.createdAt).toLocaleString()}`} />
          <figcaption><a href={preview.url} download={`lantern-${preview.event.id}.jpg`}>Download snapshot</a><button className="quiet" onClick={clearPreview}>Close snapshot</button></figcaption>
        </figure>}
      </div>
    </details>
  );
}

type TileState = "checking" | "armed" | "disarmed" | "offline" | "pair-again";

function CameraTile({ peer, selected, onSelect }: { peer: SavedPeer; selected: boolean; onSelect(): void }) {
  const [status, setStatus] = useState<TileState>(peer.token ? "checking" : "pair-again");
  useEffect(() => {
    if (!peer.token) { setStatus("pair-again"); return; }
    const abort = new AbortController(); setStatus("checking");
    void new PeerApi(peer.origin).info(abort.signal).then(
      (info) => { if (!abort.signal.aborted) setStatus(info.armed ? "armed" : "disarmed"); },
      () => { if (!abort.signal.aborted) setStatus("offline"); },
    );
    return () => abort.abort();
  }, [peer.origin, peer.token]);
  const statusLabel = status === "pair-again" ? "Pair again" : status === "checking" ? "Checking" : status === "armed" ? "Armed" : status === "disarmed" ? "Disarmed" : "Offline";
  return (
    <button className={`camera-tile ${selected ? "selected" : ""}`} aria-pressed={selected}
      aria-label={`${peer.name}, ${statusLabel}`} onClick={onSelect}>
      <span className="camera-tile-visual"><UiIcon src={videoIcon} /></span>
      <span className="camera-tile-copy"><strong>{peer.name}</strong><span className={`camera-state ${status}`}><i aria-hidden="true" />{statusLabel}</span></span>
      <UiIcon src={caretRightIcon} className="camera-tile-caret" />
    </button>
  );
}

function ActionButton({ icon, label, detail, className = "", ...props }: {
  icon: ReactNode; label: string; detail?: string;
} & ButtonHTMLAttributes<HTMLButtonElement>) {
  return <button {...props} className={`action-button ${className}`}><span className="action-icon">{icon}</span><span><strong>{label}</strong>{detail && <small>{detail}</small>}</span></button>;
}

function CameraCard({ peer, onRemove, onExpired, onWarning }: {
  peer: SavedPeer; onRemove(): void; onExpired(): void; onWarning(message: string): void;
}) {
  const [connection] = useState(() => new CameraConnection(new PeerApi(peer.origin, peer.token), peer.deviceId, onExpired, onWarning));
  const state = useSyncExternalStore(connection.subscribe, connection.getSnapshot);
  const video = useRef<HTMLVideoElement | null>(null);
  const [sound, setSound] = useState(false);
  const [playbackBlocked, setPlaybackBlocked] = useState(false);
  const [confirmRemove, setConfirmRemove] = useState(false);
  useEffect(() => { void connection.refresh(); return () => { void connection.stop(); }; }, [connection]);
  useEffect(() => {
    const element = video.current; if (!element) return;
    element.srcObject = state.stream;
    if (state.stream) void element.play().then(() => setPlaybackBlocked(false), () => setPlaybackBlocked(true));
    return () => { element.srcObject = null; };
  }, [state.stream]);
  useEffect(() => { if (!state.audio) setSound(false); }, [state.audio]);

  const connecting = state.connection === "connecting";
  const connected = state.connection === "connected";
  const name = state.info?.name ?? peer.name;
  const cameraState = connected ? "LIVE" : connecting ? "CONNECTING" : state.info?.armed ? "ARMED" : state.info ? "DISARMED" : "UNCONFIRMED";

  return (
    <article className="camera-console" aria-label={`${name} camera`}>
      <div className="video-container">
        <video ref={video} autoPlay playsInline muted={!sound} aria-label={`${name} live stream`} />
        <div className="video-status"><span className={`status-dot ${connected ? "live" : ""}`} aria-hidden="true" /><strong>{cameraState}</strong><span>{name}</span><span>Direct &amp; private</span><UiIcon src={shieldCheckIcon} /></div>
        {!state.stream && <div className="video-empty"><UiIcon src={videoIcon} />
          <strong>{connecting ? "Connecting directly to camera..." : "Live video stays on demand"}</strong>
          <span>{connecting ? "Negotiating an authenticated media session" : "Start watching when you need it. No stream is sent while idle."}</span>
          {!connecting && <button className="primary watch-inline" onClick={() => { void connection.connect(false); }}><UiIcon src={playIcon} />Watch live</button>}
        </div>}
        {playbackBlocked && state.stream && <button className="play-overlay primary" onClick={() => { void video.current?.play().then(() => setPlaybackBlocked(false), () => setPlaybackBlocked(true)); }}>Tap to play live video</button>}
      </div>

      <div className="connection-strip" aria-label="Camera connection details">
        <div><span className="summary-icon positive"><UiIcon src={wifiIcon} /></span><span><strong>{state.info ? "Camera reachable" : "Checking connection"}</strong><small>Direct WebRTC (LAN/Meshnet)</small></span></div>
        <div><span className="summary-icon"><UiIcon src={deviceMobileIcon} /></span><span><strong>{name}</strong><small>{peer.origin}</small></span></div>
        <div><span className={`summary-icon ${state.info?.armed ? "positive" : ""}`}><UiIcon src={shieldCheckIcon} /></span><span><strong>{state.info?.armed ? "Armed" : "Not armed"}</strong><small>Camera capture state</small></span></div>
      </div>

      <div className="camera-controls">
        {!connected && !connecting && <ActionButton className="primary-action" icon={<UiIcon src={playIcon} />} label="Watch" detail="Video only" onClick={() => { void connection.connect(false); }} />}
        {(connected || connecting) && <ActionButton className="danger-action" icon={<UiIcon src={phoneOffIcon} />} label="Disconnect" detail={connecting ? "Cancel connection" : "Close direct session"} onClick={() => { void connection.disconnect(); }} />}
        <ActionButton icon={<UiIcon src={volumeIcon} />} label={state.audio ? (sound ? "Mute camera" : "Listen") : "Connect with audio"}
          detail={state.audio ? (sound ? "Audio on" : "Audio muted") : "Restart with audio"} disabled={connecting || state.info?.capabilities.audio === false}
          aria-pressed={state.audio ? sound : undefined} onClick={() => {
            if (!state.audio) { void connection.connect(true); return; }
            const nextSound = !sound; setSound(nextSound); if (video.current) video.current.muted = !nextSound;
            void video.current?.play().then(() => setPlaybackBlocked(false), () => setPlaybackBlocked(true));
          }} />
        <ActionButton icon={<UiIcon src={microphoneIcon} />} label={state.microphonePending ? "Requesting microphone..." : state.microphone ? "Stop talking" : "Talk"}
          detail={state.microphone ? "Microphone active" : "Push to speak"} className={state.microphone ? "talking" : ""}
          disabled={!connected || !state.audio || state.microphonePending} aria-pressed={state.microphone} onClick={() => { void connection.setMicrophone(!state.microphone); }} />
        <ActionButton icon={<UiIcon src={cameraIcon} />} label={state.snapshotting ? "Saving..." : "Take snapshot"} detail="Store on camera"
          disabled={state.snapshotting || !state.info?.armed || state.info.capabilities.snapshots === false} onClick={() => { void connection.capture(); }} />
      </div>
      {state.microphone && <p className="mic-notice" role="status"><UiIcon src={microphoneIcon} />Your microphone is transmitting to this camera.</p>}
      {state.error && <div className="error-banner" role="alert"><p>{state.error}</p><button className="quiet" onClick={() => connection.clearError()}>Dismiss</button></div>}
      <EventHistory connection={connection} events={state.events} loading={state.loadingEvents} />
      <div className="camera-footer"><span className="small muted">{state.info?.capabilities.personDetection ? "On-device person detection" : "Local snapshot storage"}</span>
        {!confirmRemove ? <button className="quiet" onClick={() => setConfirmRemove(true)}>Remove camera</button> : <div className="remove-confirm"><span className="small">Forget this viewer's credentials?</span><button className="danger" onClick={() => { void connection.stop().then(onRemove); }}>Remove</button><button className="quiet" onClick={() => setConfirmRemove(false)}>Keep</button></div>}
      </div>
    </article>
  );
}

function UnpairedCamera({ peer, onPair, onRemove }: { peer: SavedPeer; onPair(): void; onRemove(): void }) {
  return <article className="camera-console unpaired" aria-label={`${peer.name} camera`}><UiIcon src={videoIcon} /><p className="eyebrow">PAIRING REQUIRED</p><h2>{peer.name}</h2><p className="endpoint">{peer.origin}</p><p className="muted">This viewer's authorization has expired or been revoked.</p><div className="unpaired-actions"><button className="primary" onClick={onPair}>Pair again</button><button className="quiet" onClick={onRemove}>Remove camera</button></div></article>;
}

export default function App() {
  const [initial] = useState(loadPeers);
  const [peers, setPeers] = useState(initial.peers);
  const [selectedOrigin, setSelectedOrigin] = useState<string | null>(initial.peers[0]?.origin ?? null);
  const [warning, setWarning] = useState(initial.warning);
  const [pairOrigin, setPairOrigin] = useState<string | null>(null);
  const [showSetup, setShowSetup] = useState(false);
  const warn = useCallback((message: string) => setWarning(message), []);
  const persist = useCallback((update: (previous: SavedPeer[]) => SavedPeer[]) => {
    setPeers((previous) => { const next = update(previous); const problem = savePeers(next); if (problem) queueMicrotask(() => setWarning(problem)); return next; });
  }, []);

  useEffect(() => {
    if (selectedOrigin && peers.some((peer) => peer.origin === selectedOrigin)) return;
    setSelectedOrigin(peers[0]?.origin ?? null);
  }, [peers, selectedOrigin]);

  function paired(peer: SavedPeer) {
    const existing = peers.find((item) => item.origin === peer.origin);
    if (existing && existing.deviceId !== peer.deviceId) { setWarning("This endpoint has a different camera identity. Remove its old entry before pairing the replacement."); return; }
    if (!existing && peers.length >= 16) { setWarning("This viewer supports at most 16 paired cameras."); return; }
    persist((previous) => [...previous.filter((item) => item.origin !== peer.origin), peer]);
    setSelectedOrigin(peer.origin); setPairOrigin(null);
  }

  const selected = peers.find((peer) => peer.origin === selectedOrigin) ?? null;
  return (
    <div className="app-shell">
      <aside className="sidebar"><Brand /><nav aria-label="Primary">
        <a className="nav-item active" href="#cameras" aria-current="page"><UiIcon src={videoIcon} /><span>Cameras</span></a>
        <button className="nav-item" onClick={() => setPairOrigin("")}><UiIcon src={plusIcon} /><span>Add camera</span></button>
      </nav><div className="sidebar-art" role="img" aria-label="A warm lantern beside houseplants" />
        <p className="sidebar-message">Your cameras.<br />Your network.<br />A more peaceful home.</p>
        <p className="sidebar-foot"><UiIcon src={cloudOffIcon} />No cloud. No analytics.</p></aside>

      <div className="content-shell"><header className="mobile-topbar"><Brand /><button className="icon-button" aria-label="Add camera" onClick={() => setPairOrigin("")}><UiIcon src={plusIcon} /></button></header>
        <main id="cameras"><section className="page-heading"><div><p className="eyebrow">YOUR CAMERAS. YOUR NETWORK.</p><h1>Cameras</h1><p>Direct and private <span aria-hidden="true">•</span> No cloud <span aria-hidden="true">•</span> Just your devices</p></div><span className="fleet-count"><strong>{peers.length}</strong> paired {peers.length === 1 ? "camera" : "cameras"}</span></section>
          {warning && <div className="error-banner" role="alert"><p>{warning}</p><button className="quiet" onClick={() => setWarning(null)}>Dismiss</button></div>}
          {pairOrigin !== null && <PairForm key={pairOrigin} initialOrigin={pairOrigin} onPaired={paired} onCancel={() => setPairOrigin(null)} />}
          {peers.length === 0 && pairOrigin === null && <section className="welcome" aria-labelledby="welcome-title"><div className="welcome-visual"><UiIcon src={lampIcon} /></div><p className="eyebrow">NO SHARED SERVER. PAIRED VIEWERS.</p><h2 id="welcome-title">Start with one trusted camera.</h2><p className="muted">Install Lantern on an Android phone, provision its HTTPS identity, and pair this browser using the code on its screen.</p><button className="primary" onClick={() => setPairOrigin("")}><UiIcon src={plusIcon} />Pair your first camera</button></section>}
          {peers.length > 0 && <><div className="camera-strip" role="group" aria-label="Paired cameras">{peers.map((peer) => <CameraTile key={peer.origin} peer={peer} selected={peer.origin === selectedOrigin} onSelect={() => setSelectedOrigin(peer.origin)} />)}</div>
            {selected?.token ? <CameraCard key={`${selected.origin}:${selected.token}`} peer={selected} onWarning={warn}
              onRemove={() => persist((previous) => previous.filter((item) => item.origin !== selected.origin))}
              onExpired={() => { persist((previous) => previous.map((item) => item.origin === selected.origin ? { ...item, token: null, expiresAt: null } : item)); setWarning(`${selected.name}: credentials expired or were revoked. Pair this browser again.`); }} />
              : selected ? <UnpairedCamera peer={selected} onPair={() => setPairOrigin(selected.origin)} onRemove={() => persist((previous) => previous.filter((item) => item.origin !== selected.origin))} /> : null}</>}
          <section className="setup-note"><button className="setup-toggle" aria-expanded={showSetup} aria-controls="setup-details" onClick={() => setShowSetup((value) => !value)}><span><UiIcon src={shieldCheckIcon} />Connection and privacy checklist</span><span aria-hidden="true">{showSetup ? "−" : "+"}</span></button>
            {showSetup && <div id="setup-details" className="setup-details"><p><strong>Trust HTTPS on every viewer.</strong> Import the household CA and enable TLS trust. Meshnet does not issue browser certificates.</p><p><strong>Approve this origin on each camera:</strong> <code>{location.origin}</code>. GitHub Pages and other camera-hosted viewers are separate origins.</p><p><strong>For remote viewing:</strong> enable Meshnet on both devices and grant the viewer remote-access permission. Keep camera-level pairing enabled.</p><p><strong>Doorbell audio:</strong> start an audio session, tap Listen, then Talk. Only one audio session per camera is allowed. Closed browser tabs do not receive reliable doorbell alerts.</p><p><strong>Initial milestone:</strong> local snapshots and direct live sessions. Peer replication, cloud backup and owner recognition are not implemented yet.</p><p><strong>Remove versus revoke:</strong> removing a camera forgets this tab's credentials. Revoke the viewer grant on the phone to invalidate it.</p></div>}
          </section></main><footer className="page-footer"><span>Lantern / personal camera network</span><span>Media travels directly to your viewer.</span></footer></div>
    </div>
  );
}
