import { createClient } from "https://esm.sh/@supabase/supabase-js@2";
import {
  type Feature, FREE_CREDITS_USED_MESSAGE, FREE_FEATURE_KEY, FREE_FEATURES, FREE_LIFETIME_CREDITS,
  LIFETIME_WINDOW, PREMIUM_LIMITS, PREMIUM_ONLY_MESSAGE, premiumLimitMessage, windowStart,
} from "./quota.ts";

// Shared Gemini AI helper for all AI Edge Functions
const GEMINI_API_KEY = Deno.env.get("GEMINI_API_KEY") ?? "";
const MODELS = ["gemini-2.5-flash", "gemini-2.0-flash", "gemini-1.5-flash"];

function cleanJsonResponse(text: string): string {
  let cleaned = text.trim();
  if (cleaned.startsWith("```json")) cleaned = cleaned.slice(7);
  else if (cleaned.startsWith("```")) cleaned = cleaned.slice(3);
  if (cleaned.endsWith("```")) cleaned = cleaned.slice(0, -3);
  return cleaned.trim();
}

/** Normalize Android UPPER_SNAKE / mixed-case enums to lowercase_snake_case */
export function normalizeEnum(value: unknown, fallback = ""): string {
  if (value == null) return fallback;
  return String(value).trim().toLowerCase().replace(/\s+/g, "_");
}

export function normalizeProfile(raw: Record<string, unknown> | null | undefined) {
  const profile = raw ?? {};
  const goals = Array.isArray(profile.fitnessGoals)
    ? profile.fitnessGoals.map((g) => normalizeEnum(g, "general_fitness"))
    : ["general_fitness"];

  return {
    ...profile,
    gender: normalizeEnum(profile.gender, "male"),
    activityLevel: normalizeEnum(profile.activityLevel, "moderately_active"),
    fitnessGoals: goals,
    trainingLocation: normalizeEnum(profile.trainingLocation, "gym"),
    liftingExperience: normalizeEnum(profile.liftingExperience, "beginner"),
    workoutStyle: normalizeEnum(profile.workoutStyle, "muscle_group"),
    weight: Number(profile.weight) || 0,
    height: Number(profile.height) || 0,
    age: Number(profile.age) || 0,
    targetWeight: Number(profile.targetWeight) || 0,
    intervalWeeks: Number(profile.intervalWeeks) || 6,
    gymDaysPerWeek: Number(profile.gymDaysPerWeek) || 3,
  };
}

/** One part of a Gemini request: text, or an inline image. */
export type GeminiPart = { text: string } | { inline_data: { mime_type: string; data: string } };

async function tryModel(
  model: string,
  parts: GeminiPart[],
  maxRetries: number,
): Promise<string> {
  for (let attempt = 0; attempt <= maxRetries; attempt++) {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 60000);
    try {
      const response = await fetch(
        `https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent?key=${GEMINI_API_KEY}`,
        {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            contents: [
              { role: "user", parts },
            ],
            generationConfig: { temperature: 0.7, responseMimeType: "application/json" },
          }),
          signal: controller.signal,
        },
      );
      clearTimeout(timeout);

      if ((response.status === 429 || response.status === 503) && attempt < maxRetries) {
        await new Promise((r) => setTimeout(r, 5000 * (attempt + 1)));
        continue;
      }
      if (!response.ok) {
        const errorText = await response.text();
        throw new Error(`Gemini API error (${model}): ${response.status} - ${errorText}`);
      }

      const data = await response.json();
      const text = data.candidates?.[0]?.content?.parts?.[0]?.text;
      if (!text) throw new Error("No response from Gemini API");
      return cleanJsonResponse(text);
    } catch (error: any) {
      clearTimeout(timeout);
      if (error.name === "AbortError") throw new Error("Gemini API request timed out");
      if (attempt === maxRetries) throw error;
    }
  }
  throw new Error(`Failed after retries with ${model}`);
}

export function callGemini(
  prompt: string,
  systemPrompt: string,
  maxRetries = 3,
): Promise<string> {
  return callGeminiParts([{ text: `${systemPrompt}\n\n${prompt}` }], maxRetries);
}

