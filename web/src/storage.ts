import { SavedPeersSchema, type SavedPeer } from "./protocol";

const KEY = "lantern.peers.v1";

export function loadPeers(storage: Storage = sessionStorage): { peers: SavedPeer[]; warning: string | null } {
  try {
    const raw = storage.getItem(KEY);
    if (!raw) return { peers: [], warning: null };
    const result = SavedPeersSchema.safeParse(JSON.parse(raw));
    if (!result.success) throw new Error("Invalid peer registry");
    const peers = result.data.map((peer) => (
      peer.expiresAt && Date.parse(peer.expiresAt) <= Date.now()
        ? { ...peer, token: null, expiresAt: null }
        : peer
    ));
    return { peers, warning: null };
  } catch {
    return {
      peers: [],
      warning: "Saved camera credentials could not be read. Pair again; this tab will not trust invalid saved data.",
    };
  }
}

export function savePeers(peers: SavedPeer[], storage: Storage = sessionStorage): string | null {
  const parsed = SavedPeersSchema.safeParse(peers);
  if (!parsed.success) return "Camera settings were not saved because the registry is invalid.";
  try {
    storage.setItem(KEY, JSON.stringify(parsed.data));
    return null;
  } catch {
    return "This browser cannot save session credentials. Cameras remain paired only while this page is open.";
  }
}
