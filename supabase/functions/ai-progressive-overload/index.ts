// Retired. This function had no sign-in or Premium check, so anyone could use
// the Gemini key through it. The app no longer calls it; it now refuses all calls.
// Meal photos moved to ai-photo-meal, which enforces Premium and the free allowance.
Deno.serve(() =>
  new Response(JSON.stringify({ error: "This endpoint has been retired. Update Nexal from Google Play." }), {
    status: 410,
    headers: { "Content-Type": "application/json" },
  })
);
