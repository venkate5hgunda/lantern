import { expect, test } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";

test("review embeds the source contract and keeps unsupported switches disabled", async ({ page }) => {
  await page.goto("/review.html");
  await expect(page.getByRole("heading", { name: "Lantern product requirements" })).toBeVisible();
  await page.getByRole("link", { name: "Architecture", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Existing system and streaming decision" })).toBeVisible();
  await page.getByRole("link", { name: "Controls", exact: true }).click();
  await expect(page.getByLabel("Encrypted peer backups", { exact: false })).toBeDisabled();
  await page.getByLabel("Foreground doorbell alerts").uncheck();
  await page.getByLabel("Connection details").check();
  await page.reload();
  await expect(page.getByLabel("Foreground doorbell alerts")).not.toBeChecked();
  await expect(page.getByLabel("Connection details")).toBeChecked();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  const accessibility = await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa"]).analyze();
  expect(accessibility.violations).toEqual([]);
  await page.getByRole("link", { name: "Open viewer" }).click();
  await expect(page.getByRole("heading", { name: "A window into home." })).toBeVisible();
  await expect(page.getByRole("link", { name: "Product review" })).toHaveCount(0);
});
