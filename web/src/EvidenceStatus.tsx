import { useEffect, useState } from "react";
import { PeerApi, isUnauthorized } from "./api";
import { z } from "zod";
import { AnalyticsSchema, BackupStatusSchema, ReplicaHistorySchema, describeError } from "./protocol";
type Analytics = z.infer<typeof AnalyticsSchema>;
type Backup = z.infer<typeof BackupStatusSchema>;
type Replica = z.infer<typeof ReplicaHistorySchema>["replicas"][number];
export function EvidenceStatus({ api, deviceId, analytics, backup, onExpired }: { api: PeerApi; deviceId: string; analytics: boolean; backup: boolean; onExpired(): void }) {
  const [presence, setPresence] = useState<Analytics | null>(null);
  const [replication, setReplication] = useState<Backup | null>(null);
  const [replicas, setReplicas] = useState<Replica[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [revision, setRevision] = useState(0);
  const [download, setDownload] = useState<{ url: string; id: string } | null>(null);
  const [downloadId, setDownloadId] = useState<string | null>(null);
  useEffect(() => {
    if (!analytics && !backup) return;
    const abort = new AbortController();
    let pending = false;
    async function refresh() {
      if (pending || document.visibilityState === "hidden") return;
      pending = true;
      try {
        const [activity, status, history] = await Promise.all([analytics ? api.analytics(abort.signal) : null, backup ? api.backupStatus(abort.signal) : null, backup ? api.replicas(abort.signal) : null]);
        if (activity && activity.deviceId !== deviceId) throw new Error("Analytics came from a different camera. Pair again.");
        if (!abort.signal.aborted) { setPresence(activity); setReplication(status); setReplicas(history?.replicas ?? []); setError(null); }
      } catch (cause) {
        if (!abort.signal.aborted) { if (isUnauthorized(cause)) onExpired(); setError(describeError(cause)); }
      } finally { pending = false; }
    }
    void refresh();
    const timer = setInterval(() => { void refresh(); }, 15_000);
    const visible = () => { if (document.visibilityState === "visible") void refresh(); };
    document.addEventListener("visibilitychange", visible);
    return () => { abort.abort(); clearInterval(timer); document.removeEventListener("visibilitychange", visible); };
  }, [api, deviceId, analytics, backup, onExpired, revision]);
  useEffect(() => () => { if (download) URL.revokeObjectURL(download.url); }, [download]);
  useEffect(() => {
    if (!downloadId) return;
    const abort = new AbortController();
    void api.replicaSnapshot(downloadId, abort.signal).then((blob) => {
      if (!abort.signal.aborted) setDownload({ id: downloadId, url: URL.createObjectURL(blob) });
    }, (cause: unknown) => { if (!abort.signal.aborted) { if (isUnauthorized(cause)) onExpired(); setError(describeError(cause)); } });
    return () => abort.abort();
  }, [api, downloadId, onExpired]);
  return <>
    {presence && <details className="history"><summary>Person detection and presence</summary><div className="history-body"><p>{!presence.enabled ? "Person detection is off. Enable it on the camera phone." : !presence.available ? "Analysis unavailable — no conclusion about presence." : presence.present ? `${presence.people} people currently observed.` : "No confirmed presence at the latest observation."}</p><p className="small muted">{presence.observations} samples this camera service run; peak {presence.peakPeople} people. Counts are detections, not unique identities. Model: {presence.model}.</p><p className="small muted">Last sampled: {presence.sampledAt ? new Date(presence.sampledAt).toLocaleString() : "Not yet sampled"}. Last person observation: {presence.lastSeen ? new Date(presence.lastSeen).toLocaleString() : "None this run"}.</p>{presence.error && <p role="alert" className="error">{presence.error}</p>}</div></details>}
    {replication && <details className="history"><summary>Redundant backups <span className="muted">{replication.targets.length} peers</span></summary><div className="history-body"><p className="small muted">{Math.round(replication.replicaBytes / 1048576)} MiB of {Math.round(replication.quotaBytes / 1048576)} MiB reserved for other cameras. Verified status is measured at each reconciliation, not a guarantee during an outage.</p>{replication.targets.length === 0 && <p>No redundant target configured. Pair another camera in Android settings.</p>}{replication.targets.map((target) => <div key={target.deviceId}><strong>{target.origin}</strong><p>{target.verified} verified copies of {replication.desired} retained local snapshots; {target.pending} pending.</p><p className="small muted">Last reconciled: {target.lastSync ? new Date(target.lastSync).toLocaleString() : "Never"}</p>{target.error && <p className="error">{target.error}</p>}</div>)}<button onClick={() => setRevision((value) => value + 1)}>Refresh backup status</button></div></details>}
    {backup && <details className="history"><summary>Recovered peer evidence <span className="muted">{replicas.length} recent</span></summary><div className="history-body"><p className="small muted">These snapshots are stored on this camera and can be retrieved while their source is offline. Replica retention is independent of the source.</p>{replicas.length === 0 ? <p>No verified peer copies stored yet.</p> : <ol className="event-list">{replicas.map((replica) => <li key={replica.id}><div><strong>{replica.evidence.trigger === "person" ? "Person evidence" : "Peer snapshot"}</strong><time dateTime={new Date(replica.evidence.created).toISOString()}>{new Date(replica.evidence.created).toLocaleString()}</time><span className="small muted">Source {replica.evidence.sourceDeviceId}</span></div><button onClick={() => { setDownload(null); setDownloadId(null); queueMicrotask(() => setDownloadId(replica.id)); }}>Retrieve replica</button></li>)}</ol>}{download && <p><a href={download.url} download={`lantern-replica-${download.id}.jpg`}>Download verified snapshot</a><button className="quiet" onClick={() => { setDownload(null); setDownloadId(null); }}>Close download</button></p>}</div></details>}
    {error && <p role="alert" className="error camera-extra-error">{error}</p>}
  </>;
}
