// Who may use which AI feature, and how often. Pure, so it can be unit tested.

export type Feature =
  | "workout"
  | "meal"
  | "meal_swap"
  | "food_lookup"
  | "barcode"
  | "photo_meal"
  | "exercise_tips"
  | "assess"
  | "weekly_checkin";

/** Free accounts: this many AI actions for life, shared across FREE_FEATURES. */
export const FREE_LIFETIME_CREDITS = 3;
export const FREE_FEATURE_KEY = "free";
export const LIFETIME_WINDOW = "2000-01-01";

/** Features a free credit can pay for. The rest are Premium only. */
export const FREE_FEATURES: ReadonlySet<Feature> = new Set<Feature>([
  "workout",
  "meal",
  "meal_swap",
  "food_lookup",
  "barcode",
  "photo_meal",
  "exercise_tips",
]);

type Window = "day" | "week";

/** Fair-use caps for Premium. Generous for real use, they stop scripted abuse. */
export const PREMIUM_LIMITS: Record<Feature, { limit: number; window: Window }> = {
  workout: { limit: 3, window: "week" },
  meal: { limit: 5, window: "day" },
  meal_swap: { limit: 30, window: "day" },
  food_lookup: { limit: 150, window: "day" },
  barcode: { limit: 100, window: "day" },
  photo_meal: { limit: 30, window: "day" },
  exercise_tips: { limit: 20, window: "day" },
  assess: { limit: 60, window: "day" },
  weekly_checkin: { limit: 3, window: "week" },
};

const FEATURE_NAMES: Record<Feature, string> = {
  workout: "workout plans",
  meal: "meal plans",
  meal_swap: "meal swaps",
  food_lookup: "food look-ups",
  barcode: "barcode scans",
  photo_meal: "photo meal logs",
  exercise_tips: "exercise tips",
  assess: "AI food checks",
  weekly_checkin: "weekly check-ins",
};

/** UTC date the quota window starts on: today, or this week's Monday. */
export function windowStart(window: Window, now: Date = new Date()): string {
  const d = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate()));
  if (window === "week") {
    const daysSinceMonday = (d.getUTCDay() + 6) % 7;
    d.setUTCDate(d.getUTCDate() - daysSinceMonday);
  }
  return d.toISOString().slice(0, 10);
}

export const FREE_CREDITS_USED_MESSAGE =
  "You've used your 3 free AI actions. Go Premium for unlimited AI plans, scans and photo logging.";

export const PREMIUM_ONLY_MESSAGE = "Premium subscription required";

export function premiumLimitMessage(feature: Feature): string {
  const { limit, window } = PREMIUM_LIMITS[feature];
  const when = window === "day" ? "tomorrow" : "on Monday";
  return `You've reached the fair-use limit of ${limit} ${FEATURE_NAMES[feature]} per ${window}. It resets ${when}.`;
}
