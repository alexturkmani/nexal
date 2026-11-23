import { serve } from "https://deno.land/std@0.208.0/http/server.ts";
import {
  callGeminiParts, jsonResponse, errorResponse, corsHeaders, requireAccess, withCredits, type Access,
} from "../_shared/helpers.ts";

// ~3 MB of JPEG once decoded. The app sends a 1280 px image at quality 80, far below this.
const MAX_BASE64_LENGTH = 4_000_000;
const ALLOWED_TYPES = new Set(["image/jpeg", "image/png", "image/webp"]);

const round = (n: unknown) => Math.max(0, Math.round(Number(n) || 0));

serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders() });

  let access: Access | null = null;
  try {
    const { imageBase64, mimeType, note } = await req.json();
    if (typeof imageBase64 !== "string" || imageBase64.length < 100) {
      return errorResponse("Photo is required", 400);
    }
    if (imageBase64.length > MAX_BASE64_LENGTH) return errorResponse("Photo is too large", 413);
    const type = ALLOWED_TYPES.has(mimeType) ? mimeType : "image/jpeg";

    const gate = await requireAccess(req, "photo_meal");
    if ("error" in gate) return gate.error;
    access = gate.access;

    const hint = typeof note === "string" && note.trim() ? `\nThe user adds: "${note.trim().slice(0, 200)}"` : "";
    const instructions = `Identify the food and drink in this photo and estimate the nutrition of what is shown.${hint}

Rules:
- List each distinct item with a realistic portion in grams or common units.
- Estimate from visible portion size; use standard USDA-style values.
- If the photo shows no food, return an empty "foods" list and say so in "notes".

Return JSON only:
{
  "mealName": "Short name for the meal",
  "foods": [{"name": "Grilled chicken breast", "servingSize": "150 g", "macros": {"calories": 248, "protein": 46, "carbs": 0, "fats": 5}}],
  "confidence": "high" | "medium" | "low",
  "notes": "One short sentence, e.g. what was hard to judge"
}`;

    const result = await callGeminiParts([
      { text: "You are a precise sports nutritionist estimating meals from photos. Always return valid JSON only." },
      { inline_data: { mime_type: type, data: imageBase64 } },
      { text: instructions },
    ], 2);
    const parsed = JSON.parse(result);

    const foods = (Array.isArray(parsed.foods) ? parsed.foods : []).slice(0, 12).map((f: any) => ({
      name: String(f?.name || "Food").slice(0, 80),
      servingSize: String(f?.servingSize || "1 serving").slice(0, 40),
      macros: {
        calories: round(f?.macros?.calories),
        protein: round(f?.macros?.protein),
        carbs: round(f?.macros?.carbs),
        fats: round(f?.macros?.fats),
      },
    }));
    const total = foods.reduce(
      (t: any, f: any) => ({
        calories: t.calories + f.macros.calories,
        protein: t.protein + f.macros.protein,
        carbs: t.carbs + f.macros.carbs,
        fats: t.fats + f.macros.fats,
      }),
      { calories: 0, protein: 0, carbs: 0, fats: 0 },
    );

    // No food found: nothing useful was delivered, so don't charge for it.
    if (foods.length === 0) await access.refund();

    return jsonResponse(withCredits({
      mealName: String(parsed.mealName || "Meal").slice(0, 60),
      foods,
      totalMacros: total,
      confidence: ["high", "medium", "low"].includes(parsed.confidence) ? parsed.confidence : "medium",
      notes: String(parsed.notes || "").slice(0, 200),
    }, access));
  } catch (error: any) {
    await access?.refund();
    console.error("Photo meal error:", error);
    return errorResponse(error.message || "Couldn't read that photo");
  }
});
