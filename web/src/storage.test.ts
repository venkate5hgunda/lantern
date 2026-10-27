import { beforeEach, describe, expect, it } from "vitest";
import { loadPeers, savePeers } from "./storage";

const peer = {
  origin: "https://front-door.nord:8443", deviceId: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
  name: "Door", token: "a".repeat(43), expiresAt: "2099-01-01T00:00:00Z",
};

describe("session-only credential registry", () => {
  beforeEach(() => { sessionStorage.clear(); localStorage.clear(); });
  it("stores credentials in session storage, never local storage", () => {
    expect(savePeers([peer])).toBeNull();
    expect(loadPeers().peers).toEqual([peer]);
    expect(localStorage.length).toBe(0);
  });
  it("keeps an expired endpoint but removes its grant", () => {
    savePeers([{ ...peer, expiresAt: "2020-01-01T00:00:00Z" }]);
    expect(loadPeers().peers).toEqual([{ ...peer, token: null, expiresAt: null }]);
  });
  it("does not trust malformed or tampered saved state", () => {
    sessionStorage.setItem("lantern.peers.v1", '{"broken":true}');
    const result = loadPeers();
    expect(result.peers).toEqual([]);
    expect(result.warning).toContain("invalid saved data");
  });
  it("reports unavailable storage instead of pretending persistence worked", () => {
    const unavailable: Storage = {
      length: 0, key: () => null, clear: () => {}, removeItem: () => {},
      getItem: () => { throw new DOMException("Denied", "SecurityError"); },
      setItem: () => { throw new DOMException("Denied", "SecurityError"); },
    };
    expect(loadPeers(unavailable).warning).not.toBeNull();
    expect(savePeers([peer], unavailable)).toContain("cannot save");
  });
});

it("remembers authorizations only after explicit opt-in and removes them on opt-out", async () => {
  const { setRememberViewer, remembersViewer } = await import("./storage");
  sessionStorage.clear(); localStorage.clear();
  const peers = [peer];
  expect(setRememberViewer(true, peers)).toBeNull();
  expect(remembersViewer()).toBe(true);
  expect(localStorage.getItem("lantern.peers.v1")).toContain(peer.token);
  expect(setRememberViewer(false, peers)).toBeNull();
  expect(remembersViewer()).toBe(false);
  expect(localStorage.getItem("lantern.peers.v1")).toBeNull();
});
