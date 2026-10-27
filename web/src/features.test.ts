import { beforeEach, expect, it, vi } from "vitest";
import { defaultFeatures, loadFeatures, saveFeatures } from "./features";
beforeEach(() => sessionStorage.clear());
it("fails closed to defaults on malformed and unknown preferences", () => {
  sessionStorage.setItem("lantern.features.v1", JSON.stringify({ doorbellAlerts: "yes", transportDetails: true }));
  expect(loadFeatures()).toEqual(defaultFeatures);
  sessionStorage.setItem("lantern.features.v1", JSON.stringify({ ...defaultFeatures, enableRecognition: true }));
  expect(loadFeatures()).toEqual(defaultFeatures);
});
it("persists only supported browser controls and reports denied storage", () => {
  expect(saveFeatures({ doorbellAlerts: false, transportDetails: true })).toBeNull();
  expect(loadFeatures()).toEqual({ doorbellAlerts: false, transportDetails: true });
  vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => { throw new Error("blocked"); });
  expect(saveFeatures(defaultFeatures)).toContain("could not save");
});
