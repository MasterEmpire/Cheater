import { serve } from "https://deno.land/std@0.168.0/http/server.ts"
import { createClient } from "https://esm.sh/@supabase/supabase-js@2"

serve(async (req) => {
  const requestId = Math.random().toString(36).substring(7).toUpperCase();
  const SUPABASE_URL = Deno.env.get('SUPABASE_URL')!;
  const SUPABASE_SERVICE_KEY = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')!;
  const GITHUB_PAT = Deno.env.get('GITHUB_PAT');
  const REPO_OWNER = "GetyeTek";
  const REPO_NAME = "IDE";

  if (req.method === 'OPTIONS') return new Response('ok');

  try {
    const supabase = createClient(SUPABASE_URL, SUPABASE_SERVICE_KEY);
    const payload = await req.json();

    if (payload.action === 'process_staged_images') {
      console.log(`[${requestId}] [DISPATCHER] Registering batch: ${payload.paths?.length} images`);
      
      const { data: record, error: insError } = await supabase
        .from('processed_images')
        .insert([{ status: 'processing' }])
        .select().single();
      if (insError) throw insError;

      // Trigger GitHub Action
      const ghRes = await fetch(`https://api.github.com/repos/${REPO_OWNER}/${REPO_NAME}/actions/workflows/ai_worker.yml/dispatches`, {
        method: 'POST',
        headers: {
          'Authorization': `Bearer ${GITHUB_PAT}`,
          'Accept': 'application/vnd.github+json',
          'X-GitHub-Api-Version': '2022-11-28'
        },
        body: JSON.stringify({
          ref: 'conduit-dev',
          inputs: {
            record_id: record.id,
            image_paths: JSON.stringify(payload.paths),
            request_id: requestId
          }
        })
      });

      if (!ghRes.ok) {
        const errText = await ghRes.text();
        console.error(`[${requestId}] GitHub Trigger Failed:`, errText);
        throw new Error("Worker dispatch failed");
      }

      return new Response(JSON.stringify({ id: record.id }));
    }

    return new Response("Action not supported by dispatcher", { status: 400 });
  } catch (err) {
    console.error(`[${requestId}] [FATAL]:`, err.message);
    return new Response(JSON.stringify({ error: err.message }), { status: 500 });
  }
});