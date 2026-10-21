import { z } from "zod";

export function normalizeOrigin(value: string): string {
  let url: URL;
  try {
    url = new URL(value.trim());
  } catch {
    throw new Error("Enter a complete HTTPS address, such as https://front-door.nord:8443.");
  }
  if (
    url.protocol !== "https:" || url.username || url.password ||
    url.search || url.hash || url.pathname !== "/"
  ) {
    throw new Error("Use an HTTPS origin only, without credentials, a path, query or fragment.");
  }
  return url.origin;
}

const uuid = z.string().uuid();
const timestamp = z.string().datetime({ offset: true });
const token = z.string().regex(/^[A-Za-z0-9_-]{43,256}$/);
const name = z.string().trim().min(1).max(80);

export const InfoSchema = z.object({
  apiVersion: z.literal(1),
  deviceId: uuid,
  name,
  armed: z.boolean(),
  localStunPort: z.number().int().min(1).max(65_535).optional(),
  capabilities: z.object({
    video: z.boolean(),
    audio: z.boolean(),
    snapshots: z.boolean(),
    personDetection: z.boolean(),
    doorbell: z.boolean().optional(),
    rollingSnapshots: z.boolean().optional(),
  }),
});
export type PeerInfo = z.infer<typeof InfoSchema>;

export const PairSchema = z.object({ token, expiresAt: timestamp, deviceId: uuid });
export const SessionSchema = z.object({
  sessionId: uuid,
  answer: z.object({ type: z.literal("answer"), sdp: z.string().min(1).max(65_536) }),
  expiresAt: timestamp,
});
export const HeartbeatSchema = z.object({ expiresAt: timestamp });
export const ErrorSchema = z.object({
  error: z.object({ code: z.string().min(1).max(80), message: z.string().min(1).max(500) }),
});
export const EventSchema = z.object({
  id: uuid,
  deviceId: uuid,
  createdAt: timestamp,
  trigger: z.enum(["manual", "person", "scheduled"]),
  confidence: z.number().finite().min(0).max(1).nullable(),
  snapshotPath: z.string(),
}).superRefine((event, context) => {
  if (event.snapshotPath !== `/api/v1/events/${event.id}/snapshot`) {
    context.addIssue({ code: "custom", message: "Invalid snapshot path" });
  }
  if ((event.trigger !== "person") !== (event.confidence === null)) {
    context.addIssue({ code: "custom", message: "Invalid detection confidence" });
  }
});
export type CameraEvent = z.infer<typeof EventSchema>;
export const EventsSchema = z.object({ events: z.array(EventSchema).max(50) });

export const SavedPeerSchema = z.object({
  origin: z.string().refine((value) => {
    try { return normalizeOrigin(value) === value; } catch { return false; }
  }),
  deviceId: uuid,
  name,
  token: token.nullable(),
  expiresAt: timestamp.nullable(),
}).refine((peer) => (peer.token === null) === (peer.expiresAt === null), {
  message: "Incomplete peer credentials",
});
export type SavedPeer = z.infer<typeof SavedPeerSchema>;
export const SavedPeersSchema = z.array(SavedPeerSchema).max(16).refine(
  (peers) => new Set(peers.map((peer) => peer.origin)).size === peers.length,
  { message: "Duplicate peers" },
);

export function describeError(error: unknown): string {
  return error instanceof Error ? error.message : "An unexpected operation failed.";
}

export const DoorbellSchema = z.object({
  deviceId: uuid,
  rings: z.array(z.object({ id: uuid, createdAt: timestamp, acknowledged: z.boolean() })).max(50),
});
export type Doorbell = z.infer<typeof DoorbellSchema>;
export const StorageStatusSchema = z.object({
  kind: z.literal("snapshots"), items: z.number().int().nonnegative(), bytes: z.number().int().nonnegative(),
  maxItems: z.number().int().positive(), maxBytes: z.number().int().positive(),
  maxAgeSeconds: z.number().int().positive(), reserveBytes: z.number().int().nonnegative(),
  availableBytes: z.number().int().nonnegative(), automaticSnapshots: z.boolean(), error: z.string().nullable(),
});
export type StorageStatus = z.infer<typeof StorageStatusSchema>;
