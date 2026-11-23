import { serve } from "https://deno.land/std@0.208.0/http/server.ts";
import {
  jsonResponse, errorResponse, corsHeaders, requireAccess, withCredits, type Access,
} from "../_shared/helpers.ts";
import { selectNutritionBasis } from "./basis.ts";

serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders() });

  let access: Access | null = null;
  try {

    const url = new URL(req.url);
    let barcode = url.searchParams.get("barcode");

    // Android client POSTs JSON body { barcode }; also accept query for older callers
    if (!barcode && req.method !== "GET") {
      try {
        const body = await req.json();
        barcode = body?.barcode ? String(body.barcode) : null;
      } catch {
        // ignore empty/invalid body
      }
    }

    if (!barcode) return errorResponse("Barcode parameter is required", 400);
    const gate = await requireAccess(req, "barcode");
    if ("error" in gate) return gate.error;
    access = gate.access;

    const response = await fetch(
      `https://world.openfoodfacts.org/api/v2/product/${encodeURIComponent(barcode)}.json`,
    );
    // Not found isn't the user's fault, so it doesn't cost a credit.
    if (!response.ok) {
      await access.refund();
      return errorResponse("Product not found", 404);
    }

    const data = await response.json();
    if (data.status === 0 || !data.product) {
      await access.refund();
      return errorResponse("Product not found in database", 404);
    }

    const product = data.product;
    const { servingSize, macros, proteinCalorieRatio } = selectNutritionBasis(product);

    return jsonResponse(withCredits({
      barcode,
      name: product.product_name || "Unknown Product",
      brand: product.brands || undefined,
      servingSize,
      macros,
      proteinCalorieRatio,
      imageUrl: product.image_url || undefined,
    }, access));
  } catch (error: any) {
    await access?.refund();
    console.error("Nutrition lookup error:", error);
    return errorResponse(error.message || "Failed to look up product");
  }
});