/** Like callGemini, but with several parts, e.g. an image plus instructions. */
export async function callGeminiParts(parts: GeminiPart[], maxRetries = 3): Promise<string> {
  if (!GEMINI_API_KEY) {
    throw new Error(
      "AI is not configured. Set the GEMINI_API_KEY secret in Supabase and redeploy functions.",
    );
  }

  let lastError: Error | null = null;
  for (const model of MODELS) {
    try {
      return await tryModel(model, parts, maxRetries);
    } catch (error: any) {
      console.warn(`Model ${model} failed: ${error.message}`);
      lastError = error;
    }
  }
  throw new Error(
    lastError?.message?.includes("API error")
      ? "AI is temporarily unavailable. Please try again in a minute."
      : (lastError?.message || "AI is temporarily unavailable. Please try again in a minute."),
  );
}

export function generateId(): string {
  return crypto.randomUUID().replace(/-/g, "").slice(0, 24);
}

export function corsHeaders() {
  return {
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  };
}

export function jsonResponse(data: unknown, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { ...corsHeaders(), "Content-Type": "application/json" },
  });
}

export function errorResponse(message: string, status = 500) {
  return jsonResponse({ error: message }, status);
}

/** What the caller may do, once requireAccess has let them through. */
export type Access = {
  userId: string;
  isPremium: boolean;
  /** Free AI actions left after this one; null for Premium. */
  freeCreditsLeft: number | null;
  /** Give the unit back. Call it when the AI request fails. */
  refund: () => Promise<void>;
};

/**
 * Authenticate the caller and take one unit of AI quota for `feature`.
 * Premium: per-feature fair-use caps. Free: 3 AI actions for life, shared
 * across the features in FREE_FEATURES; everything else needs Premium.
 *
 * Function gateway JWT verification stays disabled because Supabase Auth issues
 * ES256 tokens while the legacy gateway verifier rejects that algorithm. The
 * Auth API performs the authoritative token validation here instead.
 */
export async function requireAccess(
  req: Request,
  feature: Feature,
): Promise<{ error: Response } | { access: Access }> {
  const authHeader = req.headers.get("authorization");
  const token = authHeader?.match(/^Bearer\s+(.+)$/i)?.[1];
  if (!token) return { error: errorResponse("Unauthorized", 401) };

  const supabaseUrl = Deno.env.get("SUPABASE_URL");
  const anonKey = Deno.env.get("SUPABASE_ANON_KEY");
  const serviceRoleKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (!supabaseUrl || !anonKey || !serviceRoleKey) {
    console.error("AI authorization is missing Supabase environment variables");
    return { error: errorResponse("AI authorization is unavailable", 500) };
  }

  const authClient = createClient(supabaseUrl, anonKey, {
    global: { headers: { Authorization: `Bearer ${token}` } },
  });
  const { data: { user }, error: authError } = await authClient.auth.getUser(token);
  if (authError || !user) return { error: errorResponse("Unauthorized", 401) };

  const admin = createClient(supabaseUrl, serviceRoleKey);
  const { data: subscription, error: subscriptionError } = await admin
    .from("user_subscriptions")
    .select("status, expiry_time")
    .eq("user_id", user.id)
    .maybeSingle();

  if (subscriptionError) {
    console.error("Premium subscription lookup failed:", subscriptionError.message);
    return { error: errorResponse("Unable to verify Premium access", 500) };
  }

  const expiryTime = subscription?.expiry_time
    ? Date.parse(subscription.expiry_time)
    : Number.POSITIVE_INFINITY;
  const isPremium = subscription?.status === "active" && expiryTime > Date.now();

  if (!isPremium && !FREE_FEATURES.has(feature)) {
    return { error: jsonResponse({ error: PREMIUM_ONLY_MESSAGE, code: "premium_required" }, 403) };
  }

  const key = isPremium ? feature : FREE_FEATURE_KEY;
  const window = isPremium ? windowStart(PREMIUM_LIMITS[feature].window) : LIFETIME_WINDOW;
  const limit = isPremium ? PREMIUM_LIMITS[feature].limit : FREE_LIFETIME_CREDITS;

  const { data: rows, error: quotaError } = await admin.rpc("consume_ai_quota", {
    p_user: user.id, p_feature: key, p_window: window, p_limit: limit,
  });
  if (quotaError) {
    console.error("AI quota check failed:", quotaError.message);
    return { error: errorResponse("Unable to check your AI allowance", 500) };
  }
  const row = Array.isArray(rows) ? rows[0] : rows;
  if (!row?.allowed) {
    return {
      error: isPremium
        ? jsonResponse({ error: premiumLimitMessage(feature), code: "fair_use_limit" }, 429)
        : jsonResponse({ error: FREE_CREDITS_USED_MESSAGE, code: "free_credits_used", freeCreditsLeft: 0 }, 403),
    };
  }

  return {
    access: {
      userId: user.id,
      isPremium,
      freeCreditsLeft: isPremium ? null : Math.max(0, limit - Number(row.used)),
      refund: async () => {
        const { error } = await admin.rpc("refund_ai_quota", {
          p_user: user.id, p_feature: key, p_window: window,
        });
        if (error) console.error("AI quota refund failed:", error.message);
      },
    },
  };
}

