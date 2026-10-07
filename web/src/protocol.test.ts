import { describe, expect, it } from "vitest";
import { EventSchema, InfoSchema, SavedPeersSchema, normalizeOrigin } from "./protocol";

const id = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
const event = {
  id, deviceId: id, createdAt: "2026-10-04T12:00:00Z", trigger: "manual",
  confidence: null, snapshotPath: `/api/v1/events/${id}/snapshot`,
};

describe("endpoint validation", () => {
  it("canonicalizes HTTPS origins without rejecting Meshnet addressing", () => {
    expect(normalizeOrigin(" https://FRONT-DOOR.nord:8443/ ")).toBe("https://front-door.nord:8443");
    expect(normalizeOrigin("https://100.101.12.13:8443")).toBe("https://100.101.12.13:8443");
    expect(normalizeOrigin("https://[fd00::1]:8443")).toBe("https://[fd00::1]:8443");
  });
  it.each([
    "http://192.168.1.1:8443", "javascript:alert(1)", "front-door.nord",
    "https://user:password@front-door.nord", "https://front-door.nord/api",
    "https://front-door.nord/?token=secret", "https://front-door.nord/#secret",
  ])("rejects unsafe or ambiguous address %s", (origin) => {
    expect(() => normalizeOrigin(origin)).toThrow();
  });
});

describe("wire validation", () => {
  it("requires the exact supported protocol version and actual capability booleans", () => {
    const info = { apiVersion: 1, deviceId: id, name: "Front door", armed: true,
      capabilities: { video: true, audio: true, snapshots: true, personDetection: false } };
    expect(InfoSchema.safeParse(info).success).toBe(true);
    expect(InfoSchema.safeParse({ ...info, apiVersion: 2 }).success).toBe(false);
    expect(InfoSchema.safeParse({ ...info, armed: "true" }).success).toBe(false);
  });
  it("accepts real manual and person events", () => {
    expect(EventSchema.safeParse(event).success).toBe(true);
    expect(EventSchema.safeParse({ ...event, trigger: "person", confidence: 0.85 }).success).toBe(true);
  });
  it.each([0, 65_536, 8444.5, "8444", Infinity, NaN])("rejects invalid local discovery port %s", (localStunPort) => {
    const info = { apiVersion: 1, deviceId: id, name: "Front door", armed: true, localStunPort,
      capabilities: { video: true, audio: true, snapshots: true, personDetection: false } };
    expect(InfoSchema.safeParse(info).success).toBe(false);
  });
  it("rejects credential URLs, arbitrary paths and fabricated confidence", () => {
    expect(EventSchema.safeParse({ ...event, snapshotPath: "https://attacker.invalid/secret" }).success).toBe(false);
    expect(EventSchema.safeParse({ ...event, confidence: 0.8 }).success).toBe(false);
    expect(EventSchema.safeParse({ ...event, trigger: "person" }).success).toBe(false);
    expect(EventSchema.safeParse({ ...event, trigger: "person", confidence: Infinity }).success).toBe(false);
  });
  it("rejects duplicate registry entries and incomplete credentials", () => {
    const peer = { origin: "https://front-door.nord:8443", deviceId: id, name: "Door",
      token: "a".repeat(43), expiresAt: "2099-01-01T00:00:00Z" };
    expect(SavedPeersSchema.safeParse([peer]).success).toBe(true);
    expect(SavedPeersSchema.safeParse([peer, peer]).success).toBe(false);
    expect(SavedPeersSchema.safeParse([{ ...peer, token: null }]).success).toBe(false);
  });
});
