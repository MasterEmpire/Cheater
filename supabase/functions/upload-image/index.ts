import { serve } from "https://deno.land/std@0.168.0/http/server.ts"
import { createClient } from "https://esm.sh/@supabase/supabase-js@2"

const SUPABASE_URL = "https://xvldfsmxskhemkslsbym.supabase.co"
const SUPABASE_SERVICE_ROLE_KEY = "YOUR_SERVICE_ROLE_KEY_HERE"

serve(async (req) => {
  try {
    const formData = await req.formData()
    const file = formData.get('file') as File

    if (!file) {
      return new Response(JSON.stringify({ error: 'No file uploaded' }), { status: 400 })
    }

    const supabase = createClient(SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY)
    const fileName = `${Date.now()}_${file.name}`

  PHONETIC RULES:
  1. NO SYMBOLS: Replace symbols with words (e.g., 'x squared', 'square root').
  2. STRICT NO-EXPLANATION: Do NOT provide reasons, justifications, or context for mc, tf, fill, ma, or sa. 
  3. FORMATTING:
     - For 'wo': Provide only solving steps. No intro/outro.
     - For 'mc': Provide ONLY the letter and the exact text (e.g., 'Option B, Mitochondria').
     - For 'tf/fill/sa': Provide ONLY the direct answer string. No 'The answer is...' or 'This is because...'
  4. LENGHT: Max 15 words for non-workout answers.

    if (error) throw error

    return new Response(JSON.stringify({ success: true, path: data.path }), { 
        headers: { "Content-Type": "application/json" } 
    })
  } catch (err) {
    return new Response(JSON.stringify({ error: err.message }), { status: 500 })
  }
})