/** Adds freeCreditsLeft to a response body for free accounts. */
export function withCredits<T extends Record<string, unknown>>(body: T, access: Access): T {
  return access.freeCreditsLeft == null ? body : { ...body, freeCreditsLeft: access.freeCreditsLeft };
}

// ─── Nutrition calculation helpers (ported from utils.ts) ────────────────────

interface Profile {
  weight: number;
  height: number;
  age: number;
  gender: string;
  activityLevel: string;
  fitnessGoals?: string[];
  intervalWeeks?: number;
  gymDaysPerWeek?: number;
  trainingLocation?: string;
  liftingExperience?: string;
  workoutStyle?: string;
}

export function calculateTDEE(profile: Profile): number {
  const gender = normalizeEnum(profile.gender, "male");
  const activity = normalizeEnum(profile.activityLevel, "moderately_active");
  const bmr =
    gender === "male"
      ? 10 * profile.weight + 6.25 * profile.height - 5 * profile.age + 5
      : 10 * profile.weight + 6.25 * profile.height - 5 * profile.age - 161;
  const multipliers: Record<string, number> = {
    sedentary: 1.2,
    lightly_active: 1.375,
    moderately_active: 1.55,
    very_active: 1.725,
    extremely_active: 1.9,
  };
  return Math.round(bmr * (multipliers[activity] || 1.55));
}

export function calculateMacroTargets(profile: Profile) {
  const tdee = calculateTDEE(profile);
  const goals = (profile.fitnessGoals || ["general_fitness"]).map((g) =>
    normalizeEnum(g, "general_fitness"),
  );
  const goalMacros: Record<string, { calAdjust: number; proteinRatio: number; fatRatio: number }> = {
    weight_loss: { calAdjust: -500, proteinRatio: 0.35, fatRatio: 0.25 },
    muscle_gain: { calAdjust: 300, proteinRatio: 0.3, fatRatio: 0.25 },
    strength: { calAdjust: 0, proteinRatio: 0.3, fatRatio: 0.3 },
    endurance: { calAdjust: 200, proteinRatio: 0.2, fatRatio: 0.25 },
    general_fitness: { calAdjust: 0, proteinRatio: 0.25, fatRatio: 0.3 },
  };
  let totalCalAdj = 0, totalPR = 0, totalFR = 0;
  const count = goals.length || 1;
  for (const goal of goals) {
    const g = goalMacros[goal] || goalMacros.general_fitness;
    totalCalAdj += g.calAdjust;
    totalPR += g.proteinRatio;
    totalFR += g.fatRatio;
  }
  const calories = tdee + Math.round(totalCalAdj / count);
  const proteinRatio = totalPR / count;
  const fatRatio = totalFR / count;
  const protein = Math.round((calories * proteinRatio) / 4);
  const fats = Math.round((calories * fatRatio) / 9);
  const carbs = Math.round((calories - protein * 4 - fats * 9) / 4);
  return { calories: Math.round(calories), protein, carbs, fats };
}

export function calculateDailyWaterIntake(profile: Profile): number {
  let waterMl = profile.weight * 33;
  const activity = normalizeEnum(profile.activityLevel, "moderately_active");
  const activityMultipliers: Record<string, number> = {
    sedentary: 1.0, lightly_active: 1.1, moderately_active: 1.2,
    very_active: 1.35, extremely_active: 1.5,
  };
  waterMl *= activityMultipliers[activity] || 1.2;
  const goals = (profile.fitnessGoals || []).map((g) => normalizeEnum(g));
  if (goals.includes("weight_loss")) waterMl *= 1.1;
  if (goals.includes("muscle_gain")) waterMl *= 1.05;
  return Math.round(waterMl / 100) * 100;
}
