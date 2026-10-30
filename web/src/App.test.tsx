import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import App from "./App";

const peers = [
  {
    origin: "https://front-door.nord:8443",
    deviceId: "11111111-1111-4111-8111-111111111111",
    name: "Front door",
    token: "b".repeat(43),
    expiresAt: "2099-01-01T00:00:00Z",
    armed: true,
  },
  {
    origin: "https://garage.nord:8443",
    deviceId: "22222222-2222-4222-8222-222222222222",
    name: "Garage",
    token: "c".repeat(43),
    expiresAt: "2099-01-01T00:00:00Z",
    armed: false,
  },
];

describe("multi-camera viewer", () => {
  beforeEach(() => {
    sessionStorage.setItem("lantern.peers.v1", JSON.stringify(peers.map(({ armed: _armed, ...peer }) => peer)));
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const url = new URL(String(input));
      const peer = peers.find((candidate) => candidate.origin === url.origin);
      if (!peer) throw new Error(`Unexpected camera origin: ${url.origin}`);
      const body = url.pathname === "/api/v1/info"
        ? { apiVersion: 1, deviceId: peer.deviceId, name: peer.name, armed: peer.armed,
          capabilities: { video: true, audio: true, snapshots: true, personDetection: false } }
        : { events: [] };
      return new Response(JSON.stringify(body), { status: 200, headers: { "Content-Type": "application/json" } });
    }));
  });

  afterEach(() => {
    cleanup();
    sessionStorage.clear();
    vi.unstubAllGlobals();
  });

  it("shows one active console and switches it from the camera strip", async () => {
    const user = userEvent.setup();
    render(<App />);

    const frontDoor = await screen.findByRole("button", { name: "Front door, Armed" });
    expect(frontDoor.getAttribute("aria-pressed")).toBe("true");
    expect(screen.getByRole("article", { name: "Front door camera" })).not.toBeNull();
    expect(screen.queryByRole("article", { name: "Garage camera" })).toBeNull();

    await user.click(await screen.findByRole("button", { name: "Garage, Disarmed" }));

    expect(screen.getByRole("button", { name: "Garage, Disarmed" }).getAttribute("aria-pressed")).toBe("true");
    expect(screen.getByRole("article", { name: "Garage camera" })).not.toBeNull();
    expect(screen.queryByRole("article", { name: "Front door camera" })).toBeNull();
  });
});
