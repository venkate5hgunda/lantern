import { z } from "zod";
const key = "lantern.features.v1";
export const FeatureSchema = z.object({ doorbellAlerts: z.boolean(), transportDetails: z.boolean() }).strict();
export type Features = z.infer<typeof FeatureSchema>;
export const defaultFeatures: Features = { doorbellAlerts: true, transportDetails: false };
export function loadFeatures(): Features {
  try { const value = sessionStorage.getItem(key); return value ? FeatureSchema.parse(JSON.parse(value)) : { ...defaultFeatures }; }
  catch { return { ...defaultFeatures }; }
}
export function saveFeatures(features: Features): string | null {
  try { sessionStorage.setItem(key, JSON.stringify(FeatureSchema.parse(features))); return null; }
  catch { return "This browser could not save preferences. Changes will not survive navigation."; }
}
