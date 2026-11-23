import { serve } from "https://deno.land/std@0.208.0/http/server.ts";
import {
  callGemini, jsonResponse, errorResponse, corsHeaders, requireAccess, withCredits, type Access,
} from "../_shared/helpers.ts";

serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders() });

  let access: Access | null = null;
  try {

    const { exercises, goals } = await req.json();
    if (!exercises || exercises.length === 0) return errorResponse("No exercises provided", 400);
    const gate = await requireAccess(req, "exercise_tips");
    if ("error" in gate) return gate.error;
    access = gate.access;

    const exerciseSummary = exercises.map((ex: any) => ({
      name: ex.name,
      muscleGroup: ex.muscleGroup,
      sets: (ex.sets || []).map((s: any) => `${s.weight}kg x ${s.reps} reps`),
    }));

    const prompt = `Analyze each exercise and provide improvement suggestions:\n\nUser goals: ${(goals || ["general_fitness"]).map((g: string) => g.replace("_", " ")).join(", ")}\n\nExercises:\n${JSON.stringify(exerciseSummary, null, 2)}\n\nFor EACH exercise provide assessment, 2-3 tips, and 2-3 alternatives.\n\nReturn JSON:\n{\n  "suggestions": [\n    {\n      "exerciseName": "Name",\n      "assessment": "Brief assessment",\n      "improvementTips": ["tip 1", "tip 2"],\n      "alternatives": [{"name": "Alt Exercise", "reason": "Why good alternative"}]\n    }\n  ]\n}`;
    const systemPrompt = "You are an expert strength coach. Analyze exercises and provide actionable tips and alternative suggestions. Always return valid JSON only.";
    const result = await callGemini(prompt, systemPrompt);
    return jsonResponse(withCredits(JSON.parse(result), access));
  } catch (error: any) {
    await access?.refund();
    console.error("Exercise suggestion error:", error);
    return errorResponse(error.message || "Failed to generate suggestions");
  }
});
