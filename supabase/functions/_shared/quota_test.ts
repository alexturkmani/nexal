// Run: deno test supabase/functions/_shared/quota_test.ts
import { assert, assertEquals } from "https://deno.land/std@0.208.0/assert/mod.ts";
import {
  FREE_FEATURES, FREE_LIFETIME_CREDITS, PREMIUM_LIMITS, premiumLimitMessage, windowStart,
} from "./quota.ts";

Deno.test("day window is the UTC date", () => {
  assertEquals(windowStart("day", new Date("2026-11-22T23:30:00Z")), "2026-11-22");
});

Deno.test("week window starts on Monday", () => {
  // 22 Nov 2026 is a Sunday; its week started Monday 16 Nov.
  assertEquals(windowStart("week", new Date("2026-11-22T10:00:00Z")), "2026-11-16");
  assertEquals(windowStart("week", new Date("2026-11-16T00:00:00Z")), "2026-11-16");
  assertEquals(windowStart("week", new Date("2026-11-23T08:00:00Z")), "2026-11-23");
});

Deno.test("free accounts get 3 credits and no Premium-only features", () => {
  assertEquals(FREE_LIFETIME_CREDITS, 3);
  assert(!FREE_FEATURES.has("weekly_checkin"));
  assert(!FREE_FEATURES.has("assess"));
  assert(FREE_FEATURES.has("photo_meal"));
  assert(FREE_FEATURES.has("barcode"));
});

Deno.test("every feature has a positive Premium cap", () => {
  for (const [feature, { limit }] of Object.entries(PREMIUM_LIMITS)) {
    assert(limit > 0, `${feature} has no cap`);
  }
});

Deno.test("limit messages say when the cap resets", () => {
  assert(premiumLimitMessage("workout").includes("Monday"));
  assert(premiumLimitMessage("meal").includes("tomorrow"));
});
