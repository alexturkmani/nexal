import { serve } from "https://deno.land/std@0.208.0/http/server.ts";
import {
  callGemini, jsonResponse, errorResponse, corsHeaders, requireAccess, type Access,
} from "../_shared/helpers.ts";

serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders() });

  let access: Access | null = null;
  try {
    const body = await req.json();
    const type = body?.type;
    if (type !== "workout" && type !== "food" && type !== "weekly") {
      return errorResponse("Invalid assessment type", 400);
    }

    const gate = await requireAccess(req, type === "weekly" ? "weekly_checkin" : "assess");
    if ("error" in gate) return gate.error;
    access = gate.access;

    if (type === "workout") {
      const summary = (body.logs || []).map((log: any) => ({
        date: log.date,
        exercises: (log.exercises || []).map((e: any) => ({
          name: e.exerciseName,
          sets: (e.sets || []).map((s: any) => `${s.weight}kg x ${s.reps} reps`).join(", "),
        })),
      }));

      const prompt = `Analyze the following workout logs and provide an assessment:\n\n${JSON.stringify(summary, null, 2)}\n\nReturn JSON:\n{\n  "overallScore": "good/moderate/needs_improvement",\n  "consistency": "description",\n  "progressiveOverload": "analysis",\n  "weakPoints": ["list"],\n  "recommendations": ["list"],\n  "summary": "One paragraph"\n}`;
      const result = await callGemini(prompt, "You are an expert fitness coach analyzing workout performance. Provide honest, actionable assessments. Always return valid JSON.");
      return jsonResponse(JSON.parse(result));
    }

    if (type === "food") {
      const { productName, macros, ratio } = body;
      const prompt = `A user scanned: ${productName}\nCalories: ${macros.calories}, Protein: ${macros.protein}g, Carbs: ${macros.carbs}g, Fats: ${macros.fats}g\nProtein-to-calorie ratio: ${ratio.toFixed(1)}g per 100 cal (${ratio >= 10 ? "good" : ratio >= 5 ? "moderate" : "poor"})\n\n${ratio < 10 ? "Suggest 3 better alternatives with higher protein-to-calorie ratios." : "Briefly confirm this is a good choice."}\n\nReturn JSON:\n{\n  "assessment": "Brief assessment",\n  "alternatives": [{"name": "Product", "typicalMacros": {"calories": 100, "protein": 20, "carbs": 5, "fats": 2}, "reason": "Why better"}]\n}`;
      const result = await callGemini(prompt, "You are a sports nutrition expert. Assess food products and suggest better alternatives. Always return valid JSON.");
      return jsonResponse(JSON.parse(result));
    }

    // Weekly check-in: last 7 days of training, food and weight, turned into next week's adjustments.
    const week = {
      goal: body.goal || "general fitness",
      workoutsPlanned: Number(body.workoutsPlanned) || 0,
      workouts: (body.workouts || []).slice(0, 14).map((log: any) => ({
        date: log.date,
        day: log.dayLabel,
        exercises: (log.exercises || []).slice(0, 10).map((e: any) => ({
          name: e.exerciseName,
          sets: (e.sets || []).slice(0, 8).map((s: any) => `${s.weight}kg x ${s.reps}`).join(", "),
        })),
      })),
      nutrition: {
        targetCalories: Number(body.nutrition?.targetCalories) || 0,
        targetProtein: Number(body.nutrition?.targetProtein) || 0,
        avgCalories: Number(body.nutrition?.avgCalories) || 0,
        avgProtein: Number(body.nutrition?.avgProtein) || 0,
        daysLogged: Number(body.nutrition?.daysLogged) || 0,
      },
      weights: (body.weights || []).slice(0, 30).map((w: any) => ({ date: w.date, kg: Number(w.weight) || 0 })),
    };

    const prompt = `Weekly check-in for a fitness app user. Data for the last 7 days:\n${JSON.stringify(week, null, 2)}\n\nWrite a short, encouraging but honest check-in. Base every point on the data; if a section has no data, say what to log next week instead of guessing.\n\nReturn JSON:\n{\n  "headline": "One short line, max 60 characters",\n  "score": <integer 0-100 for the week>,\n  "wins": ["1-3 short wins"],\n  "focus": ["1-3 short things to improve next week"],\n  "calorieAdjustment": <integer kcal change to the daily target, between -300 and 300, 0 if on track>,\n  "liftTargets": [{"exercise": "Name from the logs", "target": "e.g. 62.5kg x 8"}],\n  "summary": "2-3 sentences"\n}`;
    const result = await callGemini(prompt, "You are an expert fitness and nutrition coach writing a weekly check-in. Keep it specific and brief. Always return valid JSON.");
    const parsed = JSON.parse(result);
    const adjustment = Math.max(-300, Math.min(300, Math.round(Number(parsed.calorieAdjustment) || 0)));
    return jsonResponse({
      headline: String(parsed.headline || "Your week in review").slice(0, 80),
      score: Math.max(0, Math.min(100, Math.round(Number(parsed.score) || 0))),
      wins: Array.isArray(parsed.wins) ? parsed.wins.slice(0, 3).map(String) : [],
      focus: Array.isArray(parsed.focus) ? parsed.focus.slice(0, 3).map(String) : [],
      calorieAdjustment: adjustment,
      liftTargets: Array.isArray(parsed.liftTargets)
        ? parsed.liftTargets.slice(0, 5).map((t: any) => ({ exercise: String(t.exercise || ""), target: String(t.target || "") }))
        : [],
      summary: String(parsed.summary || ""),
    });
  } catch (error: any) {
    await access?.refund();
    console.error("Assessment error:", error);
    return errorResponse(error.message || "Failed to generate assessment");
  }
});
