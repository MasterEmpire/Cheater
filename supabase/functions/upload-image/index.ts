import { serve } from "https://deno.land/std@0.177.0/http/server.ts"
import { createClient } from "https://esm.sh/@supabase/supabase-js@2"
import { encodeBase64 } from "https://deno.land/std@0.203.0/encoding/base64.ts"

const SUPABASE_URL = Deno.env.get('SUPABASE_URL') ?? '';
const SUPABASE_SERVICE_KEY = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY') ?? '';

// --- MODELS (RESTORED TO USER SPEC) ---
const PRIMARY_MODEL = "gemini-2.5-flash";
const FALLBACK_MODEL = "gemini-3-flash-preview";

const OCR_PROMPT_TEMPLATE = `
BATCH OCR & SYNTHESIS TASK:
You are provided with one or more images from a camera. 
Some images may be blurry, duplicates, or overlapping parts of the same page.

YOUR GOAL:
Create a single, perfectly ordered transcription of all unique questions found.
1. Ignore blurry content if a sharper version of the same question exists in another image.
2. Do NOT duplicate questions.
3. Identify the question type: mc (multiple choice), tf (true/false), fill (blanks), ma (matching), sa (short answer), wo (workout/math).

OUTPUT FORMAT:
Return a JSON object with a single array "questions".
Each question must have: "number", "type", "question_text", and "options" (if applicable).
`;

const SOLVER_PROMPT_TEMPLATE = (friendlyText: string) => `
EXAM SOLVER (TTS MODE).
You are an expert tutor. Solve the following questions for a student who will listen via audio.

INPUT QUESTIONS:
${friendlyText}

STRICT OUTPUT RULES:
1. NATURAL LANGUAGE: Use words, not symbols. Say "plus" instead of "+", "divided by" instead of "/", and "x squared" instead of "x²".
2. NON-WORKOUT (mc, tf, fill, ma, sa): Provide ONLY the direct answer. Steps MUST be empty [].
3. WORKOUT (wo): Provide a "steps" array. Format: "Step X: [Action] [write: [text to put on paper]]".
4. JSON ONLY: Never break the schema.

SCHEMA: 
{ "solutions": [ { "number": "string", "type": "string", "answer": "string", "steps": ["string"] } ] }
`;

// --- UTILS ---

function extractJson(raw: string): string {
  const match = raw.match(/\`\`\`json\s?([\s\S]*?)\s?\`\`\`/) || raw.match(/\`\`\`\s?([\s\S]*?)\s?\`\`\`/);
  return (match ? match[1].trim() : raw.trim());
}

function formatTranscriptionForAI(transcription: any): string {
  const qs = transcription?.questions || transcription;
  if (!Array.isArray(qs)) return "[Error formatting questions]";
  return qs.map((q: any) => `ID: ${q.number} | TYPE: ${q.type} | Q: ${q.question_text} ${q.options ? '| OPTS: ' + q.options.join(', ') : ''}`).join('\n');
}

serve(async (req) => {
  const requestId = Math.random().toString(36).substring(7).toUpperCase();
  if (req.method === 'OPTIONS') return new Response('ok');

  try {
    const supabase = createClient(SUPABASE_URL, SUPABASE_SERVICE_KEY);
    const contentType = req.headers.get("content-type") || "";

    // --- SOLVER STAGE (Webhook/JSON) ---
    if (contentType.includes("application/json")) {
      const payload = await req.json();
      const record = payload.record || payload;
      if (record.status !== 'transcribed') return new Response(JSON.stringify({ skipped: true }));

      const friendlyText = formatTranscriptionForAI(record.transcription);
      const geminiKey = await getGeminiKey(supabase);
      const solutionRaw = await callGeminiApi(PRIMARY_MODEL, geminiKey, SOLVER_PROMPT_TEMPLATE(friendlyText));
      
      const { error } = await supabase.from('processed_images')
        .update({ solution_json: JSON.parse(extractJson(solutionRaw)), status: 'completed' })
        .eq('id', record.id);

      if (error) throw error;
      return new Response(JSON.stringify({ success: true }));
    }

    // --- BATCH OCR STAGE (Image Upload) ---
    const formData = await req.formData();
    const files = formData.getAll('file') as File[];
    if (files.length === 0) throw new Error("No files uploaded");

    const geminiParts: any[] = [{ text: OCR_PROMPT_TEMPLATE }];
    for (const file of files) {
      const b64 = encodeBase64(await file.arrayBuffer());
      geminiParts.push({ inline_data: { mime_type: file.type, data: b64 } });
      supabase.storage.from('images').upload(`${Date.now()}_${file.name}`, file);
    }

    const geminiKey = await getGeminiKey(supabase);
    const ocrRaw = await callGeminiApi(PRIMARY_MODEL, geminiKey, null, geminiParts);
    const ocrJson = JSON.parse(extractJson(ocrRaw));

    const { data: row, error: dbError } = await supabase.from('processed_images').insert({
        transcription: ocrJson,
        status: 'transcribed'
    }).select().single();

    if (dbError) throw dbError;
    return new Response(JSON.stringify({ success: true, id: row.id }));

  } catch (err) {
    console.error(`[${requestId}] ERROR:`, err.message);
    return new Response(JSON.stringify({ error: err.message }), { status: 500 });
  }
});

// --- AI FUNCTIONS (RESTORED) ---

async function getGeminiKey(supabase: any) {
  const { data, error } = await supabase.from('api_keys').select('api_key').eq('service', 'gemini').eq('is_active', true).limit(1).single();
  if (error || !data) throw new Error("No active Gemini key");
  return data.api_key;
}

async function callGeminiApi(model: string, key: string, prompt: string | null, parts?: any[]): Promise<string> {
  const url = `https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent?key=${key}`;
  const payloadParts = parts || [{ text: prompt }];
  
  const res = await fetch(url, {
    method: 'POST',
    body: JSON.stringify({ 
      contents: [{ parts: payloadParts }], 
      generationConfig: { response_mime_type: "application/json" } 
    })
  });

  if (res.status === 429 && model !== FALLBACK_MODEL) {
    console.warn(`[QUOTA] ${model} exhausted. Retrying with ${FALLBACK_MODEL}...`);
    return callGeminiApi(FALLBACK_MODEL, key, prompt, parts);
  }

  const data = await res.json();
  if (!data.candidates || !data.candidates[0]) throw new Error(data.error?.message || "Empty Gemini response");
  
  // Stitch all parts together if the model returns the response in segments
  const parts = data.candidates[0].content.parts;
  if (!parts || parts.length === 0) throw new Error("No parts found in Gemini response");
  
  return parts.map((p: any) => p.text || "").join('');
}
