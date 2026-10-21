import { useEffect, useState } from "react";
import { PeerApi, isUnauthorized } from "./api";
import { CameraConnection } from "./connection";
import { describeError, type Doorbell, type PeerInfo, type SavedPeer, type StorageStatus } from "./protocol";

export function CameraExtras({ peer, info, connection, alerts, onExpired }: {
  peer: SavedPeer; info: PeerInfo | null; connection: CameraConnection; alerts: boolean; onExpired(): void;
}) {
  const [api] = useState(() => new PeerApi(peer.origin, peer.token));
  const [rings, setRings] = useState<Doorbell["rings"]>([]);
  const [storage, setStorage] = useState<StorageStatus | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [storageRevision, setStorageRevision] = useState(0);
  const [tick, setTick] = useState(Date.now);
  useEffect(() => {
    if (!info?.capabilities.doorbell || !alerts) return;
    let operation: AbortController | null = null;
    let alive = true;
    let expired = false;
    async function poll() {
      setTick(Date.now());
      if (!alive || expired || document.visibilityState === "hidden" || operation) return;
      const current = new AbortController();
      operation = current;
      try {
        const result = await api.doorbell(current.signal);
        if (result.deviceId !== peer.deviceId) throw new Error("Doorbell belongs to another device. Pair again.");
        if (alive && !current.signal.aborted) { setRings(result.rings); setError(null); }
      } catch (cause) {
        if (alive && !current.signal.aborted) {
          if (isUnauthorized(cause)) { expired = true; onExpired(); }
          setError(describeError(cause));
        }
      } finally { if (operation === current) operation = null; }
    }
    const visibility = () => {
      if (document.visibilityState === "hidden") { operation?.abort(); operation = null; }
      else void poll();
    };
    void poll();
    const timer = setInterval(() => { void poll(); }, 5_000);
    document.addEventListener("visibilitychange", visibility);
    return () => { alive = false; clearInterval(timer); operation?.abort(); document.removeEventListener("visibilitychange", visibility); };
  }, [api, info?.capabilities.doorbell, alerts, peer.deviceId, onExpired]);

  useEffect(() => {
    if (!info?.capabilities.rollingSnapshots) return;
    const operation = new AbortController();
    void api.storage(operation.signal).then((value) => {
      if (!operation.signal.aborted) setStorage(value);
    }, (cause: unknown) => {
      if (!operation.signal.aborted) {
        if (isUnauthorized(cause)) onExpired();
        setError(describeError(cause));
      }
    });
    return () => operation.abort();
  }, [api, info?.capabilities.rollingSnapshots, storageRevision, onExpired]);

  const recent = rings.find((ring) => !ring.acknowledged && tick - Date.parse(ring.createdAt) >= -5_000 && tick - Date.parse(ring.createdAt) < 120_000);
  async function acknowledge(id: string, answer: boolean) {
    if (busy) return;
    setBusy(true);
    try {
      if (answer) {
        await connection.connect(true);
        if (connection.getSnapshot().connection !== "connected") return;
      }
      await api.acknowledgeRing(id);
      setRings((previous) => previous.map((ring) => ring.id === id ? { ...ring, acknowledged: true } : ring));
      setError(null);
    } catch (cause) {
      if (isUnauthorized(cause)) onExpired();
      setError(describeError(cause));
    } finally { setBusy(false); }
  }
  return <>
    {alerts && recent && <section className="doorbell-banner" aria-label="Incoming doorbell"><p role="status"><strong>Someone rang the doorbell.</strong> <time dateTime={recent.createdAt}>{new Date(recent.createdAt).toLocaleTimeString()}</time></p><p className="small muted">Answer connects audio. Tap Listen and Talk to speak. Your microphone starts off.</p><div className="camera-controls"><button className="primary" disabled={busy} onClick={() => { void acknowledge(recent.id, true); }}>Answer doorbell</button><button disabled={busy} onClick={() => { void acknowledge(recent.id, false); }}>Dismiss ring</button></div></section>}
    {info?.capabilities.doorbell && <p className="mic-notice">Doorbell alerts {alerts ? "work while this viewer is open and visible" : "are disabled for this browser"}. Closed tabs cannot receive rings.</p>}
    {alerts && rings.length > 0 && <details className="history"><summary>Doorbell history <span className="muted">{rings.length} recent</span></summary><ol className="event-list">{rings.map((ring) => <li key={ring.id}><time dateTime={ring.createdAt}>{new Date(ring.createdAt).toLocaleString()}</time><span>{ring.acknowledged ? "Acknowledged" : "Unanswered"}</span>{!ring.acknowledged && <button disabled={busy} onClick={() => { void acknowledge(ring.id, false); }}>Acknowledge ring</button>}</li>)}</ol></details>}
    {storage && <details className="history"><summary>Local storage <span className="muted">{storage.items} snapshots</span></summary><div className="history-body"><p>{storage.automaticSnapshots ? "Automatic snapshots every minute while armed." : "Automatic snapshots off. Enable on the camera phone."}</p><p className="small muted">{(storage.bytes / 1048576).toFixed(1)} MiB used. Limits: {storage.maxItems} images, {storage.maxBytes / 1048576} MiB, {Math.round(storage.maxAgeSeconds / 86400)} days; earliest limit wins. {Math.floor(storage.availableBytes / 1048576)} MiB free; {storage.reserveBytes / 1048576} MiB reserved.</p><p className="small muted">Still images only. Continuous video and peer backups are planned.</p>{storage.error && <p role="alert" className="error">{storage.error}</p>}<button onClick={() => setStorageRevision((value) => value + 1)}>Refresh storage</button></div></details>}
    {error && <p role="alert" className="error camera-extra-error">{error}</p>}
  </>;
}
