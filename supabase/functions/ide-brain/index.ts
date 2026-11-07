import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

// 1. Polyfill document (Must run before library loads)
if (typeof document === "undefined") {
  (globalThis as any).document = { currentScript: null };
}

// --- CONFIGURATION ---
const GITHUB_USER = "GetyeTek"; 
const ORG_NAME = "MasterEmpire";
const DEFAULT_REPO = "IDE"; 
const MAIN_BRANCH = "main";
const DEV_BRANCH = "conduit-dev";

const ANDROID_BOILERPLATE: Record<string, string> = {
  ".github/workflows/build.yml": `name: Build DNS Guard v2.0-STABLE\n\non: \n  workflow_dispatch:\n\njobs:\n  build:\n    runs-on: ubuntu-latest\n    steps:\n      - name: Checkout code\n        uses: actions/checkout@v4\n\n      - name: Set up JDK 17\n        uses: actions/setup-java@v4\n        with:\n          java-version: '17'\n          distribution: 'temurin'\n\n      - name: Setup Gradle\n        uses: gradle/actions/setup-gradle@v3\n\n      - name: Build Release APK\n        run: gradle assembleRelease\n\n      - name: Sign APK\n        run: |\n          # 1. Decode Keystore\n          echo "\${{ secrets.SIGNING_KEY }}" | base64 -di > app/release.jks\n          \n          # 2. Locate SDK Tools\n          BUILD_TOOLS_DIR=$(ls -d \${ANDROID_SDK_ROOT}/build-tools/34.* | head -1)\n          \n          # 3. Zipalign\n          UNSIGNED_APK=$(find app/build/outputs/apk/release -name "*release-unsigned.apk" -o -name "*release.apk" | head -1)\n          \$BUILD_TOOLS_DIR/zipalign -v 4 "\$UNSIGNED_APK" app/build/outputs/apk/release/dns-guard-aligned.apk\n\n          # 4. Sign\n          \$BUILD_TOOLS_DIR/apksigner sign --ks app/release.jks \\\n            --ks-pass pass:"\${{ secrets.KEY_STORE_PASSWORD }}" \\\n            --ks-key-alias "\${{ secrets.ALIAS }}" \\\n            --key-pass pass:"\${{ secrets.KEY_PASSWORD }}" \\\n            --out app/build/outputs/apk/release/dns-guard-v2-stable.apk \\\n            app/build/outputs/apk/release/dns-guard-aligned.apk\n\n      - name: Upload Signed APK\n        uses: actions/upload-artifact@v4\n        with:\n          name: dns-guard-v2-stable\n          path: app/build/outputs/apk/release/dns-guard-v2-stable.apk`,
  "app/build.gradle": `plugins {\n    id 'com.android.application'\n    id 'org.jetbrains.kotlin.android'\n}\n\nandroid {\n    namespace 'com.conduit.app'\n    compileSdk 34\n\n    defaultConfig {\n        applicationId "com.conduit.app"\n        minSdk 24\n        targetSdk 34\n        versionCode 1\n        versionName "1.0"\n        \n        ndk {\n            abiFilters "arm64-v8a"\n        }\n    }\n\n    buildFeatures {\n        compose true\n    }\n    \n    composeOptions {\n        kotlinCompilerExtensionVersion '1.5.14'\n    }\n\n    buildTypes {\n        release {\n            minifyEnabled true\n            shrinkResources true\n            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'\n        }\n        debug {\n            minifyEnabled true\n            shrinkResources true\n            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'\n        }\n    }\n\n    compileOptions {\n        sourceCompatibility JavaVersion.VERSION_17\n        targetCompatibility JavaVersion.VERSION_17\n    }\n\n    kotlinOptions {\n        jvmTarget = '17'\n    }\n}\n\ndependencies {\n    implementation 'androidx.core:core-ktx:1.12.0'\n    implementation platform('androidx.compose:compose-bom:2024.06.00')\n    implementation 'androidx.compose.ui:ui'\n    implementation 'androidx.compose.material3:material3'\n    implementation 'androidx.activity:activity-compose:1.9.0'\n}`,
  "app/proguard-rules.pro": `# Add project specific ProGuard rules here.\n-keepattributes *Annotation*\n-keepattributes Signature`,
  "app/src/main/AndroidManifest.xml": `<?xml version="1.0" encoding="utf-8"?>\n<manifest xmlns:android="http://schemas.android.com/apk/res/android">\n    <uses-permission android:name="android.permission.INTERNET" />\n    \n    <application\n        android:allowBackup="false"\n        android:label="@string/app_name"\n        android:theme="@android:style/Theme.DeviceDefault.NoActionBar">\n        \n        <activity \n            android:name=".MainActivity"\n            android:exported="true">\n            <intent-filter>\n                <action android:name="android.intent.action.MAIN" />\n                <category android:name="android.intent.category.LAUNCHER" />\n            </intent-filter>\n        </activity>\n    </application>\n</manifest>`,
  "app/src/main/res/values/strings.xml": `<resources>\n    <string name="app_name">Conduit App</string>\n</resources>`,
  "app/src/main/res/xml/method.xml": `<?xml version="1.0" encoding="utf-8"?>\n<input-method xmlns:android="http://schemas.android.com/apk/res/android" />`,
  "app/src/main/res/xml/preferences.xml": `<?xml version="1.0" encoding="utf-8"?>\n<PreferenceScreen xmlns:android="http://schemas.android.com/apk/res/android">\n</PreferenceScreen>`,
  "build.gradle": `plugins {\n    id 'com.android.application' version '8.5.0' apply false\n    id 'org.jetbrains.kotlin.android' version '1.9.24' apply false\n}`,
  "gradle.properties": `org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8\nandroid.useAndroidX=true\nandroid.nonTransitiveRClass=true\nandroid.enableR8.fullMode=true`,
  "gradle/wrapper/gradle-wrapper.properties": `distributionBase=GRADLE_USER_HOME\ndistributionPath=wrapper/dists\ndistributionUrl=https\\://services.gradle.org/distributions/gradle-8.7-bin.zip\nzipStoreBase=GRADLE_USER_HOME\nzipStorePath=wrapper/dists`,
  "settings.gradle": `pluginManagement {\n    repositories {\n        google()\n        mavenCentral()\n        gradlePluginPortal()\n    }\n}\ndependencyResolutionManagement {\n    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)\n    repositories {\n        google()\n        mavenCentral()\n    }\n}\n\nrootProject.name = "ConduitApp"\ninclude ':app'`
};

const AUTO_DEPLOY_YML = `name: Manual Edge Function Deploy (No JWT)

on:
  workflow_dispatch:
    inputs:
      function_name:
        description: 'Name of the function to deploy'
        required: true
        type: string
      deploy_ticket:
        description: 'Conduit Security Ticket'
        required: true
        type: string
      conduit_url:
        description: 'Conduit Relay URL'
        required: true
        type: string

jobs:
  deploy:
    runs-on: ubuntu-latest
    if: github.ref == 'refs/heads/conduit-dev'

    steps:
      - name: Checkout code
        uses: actions/checkout@v4

      - name: Setup Supabase CLI
        uses: supabase/setup-cli@v1
        with:
          version: latest

      - name: Fetch Secrets from Conduit Relay
        run: |
          RESPONSE=$(curl -s -X POST \${{ github.event.inputs.conduit_url }} \
            -H "Content-Type: application/json" \
            -d '{"action": "claim_deploy_token", "ticket": "'\${{ github.event.inputs.deploy_ticket }}'"}')
          
          # Parse using jq for reliability
          TOKEN=$(echo "\$RESPONSE" | jq -r '.token')
          URL=$(echo "\$RESPONSE" | jq -r '.url')
          
          if [[ "\$TOKEN" == *"error"* ]] || [ -z "\$TOKEN" ] || [ "\$TOKEN" = "null" ]; then 
            echo "â Error: Failed to claim token from Conduit Relay."
            echo "Raw Response: \$RESPONSE"
            exit 1 
          fi
          
          echo "SUPABASE_ACCESS_TOKEN=\$TOKEN" >> \$GITHUB_ENV
          echo "SUPABASE_URL=\$URL" >> \$GITHUB_ENV
          
          REF=$(echo \$URL | sed -e 's|^[^/]*//||' -e 's|\\..*||')
          echo "PROJECT_ID=\$REF" >> \$GITHUB_ENV

      - name: Deploy Function without JWT
        run: |
          supabase functions deploy \${{ github.event.inputs.function_name }} --project-ref \${{ env.PROJECT_ID }} --no-verify-jwt
        env:
          SUPABASE_ACCESS_TOKEN: \${{ env.SUPABASE_ACCESS_TOKEN }}`;

// --- AI REGISTRY & ROUTING ---
interface AIProvider {
  name: string;
  baseUrl: string;
  model: string;
  apiKeyEnv: string;
  type: 'openai' | 'google';
}

interface AIConfig {
  providers: Record<string, AIProvider>;
  roles: {
    chat: string;
    architect: string;
    fast_fix: string;
    analyst: string;
  };
}

const DEFAULT_AI_CONFIG: AIConfig = {
  providers: {
    'default_main': {
      name: 'DeepSeek V3 (OpenRouter)',
      baseUrl: 'https://openrouter.ai/api/v1/chat/completions',
      model: 'deepseek/deepseek-chat',
      apiKeyEnv: 'Deepseek_API',
      type: 'openai'
    },
    'default_analyst': {
       name: 'Gemini Flash (Google)',
       baseUrl: 'https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-lite-latest:generateContent',
       model: '', // Model is in URL for Google Native
       apiKeyEnv: 'GEMINI_API_KEY',
       type: 'google'
    }
  },
  roles: {
    chat: 'default_main',
    architect: 'default_main',
    fast_fix: 'default_main',
    analyst: 'default_analyst'
  }
};

function resolveProvider(role: keyof AIConfig['roles'], config?: AIConfig): AIProvider | null {
  const effectiveConfig = config || DEFAULT_AI_CONFIG;
  // Fallback to default if roles missing in custom config
  const providerKey = effectiveConfig.roles?.[role] || DEFAULT_AI_CONFIG.roles[role];
  const provider = effectiveConfig.providers?.[providerKey] || DEFAULT_AI_CONFIG.providers[providerKey];
  return provider || null;
}

// Initialize Supabase
const supabase = createClient(
  Deno.env.get("SUPABASE_URL") ?? "",
  Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "" 
);

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
};

// --- UTILITIES ---
function base64ToText(str: string) {
    try {
        const binString = atob(str.replace(/\s/g, ''));
        const bytes = Uint8Array.from(binString, (m) => m.codePointAt(0)!);
        return new TextDecoder().decode(bytes);
    } catch (e) {
        return "";
    }
}

function textToBase64(str: string) {
    const bytes = new TextEncoder().encode(str);
    const binString = Array.from(bytes, (byte) => String.fromCharCode(byte)).join("");
    return btoa(binString);
}

function escapeRegExp(string: string) { 
    return string.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'); 
}

function isRecordInScope(record: any, scopePath: string): boolean {
    if (!scopePath || scopePath === "/" || scopePath === "") return true;
    // Checkpoints and Chat sessions usually don't have file paths, show them by default
    if (record.type === 'Checkpoint' || record.type === 'Chat') return true;

    const ops = record.ops || (record.data && record.data.ops);
    if (ops && Array.isArray(ops)) {
        // If there are no operations (System Event), don't filter it out
        if (ops.length === 0) return true;
        return ops.some((op: any) => 
            (op.file_path && op.file_path.startsWith(scopePath)) || 
            op.is_resolved_ef ||
            (op.file_path && op.file_path.includes("supabase/functions/"))
        );
    }

    const results = record.results || (record.data && record.data.results);
    if (results && Array.isArray(results)) {
        if (results.length === 0) return true;
        return results.some((res: any) => 
            (res.file && res.file.startsWith(scopePath)) ||
            (res.file && res.file.includes("supabase/functions/"))
        );
    }
    return true;
}

function getNumberedContent(content: string): string {
  if (!content) return "";
  const lines = content.split("\n");
  const pad = lines.length.toString().length;
  return lines.map((line, i) => `${(i + 1).toString().padStart(pad)} | ${line}`).join("\n");
}

// --- GITHUB API HELPERS ---
const getHeaders = () => ({
  "Authorization": `token ${Deno.env.get("GITHUB_PAT")}`,
  "Accept": "application/vnd.github.v3+json",
  "Content-Type": "application/json",
  "User-Agent": "Conduit-IDE-Agent",
});

async function githubFetch(repo: string, path: string, options: RequestInit = {}) {
  // If the repo string already contains a slash, it's a full path. Otherwise, we rely on the caller to have resolved the owner.
  const url = `https://api.github.com/repos/${repo}${path}`;
  
  const res = await fetch(url, { ...options, headers: { ...getHeaders(), ...options.headers } });
  
  if (!res.ok) {
      const text = await res.text();
      // Pass through the actual GitHub error details for debugging
      throw new Error(`GitHub API Error ${res.status} on ${url}: ${text}`);
  }
  if (res.status === 204) return {}; 
  return res.json();
}

async function getFileRaw(repo: string, filePath: string, ref: string) {
  try {
    const data = await githubFetch(repo, `/contents/${filePath}?ref=${ref}`);
    return { content: data.content, sha: data.sha }; 
  } catch (e: any) { 
    console.error(`[getFileRaw] Error on ${filePath}:`, e.message);
    return { content: "", sha: "" }; 
  }
}

async function updateFile(repo: string, filePath: string, content: string, sha: string, branch: string, message: string) {
  const encoded = textToBase64(content);
  const payload: any = { message, content: encoded, branch };
  if(sha) payload.sha = sha;
  
  const res = await githubFetch(repo, `/contents/${filePath}`, {
    method: "PUT",
    body: JSON.stringify(payload),
  });
  return res.commit.sha;
}

async function deleteFile(repo: string, filePath: string, sha: string, branch: string, message: string) {
  const payload = { message, sha, branch };
  const res = await githubFetch(repo, `/contents/${filePath}`, {
    method: "DELETE",
    body: JSON.stringify(payload),
  });
  return res.commit?.sha; 
}

async function dispatchWorkflow(repo: string, eventType: string, payload: any = {}) {
  await githubFetch(repo, `/dispatches`, {
    method: "POST",
    body: JSON.stringify({ event_type: eventType, client_payload: payload }),
  });
  return true;
}

async function triggerWorkflowFile(repo: string, workflowId: string, ref: string, inputs: any = {}) {
  // Debug log to console (viewable in Supabase Edge Function logs)
  console.log(`[GitHub Dispatch] Target: ${workflowId}, Ref: ${ref}, Inputs:`, JSON.stringify(inputs));
  
  await githubFetch(repo, `/actions/workflows/${workflowId}/dispatches`, {
    method: "POST",
    body: JSON.stringify({ ref: ref, inputs: inputs }),
  });
  return true;
}

async function ensureBranchExists(repo: string) {
  // 1. Check if DEV_BRANCH already exists
  try { 
      await githubFetch(repo, `/branches/${DEV_BRANCH}`); 
      return; 
  } catch (e) { /* Branch doesn't exist, proceed to create it */ }

  let baseSha = "";
  
  // 2. Try to find existing 'main' or 'master'
  try {
      const main = await githubFetch(repo, `/git/ref/heads/${MAIN_BRANCH}`);
      baseSha = main.object.sha;
  } catch (e) {
      try {
          // Fallback: Check for 'master' just in case
          const master = await githubFetch(repo, `/git/ref/heads/master`);
          baseSha = master.object.sha;
      } catch (e2) {
          // 3. REPO IS EMPTY (No main, no master)
          // We must create the "Root Commit" by creating a README
          console.log("Repo appears empty. Initializing with README.md...");
          try {
              // Create README directly on MAIN to initialize the repo history
              await updateFile(repo, "README.md", "# Project Initialized by Conduit", "", MAIN_BRANCH, "Initial commit");
              
              // Now that the commit exists, get its SHA
              const newMain = await githubFetch(repo, `/git/ref/heads/${MAIN_BRANCH}`);
              baseSha = newMain.object.sha;
          } catch (e3: any) {
              console.error("Failed to auto-initialize repo:", e3);
              throw new Error("Repository is empty and auto-initialization failed. Please create a README.md manually on GitHub.");
          }
      }
  }

  // 4. Create the DEV_BRANCH
  if (baseSha) {
      try {
          await githubFetch(repo, `/git/refs`, { 
              method: "POST", 
              body: JSON.stringify({ ref: `refs/heads/${DEV_BRANCH}`, sha: baseSha }) 
          });
      } catch (e:any) {
          console.error("Error creating dev branch refs:", e);
      }
  }
}

// --- AI CORE SERVICES ---

async function genericRequestAI(role: keyof AIConfig['roles'], messages: any[], config?: AIConfig, tools?: any[], responseFormat?: any): Promise<any> {
  const provider = resolveProvider(role, config);
  if (!provider) throw new Error(`No provider found for role: ${role}`);

  // AI Trace Summary (Lightweight)
  console.log(`[AI_CALL] Role: ${role} | Provider: ${provider.name} | Model: ${provider.model}`);

  // 2. Persistent DB Log
  // We use a try-catch to ensure logging failure doesn't block the actual AI request
  try {
      await supabase.from('conduit_logs').insert({
          repo_name: 'DEBUG_TRACE', // Utility function doesn't know repo, using placeholder
          type: 'ai_prompt_dump',
          data: { 
              role, 
              provider: provider.name, 
              model: provider.model, 
              timestamp: new Date().toISOString(),
              messages_preview: messages 
          }
      });
  } catch (e) { console.error("Background log failed:", e); }
  
  // Advanced Rotation Logic: Fetch Least-Recently-Used key from 'api_keys' table
  const serviceMap: Record<string, string> = { 
      'Deepseek_API': 'deepseek', 
      'GEMINI_API_KEY': 'gemini', 
      'GROQ_API_KEY': 'groq' 
  };
  const serviceName = serviceMap[provider.apiKeyEnv];
  let apiKey = "";

  if (serviceName) {
      const { data: keyRow } = await supabase
          .from('api_keys')
          .select('id, api_key')
          .eq('service', serviceName)
          .eq('is_active', true)
          .or(`cooldown_until.is.null,cooldown_until.lt.${new Date().toISOString()}`)
          .order('last_used_at', { ascending: true, nullsFirst: true })
          .limit(1)
          .maybeSingle();

      if (keyRow) {
          apiKey = keyRow.api_key;
          // Mark as used immediately to move it to the end of the rotation queue
          await supabase.from('api_keys').update({ last_used_at: new Date().toISOString() }).eq('id', keyRow.id);
      }
  }

  // Fallback to Env Var if no key found in DB or service not mapped
  if (!apiKey) {
      apiKey = Deno.env.get(provider.apiKeyEnv) || "";
      if (!apiKey) {
          if (provider.apiKeyEnv && provider.apiKeyEnv.length > 10) apiKey = provider.apiKeyEnv;
          else throw new Error(`Missing API Key: No active keys for '${serviceName || provider.apiKeyEnv}' in DB or Env`);
      }
  }

  // ADAPTER: Google Native
  if (provider.type === 'google') {
    const promptText = messages.map(m => `[${m.role.toUpperCase()}]: ${m.content}`).join('\n');
          const url = `${provider.baseUrl}?key=${apiKey}`;
    
    const payload: any = { 
        contents: [{ parts: [{ text: promptText }] }]
    };

    // ADAPTER: Google Strict Function Calling
    if (tools && tools.length > 0 && messages[0].content.includes('STRICT')) {
        payload.tool_config = {
            function_calling_config: {
                mode: "ANY",
                allowed_function_names: [tools[0].function.name]
            }
        };
    }

    // MAP OPENAI TOOLS TO GOOGLE FUNCTION DECLARATIONS
    if (tools && tools.length > 0) {
        const mapType = (t: any) => {
            // Flatten union types ["integer", "null"] -> "INTEGER"
            const rawType = Array.isArray(t) ? t[0] : t;
            if (rawType === "integer") return "INTEGER";
            if (rawType === "string") return "STRING";
            if (rawType === "boolean") return "BOOLEAN";
            if (rawType === "number") return "NUMBER";
            if (rawType === "object") return "OBJECT";
            if (rawType === "array") return "ARRAY";
            return "STRING"; // Fallback
        };

        const transformSchema = (schema: any): any => {
            const newSchema: any = { type: mapType(schema.type) };
            if (schema.properties) {
                newSchema.properties = {};
                for (const key in schema.properties) {
                    newSchema.properties[key] = transformSchema(schema.properties[key]);
                }
            }
            if (schema.items) {
                newSchema.items = transformSchema(schema.items);
            }
            if (schema.required) newSchema.required = schema.required;
            return newSchema;
        };

        payload.tools = [{
            function_declarations: tools.map((t: any) => ({
                name: t.function.name,
                description: t.function.description,
                parameters: transformSchema(t.function.parameters)
            }))
        }];
    }
    
    const res = await fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload) });
    const data = await res.json();
    
    if (data.error) throw new Error(`Google AI Error: ${data.error.message || JSON.stringify(data.error)}`);
    
    console.log(`\n=== [AI RESPONSE RECEIVED] Provider: ${provider.name} (Google) ===`);
    
    const parts = data.candidates?.[0]?.content?.parts || [];
    let combinedContent = "";
    let tool_calls: any[] = [];

    // Loop through all parts to catch both text and function calls
    for (const p of parts) {
        if (p.text) combinedContent += p.text;
        if (p.functionCall) {
            tool_calls.push({
                function: {
                    name: p.functionCall.name,
                    arguments: JSON.stringify(p.functionCall.args)
                }
            });
        }
    }

    return { 
      raw: data, 
      content: combinedContent,
      tool_calls: tool_calls.length > 0 ? tool_calls : undefined
    };
  }

  // ADAPTER: OpenAI Compatible (Standard)
  const body: any = {
    model: provider.model,
    messages: messages,
  };
  if (tools) body.tools = tools;
  // If we have tools, we want to allow the caller to specify if it's mandatory
  if (tools) body.tool_choice = (tools.length > 0 && messages[0].content.includes('STRICT')) ? { type: "function", function: { name: tools[0].function.name } } : "auto";
  if (responseFormat) body.response_format = responseFormat;

  const res = await fetch(provider.baseUrl, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${apiKey}` },
    body: JSON.stringify(body)
  });
  
  const data = await res.json();
  // AI Response Summary
  if (data.error) console.error(`[AI_ERR] Provider: ${provider.name}`, data.error);
  
  if (!res.ok || data.error) {
      const errMsg = data.error ? (data.error.message || JSON.stringify(data.error)) : `HTTP ${res.status} ${res.statusText}`;
      throw new Error(`Provider Error (${provider.name}): ${errMsg}`);
  }

  return {
    raw: data,
    content: data.choices?.[0]?.message?.content || "",
    tool_calls: data.choices?.[0]?.message?.tool_calls
  };
}

// 1. Syntax Repair
async function repairSyntaxWithAI(codeBlock: string, errorMessage: string, config?: AIConfig): Promise<{ fixed_code: string | null; explanation: string }> {
    console.log("--- AI REPAIR SYNTAX START ---");
    const systemInstruction = `You are a JS/HTML/CSS Syntax Repair Agent. Fix the syntax error without changing logic. Call the 'provide_fixed_code' function.`;
    const tools = [{ 
        type: "function", 
        function: { 
            name: "provide_fixed_code", 
            description: "Return corrected code", 
            parameters: { 
                type: "object", 
                properties: { fixed_code: { type: "string" }, explanation: { type: "string" } }, 
                required: ["fixed_code", "explanation"] 
            } 
        } 
    }];

    try {
        console.log(`[SyntaxRepair] Prompting AI. Code Len: ${codeBlock.length}, Error: ${errorMessage}`);
        
        const messages = [
            { role: "system", content: systemInstruction },
            { role: "user", content: "Code:\n" + codeBlock + "\nError:\n" + errorMessage }
        ];

        const result = await genericRequestAI('fast_fix', messages, config, tools);
        const toolCall = result.tool_calls?.[0];
        if (toolCall && toolCall.function.name === "provide_fixed_code") {
             const args = JSON.parse(toolCall.function.arguments);
             return { fixed_code: args.fixed_code, explanation: args.explanation };
        }
        return { fixed_code: null, explanation: "AI failed to call tool." };
    } catch (e: any) { console.error("[SyntaxRepair] Error:", e); return { fixed_code: null, explanation: e.message }; }
}

// 2. Self-Healing (Healer)
async function consultAI(fileContent: string, failedOp: any, failReason: string, config?: AIConfig): Promise<{ fixedOp: any | null; reason: string; score: number }> {
  console.log(`--- CONSULT AI (HEALER) START for ${failedOp?.action} ---`);
  
  const systemInstruction = `### ROLE: CONDUIT ADAPTIVE SELF-HEALING ENGINE (LEVEL 10)

### MISSION
You are the final line of defense for a code-patching engine. A developer's 'find_block' failed to match the file because of minor discrepancies (whitespace, line order, variable renaming, or partial code degradation). Your goal is to identify the EXACT coordinates where the patch should have applied.

### ALGORITHMIC FUZZY MATCHING PROTOCOL
1. WHITESPACE/INDENTATION: Completely ignore it. A match exists if the tokens and logic flow are identical.
2. QUOTE/SYNTAX AGNOSTICISM: Treat ' and " as identical. Treat trailing commas as optional.
3. CONTEXTUAL ANCHORING: If the target block is small (e.g., 1-2 lines), you MUST look at the surrounding lines in the 'File' context to ensure you aren't matching a duplicate symbol in a different function.
4. LOGICAL DRIFT: If the user's 'find_block' has 4 variables but the file has those 4 variables PLUS 1 new one in the middle, this is a MATCH. Your 'start_line' and 'end_line' must encompass the entire group.

### THE LAW OF COORDINATES
- The context provided to you contains line numbers followed by a ' | ' separator (e.g., " 25 | code").
- Your 'start_line' and 'end_line' MUST refer to these numbers.
- COORDINATE INTEGRITY: All coordinate fields are REQUIRED by the schema. If you are not using a specific field (e.g., you are doing a Range Replace and don't need 'anchor_line'), you MUST set the unused fields to 'null'.

### OUTPUT STRATEGY SELECTION
- [STRATEGY: RANGE] Use 'start_line' + 'end_line'. Best for 'replace_block' failures. Ensure the range covers the ENTIRE logical block you want to overwrite.
- [STRATEGY: ANCHOR] Use 'anchor_line'. Best for 'insert_after' or 'insert_before' failures where the anchor moved but is still a single line.
- [STRATEGY: STRING] Use 'new_anchor_text'. Only use this if you find a unique line that is 100% stable and wasn't provided by the user.

### SCORE RUBRIC (confidence_score)
- 100: Identical logic, only whitespace or quote differences.
- 90-95: Identical logic, but 1-2 variables were renamed or a trailing comma was added/removed.
- 75-85: The structure matches but the file has 1-2 extra lines of code mixed in (logical drift).
- 60: You found a possible location but it's risky (multiple similar spots).
- 0: No logical match exists.

### DATA INTEGRITY CONSTRAINTS
- 'confidence_score': MUST be a WHOLE INTEGER (e.g., 95). No floats (0.95 is ILLEGAL).
- 'explanation': Technical, concise, and definitive. Max 30 words. Identify the exact mismatch (e.g., "Variables reversed on lines 24-25").
- 'can_fix': If you are guessing with less than 60% confidence, set this to false.

### STRICT MODE
- DO NOT RESPOND WITH TEXT.
- ONLY CALL THE suggest_fix FUNCTION.
- SILENCE IS MANDATORY.`;

  const tools = [{ 
    type: "function",
    strict: true, 
    function: { 
        name: "suggest_fix", 
        description: "Provide the correct coordinates or strings to fix a failed patch operation.", 
        parameters: { 
            type: "object", 
            properties: { 
                can_fix: { 
                    type: "boolean",
                    description: "Whether you successfully located the intended code target."
                }, 
                confidence_score: { 
                    type: "integer", 
                    minimum: 0, 
                    maximum: 100, 
                    description: "MANDATORY: Whole integer 0-100. (e.g. 98). DECIMALS/FLOATS WILL BREAK THE SYSTEM."
                }, 
                explanation: { 
                    type: "string",
                    description: "Detailed technical reasoning for why the original match failed and why this new target is correct."
                }, 
                start_line: { 
                    type: ["integer", "null"], 
                    description: "The 1-based start line number. Required for Strategy: RANGE."
                }, 
                end_line: { 
                    type: ["integer", "null"], 
                    description: "The 1-based end line number. MUST BE PROVIDED if start_line is provided for a block."
                }, 
                anchor_line: { 
                    type: ["integer", "null"], 
                    description: "For insertion ops: The 1-based line number to act as the anchor."
                }, 
                new_anchor_text: { 
                    type: ["string", "null"], 
                    description: "A unique string that CURRENTLY EXISTS in the file. Do NOT put new code here."
                } 
            }, 
            required: ["can_fix", "explanation", "confidence_score", "start_line", "end_line", "anchor_line"] 
        } 
    } 
  }];

  try {
    const userPrompt = "File:\n" + getNumberedContent(fileContent) + "\nOp:\n" + JSON.stringify(failedOp) + "\nError:\n" + failReason;
    console.log("[Healer] Prompt Sent (Excluding File):", systemInstruction + "\nOp: " + JSON.stringify(failedOp) + "\nReason: " + failReason);
    
    const messages = [
        { role: "system", content: systemInstruction },
        { role: "user", content: userPrompt }
    ];
    
    const result = await genericRequestAI('fast_fix', messages, config, tools);
    console.log("ð¥ [HEALER RAW DEBUG]:", JSON.stringify(result, null, 2));
    
    let args: any = null;

    // 1. Try formal Tool Call first
    const toolCall = result.tool_calls?.[0];
    if (toolCall && toolCall.function.name === "suggest_fix") {
        try {
            args = JSON.parse(toolCall.function.arguments);
        } catch (e) { console.error("[Healer] Failed to parse tool arguments"); }
    }

    // 2. Robust Fallback: Search content for JSON block or raw JSON structure
    if (!args && result.content) {
        console.log("[Healer] Tool call missing, attempting regex extraction from content...");
        const jsonMatch = result.content.match(/```json\s*([\s\S]*?)\s*```/) || 
                          result.content.match(/\{\s*"can_fix"[\s\S]*?\}/);
        
        if (jsonMatch) {
            try {
                args = JSON.parse(jsonMatch[1] || jsonMatch[0]);
                console.log("[Healer] Successfully extracted JSON from conversational text.");
            } catch (e) { console.error("[Healer] Regex found JSON but it was malformed"); }
        }
    }

    if (!args) {
        const rawText = result.content || "AI returned no content and no tool calls.";
        return { fixedOp: null, reason: `AI Refusal: ${rawText.substring(0, 200)}`, score: 0 };
    }

    // DEFENSIVE CODING: Normalize score. AI sometimes returns 0.95 instead of 95.
    let score = args.confidence_score;
    if (score <= 1 && score > 0) score = score * 100;

    if (!args || !args.can_fix || score < 60) return { fixedOp: null, reason: args?.explanation || "Low confidence match", score: score || 0 };
    
    // COORDINATE SANITY CHECK
    const startLine = args.start_line !== null ? parseInt(String(args.start_line)) : null;
    const endLine = args.end_line !== null ? parseInt(String(args.end_line)) : null;
    const anchorLine = args.anchor_line !== null ? parseInt(String(args.anchor_line)) : null;

    const newOp = { ...failedOp, is_ai_fix: true };
    
    // Prioritize Range Replace for replace_block actions
    if (startLine && (failedOp.action === "replace_block" || !anchorLine)) {
        newOp.ai_strategy = "range_replace";
        newOp.start_line = startLine;
        if (!endLine && failedOp.find_block) {
            const originalLineCount = failedOp.find_block.split('\n').length;
            newOp.end_line = startLine + (originalLineCount - 1);
        } else {
            newOp.end_line = endLine || startLine;
        }
    } 
    else if (anchorLine) {
        newOp.ai_strategy = "line_insert";
        newOp.anchor_line = anchorLine;
    }
    else if (args.new_anchor_text) {
        // If AI gives a better string, swap it into the original operation
        if (newOp.action === "replace_block") newOp.find_block = args.new_anchor_text;
        else newOp.anchor = args.new_anchor_text;
        newOp.is_ai_fix = false; // Run it through standard logic with the new string
    }
    return { fixedOp: newOp, reason: args.explanation, score: args.confidence_score };
  } catch (e: any) { console.error("[Healer] Error:", e); return { fixedOp: null, reason: e.message, score: 0 }; }
}

// 3. Code Sanity Checker (Context-Aware)
async function checkCodeSanity(code: string, op: any, config?: AIConfig): Promise<{ sane: boolean; issues: string }> {
    console.log("--- SANITY CHECK START ---");
    
    const prompt = `
    I just performed a code modification. Check if it introduced any FATAL syntax errors.
    
    OPERATION PERFORMED:
    ${JSON.stringify(op, null, 2)}
    
    RESULTING FILE CONTENT:
    ${code.substring(0, 15000)}
    
    TASK:
    1. Focus specifically on the area where the operation occurred.
    2. Check for disturbed nesting, unclosed braces/tags, or invalid syntax caused by this specific change.
    3. Ignore pre-existing issues unrelated to this change.
    
    OUTPUT JSON ONLY: { "sane": boolean, "issues": "concise description of error" }
    `;

    try {
        console.log("[Sanity] Prompt Sent (Op Only):", JSON.stringify(op));
        
        const result = await genericRequestAI('fast_fix', [{ role: "user", content: prompt }], config, undefined, { type: "json_object" });
        console.log("[Sanity] Response:", JSON.stringify(result.raw));
        
        const text = result.content || "{}";
        const json = JSON.parse(text);
        return { sane: !!json.sane, issues: json.issues || "" };
    } catch (e) { console.error("[Sanity] Error:", e); return { sane: true, issues: "" }; }
}

// 4. STEP A: DETECTIVE (Analyze Logs & Identify Files)
async function analyzeLogsAndIdentifyFiles(logText: string, config?: AIConfig): Promise<{ summary: string, files: string[] }> {
    console.log("--- ANALYZE LOGS START ---");

    const prompt = `
    You are a CI/CD Build Failure Analyzer.
    
    TASK 1: Write a DETAILED technical summary of why the build failed.
            - Include specific error messages, line numbers, and stack trace details.
            - Explicitly explain the root cause.
    TASK 2: Identify the specific file path(s) in the repo causing the error.
    
    CRITICAL FORMAT REQUIREMENT:
    1. Write the summary first.
    2. END YOUR RESPONSE with a valid JSON array of file paths wrapped in tags.
    
    TEMPLATE FOR THE END OF RESPONSE:
    <<<FILES
    [
        "path/to/file1.ext",
        "path/to/file2.ext"
    ]
    FILES>>>

    LOGS:
    ${logText}
    `;

    try {
        console.log(`[Detective] Sending Log Analysis...`);

        // The universal adapter handles the Google/OpenAI specific formatting
        const result = await genericRequestAI('analyst', [{ role: "user", content: prompt }], config);
        const rawOutput = result.content || "";

        let summary = rawOutput;
        let files: string[] = [];

        // 1. Try Strict JSON Parsing
        const jsonMatch = rawOutput.match(/<<<FILES\s*([\s\S]*?)\s*FILES>>>/);
        
        if (jsonMatch && jsonMatch[1]) {
            try {
                files = JSON.parse(jsonMatch[1]);
                summary = rawOutput.replace(jsonMatch[0], "").trim();
            } catch (e) { console.error("Failed to parse file list JSON", e); }
        } 
        
        // 2. FALLBACK: If AI ignored JSON, Regex search the text for file paths
        if (files.length === 0) {
            console.log("[Detective] JSON not found, attempting regex extraction...");
            const pathRegex = /(?:[\w-]+\/)+[\w-]+\.[a-z]{2,4}/gi;
            const matches = rawOutput.match(pathRegex);
            if (matches) {
                // Deduplicate and filter
                files = [...new Set(matches)].filter(p => !p.includes("..."));
                console.log("[Detective] Recovered files via regex:", files);
            }
        }

        // Clean up paths (remove /home/runner/work/... if present)
        files = files.map(f => {
            const parts = f.split('/');
            const srcIndex = parts.indexOf('src');
            if (srcIndex > 0) return parts.slice(srcIndex - 1).join('/'); 
            return f;
        });

        return { summary, files };
    } catch (e) {
        console.error("[Detective] Gemini Error:", e);
        return { summary: "AI Analysis Failed", files: [] };
    }
}

// 5. STEP B: SURGEON (Generate Fix from Summary + Full Files)
async function generateFixFromFullContext(summary: string, contextFiles: any[], changeContext: string, config?: AIConfig): Promise<string> {
    console.log("--- GENERATE FIX (SURGEON) START ---");
    
    // Full Content - No Truncation as requested
    const filesStr = contextFiles.map((f:any) => 
        `FILE: ${f.path}\n${base64ToText(f.content)}` // <-- NO SUBSTRING()
    ).join("\n---\n");

    const prompt = `
    You are a Strict Code Patch Generator.
    The CI Build Failed.
    
    ERROR SUMMARY:
    ${summary}

    CONTEXT FILES (FULL CONTENT):
    ${filesStr}

    LAST COMMIT DIFF (Likely Source of Error):
    ${changeContext || "No change context available. Analyze the full file."}

    TASK:
    Generate a JSON Patch Array to fix the error.
    
    CRITICAL RULES:
    1. Every operation MUST include "file_path" and it must be one of the paths provided in the CONTEXT.
    2. Use "replace_block" or "insert_after".
    3. First operation MUST be a "comment" with a CONCISE, ONE-LINE summary of the fix (Max 100 chars).
    4. RETURN RAW JSON ARRAY ONLY. No markdown.

    SCHEMA REFERENCE:
    - REPLACE: { "file_path": "string", "action": "replace_block", "find_block": "EXACT_CODE_MATCH", "replace_with": "NEW_CODE" }
    - INSERT: { "file_path": "string", "action": "insert_after", "anchor": "EXACT_UNIQUE_LINE", "content": "NEW_CODE" }
    - COMMENT: { "action": "comment", "text": "SUMMARY_OF_CHANGES" } 
    `;

    try {
        console.log("[Surgeon] Prompt Prepared.");
        console.log("Error Summary:", summary);
        console.log("Diff Context:", changeContext);
        console.log("Files included:", contextFiles.map((c:any)=>c.path));

        const result = await genericRequestAI('architect', [{ role: "user", content: prompt }], config);
        console.log("[Surgeon] Response:", JSON.stringify(result.raw));
        
        const text = result.content || "[]";
        
        // Robust JSON Extraction using Regex
        const match = text.match(/```json\s*([\s\S]*?)\s*```/) || text.match(/```\s*([\s\S]*?)\s*```/);
        if (match) return match[1].trim();

        // Fallback: Try to find the first '[' and last ']' if no markdown blocks
        const start = text.indexOf('[');
        const end = text.lastIndexOf(']');
        if (start !== -1 && end !== -1 && end > start) {
            return text.substring(start, end + 1);
        }

        return "[]";
    } catch (e) { console.error("[Surgeon] Error:", e); return "[]"; }
}

// --- PATCHER ENGINE ---

// Helper: Finds code block ignoring whitespace, tabs, newlines, and quote styles
function findBlockRobust(originalContent: string, searchBlock: string): { start: number; end: number } | null {
  if (!searchBlock || !originalContent) return null;

  const createFingerprint = (str: string) => {
    let clean = "";
    const map: number[] = [];
    for (let i = 0; i < str.length; i++) {
      if (!/\s/.test(str[i])) {
        clean += str[i];
        map.push(i);
      }
    }
    return { clean, map };
  };

  const fileFP = createFingerprint(originalContent);
  const searchFP = createFingerprint(searchBlock);

  // 1. Strict Structural Match
  let matchIndex = fileFP.clean.indexOf(searchFP.clean);

  // 2. Quote-Agnostic Fallback (e.g. " vs ')
  if (matchIndex === -1) {
    const normalizeQuotes = (s: string) => s.replace(/['"`]/g, '"');
    matchIndex = normalizeQuotes(fileFP.clean).indexOf(normalizeQuotes(searchFP.clean));
  }

  if (matchIndex === -1) return null;

  const start = fileFP.map[matchIndex];
  const end = fileFP.map[matchIndex + searchFP.clean.length - 1] + 1;
  return { start, end };
}

function applyOperation(content: string, op: any) {
  const lines = content.split("\n");
  
  // Helper to find line by trimmed content
  const getLineIndex = (anchor: string) => {
      const cleanAnchor = anchor.trim();
      return lines.findIndex(l => l.trim() === cleanAnchor);
  };

  try {
    // --- AI HEALING ---
    if (op.is_ai_fix) {
        if (op.ai_strategy === "range_replace" && op.start_line && op.end_line) {
            lines.splice(op.start_line - 1, (op.end_line - op.start_line) + 1, op.replace_with || op.content || "");
                        const shortMsg = op.explanation ? (op.explanation.substring(0, 150) + "...") : "AI Fixed";
            return { newContent: lines.join("\n"), success: true, score: 95, message: `â¨ AI: ${shortMsg}` };
        }

        if (op.ai_strategy === "line_insert" && op.anchor_line) {
            const idx = op.anchor_line - 1;
            const payload = op.replace_with || op.content || "";
            if (op.action === "insert_after") {
                lines.splice(idx + 1, 0, payload);
            } else if (op.action === "insert_before") {
                lines.splice(idx, 0, payload);
            } else {
                lines[idx] = payload;
            }
            const shortMsg = op.explanation ? (op.explanation.substring(0, 150) + "...") : "AI Fixed";
            return { newContent: lines.join("\n"), success: true, score: 95, message: `â¨ AI: ${shortMsg}` };
        }
    }

    switch (op.action) {
      case "replace_block": {
        if (!op.find_block) return { newContent: content, success: false, score: 0, message: "Missing find_block" };
        
        // Strategy A: Exact Match
        if (content.includes(op.find_block)) {
            return { newContent: content.replace(op.find_block, op.replace_with || ""), success: true, score: 100, message: "Exact match" };
        }
        // Strategy B: Robust Match (Ignores whitespace/indentation)
        const match = findBlockRobust(content, op.find_block);
        if (match) {
            const before = content.substring(0, match.start);
            const after = content.substring(match.end);
            return { newContent: before + (op.replace_with || "") + after, success: true, score: 95, message: "Structure match" };
        }
        return { newContent: content, success: false, score: 0, message: "Block not found" };
      }

      case "insert_after": {
        // Strategy A: Exact Line
        let idx = lines.indexOf(op.anchor);
        // Strategy B: Trimmed Line
        if (idx === -1) idx = getLineIndex(op.anchor);
        
        if (idx !== -1) {
            lines.splice(idx + 1, 0, op.content);
            return { newContent: lines.join("\n"), success: true, score: 100, message: "Inserted after line" };
        }
        
        // Strategy C: Robust Block Anchor
        const match = findBlockRobust(content, op.anchor);
        if (match) {
             const before = content.substring(0, match.end);
             const after = content.substring(match.end);
             return { newContent: before + "\n" + op.content + after, success: true, score: 90, message: "Robust anchor match" };
        }
        return { newContent: content, success: false, score: 0, message: "Anchor not found" };
      }

      case "insert_before": {
        let idx = lines.indexOf(op.anchor);
        if (idx === -1) idx = getLineIndex(op.anchor);

        if (idx !== -1) {
            lines.splice(idx, 0, op.content);
            return { newContent: lines.join("\n"), success: true, score: 100, message: "Inserted before line" };
        }

        const match = findBlockRobust(content, op.anchor);
        if (match) {
             const before = content.substring(0, match.start);
             const after = content.substring(match.start);
             return { newContent: before + op.content + "\n" + after, success: true, score: 90, message: "Robust anchor match" };
        }
        return { newContent: content, success: false, score: 0, message: "Anchor not found" };
      }

      case "replace_between_anchors":
        const s = content.indexOf(op.start_anchor), e = content.indexOf(op.end_anchor);
        if(s > -1 && e > -1 && e > s) {
             const pre = content.substring(0, s + op.start_anchor.length), post = content.substring(e);
             return { newContent: pre + "\n" + op.content + "\n" + post, success: true, score: 100, message: "Range replaced" };
        }
        return { newContent: content, success: false, score: 0, message: "Anchors not found" };

      case "create_file": 
        return { newContent: op.content || "", success: true, score: 100, message: "Created" };

      case "delete_file":
         return { newContent: "", success: true, score: 100, message: "Deleted" };

      default: 
        return { newContent: content, success: false, score: 0, message: "Unknown action" };
    }
  } catch(e:any) { 
      return { newContent: content, success: false, score: 0, message: `Err: ${e.message}` }; 
  }
}

// --- CORE PROCESSING LOGIC ---
async function processOperations(TARGET_REPO: string, operations: any[], projectPath: string, autoSanity: boolean, config?: AIConfig) {
    const scopePath = projectPath || "";
    if (scopePath) {
        const invalidOps = operations.filter((op: any) => {
            if (!op.file_path) return false;
            // Security Exception: Allow if inside scope OR is an Edge Function deployment
            const isScoped = op.file_path.startsWith(scopePath);
            const isEf = op.is_resolved_ef === true || op.file_path.startsWith("supabase/functions/");
            return !isScoped && !isEf;
        });
        if (invalidOps.length > 0) throw new Error(`Security: Operation on ${invalidOps[0].file_path} is outside project scope '${scopePath}'`);
    }
    
    let actualOps = [...operations];
    let patchNote = "";
    if (operations[0]?.action === 'comment') {
        patchNote = operations[0].text || "";
        actualOps = operations.slice(1);
    }
    const patchTitle = patchNote ? patchNote.split('\n')[0].substring(0, 60) : `Patch: ${actualOps.length} operations`;
    
    const devBranchRef = await githubFetch(TARGET_REPO, `/git/ref/heads/${DEV_BRANCH}`);
    const shaBeforePatch = devBranchRef.object.sha;

    const fileResults: any[] = [];
    const opsByFile: Record<string, any[]> = {};
    actualOps.forEach((op: any) => { if (op.file_path) { if (!opsByFile[op.file_path]) opsByFile[op.file_path] = []; opsByFile[op.file_path].push(op); } });

    let lastCommitSha = "";
    let anyOpFailed = false;

    for (const filePath of Object.keys(opsByFile)) {
        const opLogs: any[] = [];
        const fileOps = opsByFile[filePath];
        let { content, sha } = await getFileRaw(TARGET_REPO, filePath, DEV_BRANCH);

        // --- 1. EXPLICIT FILE EXISTENCE CHECK ---
        // If file has no SHA (doesn't exist) and we aren't running a 'create_file' op, fail early.
        const isCreation = fileOps.some((op: any) => op.action === "create_file");
        if (!sha && !isCreation) {
            anyOpFailed = true;
            fileOps.forEach((op: any) => {
                opLogs.push({ 
                    type: op.action, 
                    success: false, 
                    score: 0, 
                    message: `File path not found: ${filePath}` 
                });
            });
            fileResults.push({ file: filePath, status: "error", operations: opLogs });
            continue; // Skip to next file
        }

        let currentContent = base64ToText(content);
        let anyChange = false;

        for (const op of fileOps) {
            if (op.action === "delete_file") {
                if (!sha) { opLogs.push({ type: "delete_file", success: true, score: 100, message: "File already gone" }); }
                else {
                    try {
                        lastCommitSha = await deleteFile(TARGET_REPO, filePath, sha, DEV_BRANCH, `Conduit: Delete ${filePath}`);
                        opLogs.push({ type: "delete_file", success: true, score: 100, message: "Deleted" });
                    } catch (e: any) { opLogs.push({ type: "delete_file", success: false, score: 0, message: e.message }); anyOpFailed = true; }
                }
                currentContent = ""; sha = ""; continue;
            }

            let result = (op.action === "create_file") ? applyOperation("", op) : applyOperation(currentContent, op);

            if (!result.success && op.action !== "create_file") {
                // --- 2. ENHANCED AI HEALER ---
                const { fixedOp, reason, score } = await consultAI(currentContent, op, result.message, config);
                
                if (fixedOp) {
                    fixedOp.explanation = reason; 
                    let retryResult = applyOperation(currentContent, fixedOp);
                    
                    // Sanity Check Logic
                    if (retryResult.success && autoSanity) {
                         const sanity = await checkCodeSanity(retryResult.newContent, fixedOp, config);
                         if (!sanity.sane) {
                             opLogs.push({ type: "sanity_check", success: false, message: `Sanity Failed: ${sanity.issues}` });
                             // Recursively fix sanity
                             const { fixedOp: sanityOp, reason: sanityReason } = await consultAI(currentContent, fixedOp, `The operation ${JSON.stringify(fixedOp)} caused this syntax error: ${sanity.issues}`, config);
                             if (sanityOp) {
                                 const sanityResult = applyOperation(currentContent, sanityOp);
                                 if (sanityResult.success) { retryResult = sanityResult; fixedOp.explanation = `Auto-corrected sanity issue: ${sanityReason}`; }
                             }
                         }
                    }

                    if (retryResult.success) {
                        currentContent = retryResult.newContent;
                        anyChange = true;
                        opLogs.push({ type: op.action, success: true, score: score, message: retryResult.message, ...fixedOp });
                        continue; 
                    } else {
                        anyOpFailed = true;
                        opLogs.push({ type: op.action, success: false, score: 0, message: `AI Fix Failed: ${retryResult.message}` });
                    }
                } else {
                    // --- 3. DETAILED FAILURE LOGGING ---
                    anyOpFailed = true;
                    const failMsg = score > 0 
                        ? `Block not found. (Best Match: ${score}%). AI Reason: ${reason}`
                        : `Block not found. AI could not locate target. (${reason})`;
                        
                    opLogs.push({ type: op.action, success: false, score: score, message: failMsg });
                }
            } else {
                if (result.success) { currentContent = result.newContent; anyChange = true; } else { anyOpFailed = true; }
                opLogs.push({ type: op.action, success: result.success, score: result.score, message: result.message, ...op });
            }
        }

        if (anyChange) {
            try { lastCommitSha = await updateFile(TARGET_REPO, filePath, currentContent, sha, DEV_BRANCH, patchTitle); } 
            catch (e: any) { anyOpFailed = true; opLogs.push({ type: 'commit_file', success: false, message: `Commit failed: ${e.message}` }); }
        }
        fileResults.push({ file: filePath, status: anyChange ? "updated" : "unchanged", operations: opLogs });
    }

    if (anyOpFailed && lastCommitSha) {
        await githubFetch(TARGET_REPO, `/git/refs/heads/${DEV_BRANCH}`, { method: "PATCH", body: JSON.stringify({ sha: shaBeforePatch, force: true }) });
        const logData = { success: false, commit_sha: null, rollback_to: shaBeforePatch, message: "Patch failed, rolled back.", results: fileResults, ops: operations };
        await supabase.from('conduit_logs').insert({ repo_name: TARGET_REPO, type: 'patch_failed', data: logData });
        return logData;
    }

    const success = !anyOpFailed;
    const finalCommitSha = success ? lastCommitSha : null;
    const logData = { success, commit_sha: finalCommitSha, sha_before: shaBeforePatch, results: fileResults, ops: operations };

    if (success && lastCommitSha) {
        const { data: newHistory, error } = await supabase.from('conduit_history')
            .insert({ repo_name: TARGET_REPO, title: patchTitle, note: patchNote, type: "Dev", meta: `${actualOps.length} ops`, ops: operations, sha: lastCommitSha })
            .select('conduit_id')
            .single();

        if (newHistory) {
            logData.conduit_id = newHistory.conduit_id; // Add the new ID to the response
        }
        logData.sha = lastCommitSha; // Add SHA to the log data for linking
    }
    await supabase.from('conduit_logs').insert({ repo_name: TARGET_REPO, type: 'patch', data: logData });
    return logData;
}


// --- SYNTAX VALIDATION (DELEGATED) ---
async function validateWithTreeSitter(code: string, filePath: string) {
  // Replace with your actual project URL or use Env Var
  const PROJECT_REF = Deno.env.get("SUPABASE_URL")?.split("https://")[1].split(".")[0]; 
  const VALIDATOR_URL = `https://${PROJECT_REF}.supabase.co/functions/v1/syntax_validator`;
  
  // Guard: Ensure we never send undefined code to the validator
  const safeCode = code === undefined || code === null ? "" : code;
  
  // Debug Log: Track what we are actually sending
  console.log(`[SyntaxCheck] Validating ${filePath}: ${safeCode.length} bytes`);

  try {
    const res = await fetch(VALIDATOR_URL, {
      method: "POST",
      headers: { 
        "Content-Type": "application/json",
        "Authorization": `Bearer ${Deno.env.get("SUPABASE_ANON_KEY")}` 
      },
      body: JSON.stringify({ code: safeCode, file_path: filePath })
    });

    if (!res.ok) {
       const txt = await res.text();
       throw new Error(`Validator API Error ${res.status}: ${txt}`);
    }
    
    const data = await res.json();
    
    // Adapter: Convert Validator Response -> IDE Format
    if (data.success && data.result) {
        // Return raw objects so Client/AI can use line numbers
        return { 
            supported: true, 
            valid: data.result.valid, 
            errors: data.result.errors || [],
            warning: null 
        };
    }

    // Handle case where validator processed but failed logic
    return { supported: true, valid: true, errors: [], warning: data.error || "Validator response invalid" };

  } catch (e: any) {
    console.error("Delegated Validation Error:", e);
    // If the separate function fails, we fallback to saying 'valid' so we don't block the build
    return { supported: true, valid: true, errors: [], warning: "Syntax check skipped (Service Unavailable)" };
  }
}

// --- MAIN REQUEST HANDLER ---
serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  try {
    const body = await req.json();
    const { 
      action, operations, file_path, ref_sha, version_name, repo_name, 
      code_block, error_message, messages, context_files, auto_sanity, 
      workflow_id, branch, inputs, run_id, project_path, chat_id, title, user_prompt, ai_config, is_org, ...payload 
    } = body;

    const OWNER = is_org ? ORG_NAME : GITHUB_USER;
    // Ensure TARGET_REPO is always 'owner/name'
    const TARGET_REPO = repo_name 
        ? (repo_name.includes('/') ? repo_name : `${OWNER}/${repo_name}`) 
        : `${OWNER}/${DEFAULT_REPO}`;

    await ensureBranchExists(TARGET_REPO);

    // 1. INIT
    if (action === "init") {
        const scope = project_path || "";
        const historyLimit = 20;
        const logLimit = 20;
        const hOff = payload.history_offset || 0;
        const lOff = payload.log_offset || 0;

        // Smart Scoping: Org mode is strict, Personal mode allows legacy short-names
        const shortName = TARGET_REPO.includes('/') ? TARGET_REPO.split('/')[1] : TARGET_REPO;
        const repoFilter = is_org ? `repo_name.eq.${TARGET_REPO}` : `repo_name.eq.${TARGET_REPO},repo_name.eq.${shortName}`;

        const { data: history } = await supabase.from('conduit_history')
            .select('*, conduit_id')
            .or(repoFilter)
            .order('conduit_id', { ascending: false })
            .range(hOff, hOff + historyLimit - 1);

        const { data: logs } = await supabase.from('conduit_logs')
            .select('*')
            .or(repoFilter)
            .order('created_at', { ascending: false })
            .range(lOff, lOff + logLimit - 1);

        return new Response(JSON.stringify({ 
            history: (history || []).filter((h:any)=>isRecordInScope(h, scope)), 
            logs: (logs || []).filter((l:any)=>isRecordInScope(l, scope)) 
        }), { headers: { ...corsHeaders, "Content-Type": "application/json" } });
    }

    // 2. AI CHAT
        if (action === "ai_chat") {
        const { use_system_prompt, custom_system_prompt, new_message } = payload;

        // 1. Hydrate Last 10 Messages (Sliding Window)
        let historyMsgs = [];
        if (chat_id) {
            const { data } = await supabase.from('conduit_history').select('ops').eq('id', chat_id).single();
            historyMsgs = (data?.ops || []).slice(-10);
        }

        // 2. Optimized Hybrid Context Hydration (Server-Side Fetch)
        let contextBundle = "";
        if (context_files && context_files.length > 0) {
            const bundleParts = await Promise.all(context_files.map(async (f: any) => {
                let rawContent = "";
                if (f.is_dirty && f.content) {
                    rawContent = base64ToText(f.content);
                } else {
                    const { content } = await getFileRaw(TARGET_REPO, f.path, DEV_BRANCH);
                    rawContent = base64ToText(content);
                }
                return "FILE: " + f.path + "\n" + rawContent;
            }));
            contextBundle = "=== CONTEXT BUNDLE ===\n" + bundleParts.join("\n\n");
        }

        // 3. System Prompt Hydration
        let sysPrompt = custom_system_prompt || (use_system_prompt ? "You are the Conduit IDE Surgeon. Generate JSON patches with atomic precision." : "");

        // 4. Construct AI Stack
        const aiMessages = [
            ...(sysPrompt ? [{ role: 'system', content: sysPrompt }] : []),
            ...historyMsgs.map((m: any) => ({ 
                role: m.role === 'model' ? 'assistant' : 'user', 
                content: m.text 
            })),
            { role: 'user', content: contextBundle ? contextBundle + "\n\n=== USER REQUEST ===\n" + new_message : new_message }
        ];

        // 5. Execute AI with Context Caching hint (where supported)
        const result = await genericRequestAI('chat', aiMessages, ai_config);
        const finalReply = result.content || "";

        // 6. Update Persistent History
        const fullHistory = [...(historyMsgs), { role: 'user', text: new_message }, { role: 'model', text: finalReply }];
        let finalChatId = chat_id;
        if (chat_id) {
            await supabase.from('conduit_history').update({ ops: fullHistory }).eq('id', chat_id);
        } else {
            const { data } = await supabase.from('conduit_history').insert({ 
                repo_name: TARGET_REPO, type: 'Chat', title: new_message.substring(0, 50), ops: fullHistory 
            }).select('id').single();
            finalChatId = data?.id;
        }

        return new Response(JSON.stringify({ reply: finalReply, chat_id: finalChatId }), { headers: { ...corsHeaders, "Content-Type": "application/json" } });
    }

    // --- REFINED GENERATE OPS (ROBUST PROMPT & PARSER) ---
    if (action === "generate_ops") {
        if (!user_prompt) throw new Error("Prompt required");
        
        const fileContext = context_files.map((f: any) => 
            `FILE: ${f.path}\nCONTENT:\n${f.content ? base64ToText(f.content) : "(Content omitted)"}\n`
        ).join("\n---\n");

        const systemPrompt = `You are a Strict JSON Patch Generator.
        Your goal: Convert User Request into a valid JSON Operations Array.
        
        ALLOWED OPERATIONS:
        - REPLACE: { "file_path": "string", "action": "replace_block", "find_block": "EXACT_CODE", "replace_with": "NEW_CODE" }
        
        STRICT RULES:
        1. Output ONLY a raw JSON Array. No Markdown blocks. No Explanations.
        2. Escape all quotes inside strings properly.
        3. ABSOLUTELY NO TRAILING COMMAS.
        4. 'find_block' must match whitespace EXACTLY.
        `;

        const finalPrompt = `${systemPrompt}\n=== CONTEXT ===\n${fileContext}\n=== REQUEST ===\n${user_prompt}`;
        
        const result = await genericRequestAI('architect', [{ role: "user", content: finalPrompt }], ai_config);
        let text = result.content || "[]";
        
        // 1. Regex Extraction (Find outermost brackets)
        const jsonMatch = text.match(/\[[\s\S]*\]/);
        if (jsonMatch) text = jsonMatch[0];

        // 2. Clean Markdown Wrappers if present
        text = text.replace(/```json/g, "").replace(/```/g, "").trim();

        // 3. Fix Common AI JSON Errors (Trailing commas)
        text = text.replace(/,(\s*[\]}])/g, "$1");

        // 4. Verification
        try {
            JSON.parse(text);
        } catch (e) {
            console.error("AI Generated Invalid JSON:", text);
            // Emergency Fallback: Return empty array to prevent client crash
            return new Response(JSON.stringify({ operations: "[]", error: "AI generated invalid JSON syntax. Please retry." }), { headers: { ...corsHeaders, "Content-Type": "application/json" } });
        }
        
        return new Response(JSON.stringify({ operations: text }), { headers: { ...corsHeaders, "Content-Type": "application/json" } });
    }

    // --- AUTO FIX BUILD (UPDATED LOGIC) ---
    if (action === "auto_fix_build") {
        if (!run_id) throw new Error("run_id required");

        // Immediate Trace: Signal that analysis is starting
        await supabase.from('conduit_logs').insert({
            repo_name: TARGET_REPO,
            type: 'ai_trace',
            data: { 
                stage: 'INIT_AUTO_FIX', 
                run_id: run_id, 
                message: 'Fetching logs and preparing AI Detective...' 
            }
        });

        // 1. Get Log Text
        const jobsData = await githubFetch(TARGET_REPO, `/actions/runs/${run_id}/jobs`);
        const failedJob = jobsData.jobs.find((j: any) => j.conclusion === "failure");
        if (!failedJob) return new Response(JSON.stringify({ success: false, message: "No failed job found." }), { headers: { ...corsHeaders, "Content-Type": "application/json" } });

        // Get the commit SHA that triggered the failed run
        const runData = jobsData.workflow_runs?.find((r:any) => r.id === run_id) || jobsData.workflow_runs?.[0]; // Fallback
        const failedSha = runData?.head_sha || failedJob.head_sha; 
        if (!failedSha) throw new Error("Could not determine commit SHA for failed run.");

        // 1. Find the last known successful SHA from history (The "Good" base)
        const { data: lastGoodHistory } = await supabase.from('conduit_history')
            .select('sha')
            .eq('repo_name', TARGET_REPO)
            .order('conduit_id', { ascending: false })
            .limit(1)
            .maybeSingle();

        const baseSha = lastGoodHistory?.sha || `${DEV_BRANCH}~1`; // Fallback to immediate parent if no history

        const jobLogRes = await fetch(`https://api.github.com/repos/${GITHUB_USER}/${TARGET_REPO}/actions/jobs/${failedJob.id}/logs`, { headers: getHeaders(), redirect: "follow" });
        const logText = await jobLogRes.text();

        // 2. PHASE 1: Detective (Summarize & Identify Files)
        const { summary, files } = await analyzeLogsAndIdentifyFiles(logText, ai_config);
        
        // Log the analysis for user visibility
        await supabase.from('conduit_logs').insert({ 
            repo_name: TARGET_REPO, 
            type: 'job_analysis', 
            data: { job_id: String(failedJob.id), analysis: summary, identified_files: files } 
        });

        if (files.length === 0) {
            return new Response(JSON.stringify({ success: false, message: "AI could not identify source files from logs." }), { headers: { ...corsHeaders, "Content-Type": "application/json" } });
        }

        // 3. PHASE 2: Fetch FULL content of identified files AND Change Context
        const contextPayload = [];
        let changeContext = "";

        // NEW: Fetch diff (Range Compare -> Fallback to Single Commit)
        try {
            let diffFiles = [];
            
            // Strategy 1: Compare Range (Last good -> Failed)
            try {
                const diffRes = await githubFetch(TARGET_REPO, `/compare/${baseSha}...${failedSha}`);
                if (diffRes.files) diffFiles = diffRes.files;
            } catch (e) { console.warn("[AutoFix] Range compare failed, trying single commit..."); }

            // Strategy 2: Single Commit Fallback (If range empty, fetch the specific breaking commit)
            if (diffFiles.length === 0) {
                console.log(`[AutoFix] Fetching specific commit: ${failedSha}`);
                const commitRes = await githubFetch(TARGET_REPO, `/commits/${failedSha}`);
                if (commitRes.files) diffFiles = commitRes.files;
            }

            // Normalize paths for matching (remove './' or leading '/')
            const norm = (p: string) => p.replace(/^\.?\//, '').trim();
            const targetFiles = files.map(norm);

            console.log("[AutoFix] Victim Files:", targetFiles);
            console.log("[AutoFix] Diff Candidates:", diffFiles.map((f:any) => f.filename));

            // Filter diffs (Use Fuzzy Matching: endsWith instead of strict equality)
            const relevantDiffs = diffFiles.filter((f: any) => {
                const diffPath = norm(f.filename);
                return targetFiles.some(tf => {
                    // Match if 'app/src/main/Manifest.xml' ends with 'Manifest.xml' (AI output)
                    // Or if 'Manifest.xml' ends with 'app/src/main/Manifest.xml' (Unlikely but safe)
                    return diffPath.includes(tf) || tf.includes(diffPath) || diffPath.endsWith(tf) || tf.endsWith(diffPath);
                });
            });
            
            changeContext = relevantDiffs.map((f:any) => 
                `FILE: ${f.filename}\nSTATUS: ${f.status}\nPATCH:\n${f.patch || "No patch content available."}`
            ).join("\n---\n");

            console.log(`[AutoFix] Final Context Size: ${changeContext.length} chars (Matches: ${relevantDiffs.length})`);
        } catch(e) { 
            console.error("Failed to fetch diff context:", e); 
        }

        for (const path of files) {
            try { 
                const { content } = await getFileRaw(TARGET_REPO, path, DEV_BRANCH); 
                contextPayload.push({ path, content }); 
            } catch(e) { console.error(`Failed to fetch ${path}`); }
        }

        // 4. PHASE 3: Surgeon (Generate Fix) - Pass the new change context
        // Immediate Trace: Log the exact prompt context being sent to the Surgeon
        await supabase.from('conduit_logs').insert({
            repo_name: TARGET_REPO,
            type: 'ai_trace',
            data: {
                stage: 'SURGEON_PROMPT_SENT',
                error_summary: summary,
                context_files: files,
                diff_context: changeContext.substring(0, 2000) + "..."
            }
        });

        const opsJson = await generateFixFromFullContext(summary, contextPayload, changeContext, ai_config);
        let ops = [];
        try { ops = JSON.parse(opsJson); } catch(e) { throw new Error("AI generated invalid JSON"); }

        // RECURSION GUARD: Tag the commit so we don't fix our own failures infinitely
        if (ops.length > 0) {
            const tag = "[Auto-Fix]";
            if (ops[0].action === 'comment') {
                if (!ops[0].text.startsWith(tag)) ops[0].text = `${tag} ${ops[0].text}`;
            } else {
                ops.unshift({ action: 'comment', text: `${tag} Automated build repair` });
            }
        }

        if (ops.length === 0) return new Response(JSON.stringify({ success: false, message: "AI could not determine a fix." }), { headers: { ...corsHeaders, "Content-Type": "application/json" } });

        // 5. Apply Patch
        const patchResult = await processOperations(TARGET_REPO, ops, "", true, ai_config);

        // 6. Trigger Re-Build (Explicitly required for bot commits)
        let triggerMsg = "No trigger attempted";
        if (patchResult.success) {
             try {
                 // Fetch the run info to get the specific workflow ID
                 const runInfo = await githubFetch(TARGET_REPO, `/actions/runs/${run_id}`);
                 if (runInfo.workflow_id) {
                     await triggerWorkflowFile(TARGET_REPO, String(runInfo.workflow_id), DEV_BRANCH);
                     triggerMsg = `Triggered workflow ${runInfo.workflow_id}`;
                 }
             } catch(e:any) { 
                 console.error("Auto-trigger failed:", e);
                 triggerMsg = `Trigger failed: ${e.message}`; 
             }
        }

        await supabase.from('conduit_logs').insert({ repo_name: TARGET_REPO, type: 'auto_fix_trigger', data: { run_id: run_id, fix_applied: patchResult.success, ops, trigger: triggerMsg } });
        return new Response(JSON.stringify({ success: true, patch_result: patchResult, trigger_status: triggerMsg }), { headers: { ...corsHeaders, "Content-Type": "application/json" } });
    }

    if (action === "patch" && operations) {
        const result = await processOperations(TARGET_REPO, operations, project_path, !!auto_sanity, ai_config);
        return new Response(JSON.stringify(result), { headers: { ...corsHeaders, "Content-Type": "application/json" } });
    }

    if (action === "fetch") {
      const treeData = await githubFetch(TARGET_REPO, `/git/trees/${DEV_BRANCH}?recursive=1`);
      const branchData = await githubFetch(TARGET_REPO, `/branches/${DEV_BRANCH}`);
      const branchDate = branchData.commit.commit.committer.date;

      // 1. Fetch recent history to map file-specific updates
      const { data: history } = await supabase
        .from('conduit_history')
        .select('created_at, ops')
        .eq('repo_name', TARGET_REPO)
        .order('created_at', { ascending: false })
        .limit(50);

      const fileDateMap: Record<string, string> = {};
      if (history) {
        history.forEach(entry => {
          if (Array.isArray(entry.ops)) {
            entry.ops.forEach((op: any) => {
              if (op.file_path && !fileDateMap[op.file_path]) {
                fileDateMap[op.file_path] = entry.created_at;
              }
            });
          }
        });
      }

      const files = treeData.tree
        .filter((f: any) => f.type === "blob")
        .map((f: any) => ({
          path: f.path,
          sha: f.sha,
          size: f.size,
          // Priority: 1. IDE History Date, 2. Branch Update Date
          last_updated: fileDateMap[f.path] || branchDate
        }));

      return new Response(JSON.stringify({ files }), { headers: { ...corsHeaders, "Content-Type": "application/json" } });
    }

    if (action === "fetch_project_bundle") {
        const ref = ref_sha || DEV_BRANCH;
        const scope = (project_path || "").trim();
        const treeData = await githubFetch(TARGET_REPO, `/git/trees/${ref}?recursive=1`);
        const blobs = (treeData.tree || []).filter((f: any) => f.type === "blob" && (!scope || f.path.startsWith(scope)));
        
        const bundle: Record<string, any> = {};
        // Aggressive server-side parallel fetch
        await Promise.all(blobs.map(async (f: any) => {
            try {
                const { content } = await getFileRaw(TARGET_REPO, f.path, ref);
                bundle[f.path] = { content, sha: f.sha, size: f.size };
            } catch (e) {}
        }));
        
        return new Response(JSON.stringify({ files: bundle }), { headers: { ...corsHeaders, "Content-Type": "application/json" } });
    }

    if (action === "fetch_project_bundle") {
      const scope = (payload.project_path || "").trim();
      const tree = await githubFetch(TARGET_REPO, `/git/trees/${ref_sha || DEV_BRANCH}?recursive=1`);
      // Strict Filtering: Only blobs, and must start with project_path if defined
      const files = tree.tree.filter((f: any) => f.type === "blob" && (!scope || f.path.startsWith(scope)));
      const fileData: Record<string, any> = {};
      const batchSize = 5; // Reduced from 10 to avoid GitHub rate limits/timeouts
      for (let i = 0; i < files.length; i += batchSize) {
          await Promise.all(files.slice(i, i + batchSize).map(async (f: any) => {
              try { 
                  const { content } = await getFileRaw(TARGET_REPO, f.path, ref_sha || DEV_BRANCH); 
                  if(content) fileData[f.path] = { content, sha: f.sha };
              } catch (e) { console.error(`Failed to bundle ${f.path}:`, e); }
          }));
      }
      return new Response(JSON.stringify({ files: fileData }), { headers: { ...corsHeaders, "Content-Type": "application/json" } });
    }

    if (action === "preview_file") {
        console.log('[Action: preview_file] File: ' + file_path + ' Ref: ' + (ref_sha || 'HEAD'));
        const { content, sha } = await getFileRaw(TARGET_REPO, file_path || 'index.html', ref_sha || DEV_BRANCH);
        if (!content) console.warn('[Action: preview_file] 404 or Empty: ' + file_path);
        return new Response(JSON.stringify({ content, sha }), { headers: { ...corsHeaders, "Content-Type": "application/json" } });
    }

    if (action === "fix_syntax") {
        const result = await repairSyntaxWithAI(code_block, error_message, ai_config);
        return new Response(JSON.stringify({ success: !!result.fixed_code, fixed_code: result.fixed_code, explanation: result.explanation }), { headers: { ...corsHeaders, "Content-Type": "application/json" } });
    }

    if (action === "propose_syntax_fix") {
        const { code, error, file_path } = payload;

        // STRATEGY: PURE DETERMINISTIC REPAIR (WASM-Guided)
        // We strictly adhere to validator instructions. No AI hallucinations allowed.
        if (error && error.message && error.message.startsWith("MISSING")) {
            const missingCharMatch = error.message.match(/MISSING "(.*?)"/);
            if (missingCharMatch) {
                const charToInsert = missingCharMatch[1];
                const lines = code.split('\n');
                // Tree-Sitter lines are 0-indexed internally, but usually reported 1-indexed.
                const targetLineIdx = error.line - 1;
                
                if (lines[targetLineIdx] !== undefined) {
                    console.log(`[DeterministicFix] Auto-inserting '${charToInsert}' at line ${error.line}`);
                    
                    const lineContent = lines[targetLineIdx];
                    const cleanOps = [
                        { "action": "comment", "text": `Auto-fix: Inserted missing ${charToInsert}` },
                        { 
                            "action": "replace_block", 
                            "file_path": file_path,
                            "find_block": lineContent,
                            "replace_with": lineContent + charToInsert 
                        }
                    ];
                    return new Response(JSON.stringify({ operations: JSON.stringify(cleanOps), success: true }), { headers: { ...corsHeaders, "Content-Type": "application/json" } });
                }
            }
        }

        // Fallback: Return empty to signal manual intervention needed
        return new Response(JSON.stringify({ operations: "[]", success: false }), { headers: { ...corsHeaders, "Content-Type": "application/json" } });
    }

    if (action === "validate_syntax_deep") {
        // ERROR FIX: 'code_block' and 'file_path' are already in the top-level scope.
        // We must NOT extract them from 'payload' (the rest object) or they will be undefined.
        
        console.log(`[DeepValidate] Input Received - File: ${file_path}, Code Length: ${code_block ? code_block.length : 'undefined'}`);

        try {
            // 1. Deterministic Check (WASM)
            // We use the top-level variable 'code_block' directly
            const tsResult = await validateWithTreeSitter(code_block || "", file_path || "file.js");
            if (tsResult.supported) {
                 return new Response(JSON.stringify(tsResult), { headers: { ...corsHeaders, "Content-Type": "application/json" } });
            }
            // 2. Fallback: AI Analyst
            const prompt = `Analyze code for FATAL syntax errors only. Ignore warnings. CODE (${file_path}):\n${code_block || ""}\nOUTPUT JSON: { "valid": boolean, "errors": ["..."] }`;
            const result = await genericRequestAI('analyst', [{ role: "user", content: prompt }], ai_config, undefined, { type: "json_object" });
            const json = JSON.parse(result.content || '{"valid":true}');
            return new Response(JSON.stringify({ ...json, supported: true }), { headers: { ...corsHeaders, "Content-Type": "application/json" } });
        } catch (e: any) {
            return new Response(JSON.stringify({ valid: false, errors: [e.message] }), { headers: { ...corsHeaders, "Content-Type": "application/json" } });
        }
    }

    if (action === "trigger_build") {
        const targetBranch = branch || DEV_BRANCH;
        const isSha = /^[0-9a-f]{40}$/i.test(targetBranch);
        
        // --- PRE-BUILD SYNTAX GUARD (OPTIMIZED BATCHING) ---
        if (payload.validate_pre_build) {
            console.log("Running Optimized Pre-Build Syntax Scan...");
            const tree = await githubFetch(TARGET_REPO, `/git/trees/${targetBranch}?recursive=1`);
            // Filter for source code files within the project scope
            const codeFiles = tree.tree.filter((f: any) => 
                f.type === "blob" && 
                f.path.startsWith(project_path || "") && 
                f.path.match(/\.(js|ts|tsx|jsx|py|go|rs|java|kt|c|cpp|json|bash|sh)$/)
            );

            const BATCH_SIZE = 5; 
            for (let i = 0; i < codeFiles.length; i += BATCH_SIZE) {
                const batch = codeFiles.slice(i, i + BATCH_SIZE);
                const batchResults = await Promise.all(batch.map(async (f: any) => {
                    const { content } = await getFileRaw(TARGET_REPO, f.path, targetBranch);
                    if (!content) return { path: f.path, valid: true };
                    const text = base64ToText(content);
                    const res = await validateWithTreeSitter(text, f.path);
                    return { path: f.path, ...res };
                }));

                for (const res of batchResults) {
                    if (!res.valid) {
                        const errorMsg = `File: ${res.path}\nErrors: ${res.errors?.slice(0, 3).join(", ")}`;
                        await supabase.from('conduit_logs').insert({ 
                            repo_name: TARGET_REPO, type: 'dispatch_aborted', 
                            data: { reason: "syntax_error", details: errorMsg } 
                        });
                        // Return structured data for Client-Side Auto-Fix
                        return new Response(JSON.stringify({ 
                            success: false, 
                            validation_error: true, 
                            file_path: res.path,
                            errors: res.errors,
                            error: errorMsg 
                        }), { headers: { ...corsHeaders, "Content-Type": "application/json" } });
                    }
                }
            }
        }

        try {
            if (workflow_id) {
                // AUTO-PROVISION or UPDATE deploy.yml (Skip if building a SHA)
                if (workflow_id === 'deploy.yml' && !isSha) {
                    let sha = "";
                    let exists = false;
                    let content = "";

                    try {
                        const res = await githubFetch(TARGET_REPO, `/contents/.github/workflows/deploy.yml?ref=${targetBranch}`);
                        sha = res.sha;
                        content = base64ToText(res.content);
                        exists = true;
                    } catch (e) {
                        // 404 likely, file needs creation
                    }

                    // Check if file is missing OR outdated (missing input OR using old parsing logic)
                    if (!exists || !content.includes("conduit_url") || !content.includes("jq -r")) {
                        console.log(`[Trigger] deploy.yml ${exists ? 'outdated' : 'missing'}. Provisioning...`);
                        await updateFile(TARGET_REPO, ".github/workflows/deploy.yml", AUTO_DEPLOY_YML, sha, targetBranch, "Conduit: Update deploy workflow definition");
                        // Give GitHub 2 seconds to index the new file before triggering
                        await new Promise(r => setTimeout(r, 2000));
                    }
                }

                // Dispatch with simple retry logic for fresh files
                let attempts = 0;
                const dispatch = async () => {
                    try {
                        // Ensure inputs exists before modification
                        let effectiveInputs = inputs || {};
                        
                        // Generate dynamic ticket for deploy.yml
                        if (workflow_id === 'deploy.yml') {
                            const ticket = crypto.randomUUID();
                            await supabase.from('conduit_logs').insert({
                                repo_name: TARGET_REPO,
                                type: 'deploy_ticket',
                                data: { ticket }
                            });
                            effectiveInputs.deploy_ticket = ticket;
                            // Use Env Var for absolute reliability
                            const projectUrl = Deno.env.get("SUPABASE_URL");
                            effectiveInputs.conduit_url = `${projectUrl}/functions/v1/ide-brain`;
                        }
                        await triggerWorkflowFile(TARGET_REPO, workflow_id, targetBranch, effectiveInputs);
                    } catch (err: any) {
                        if (attempts < 2 && err.message.includes("404")) {
                            attempts++;
                            await new Promise(r => setTimeout(r, 3000));
                            return dispatch();
                        }
                        throw err;
                    }
                };
                await dispatch();
            } else {
                await dispatchWorkflow(TARGET_REPO, "conduit_build_trigger", { version: version_name || "latest", source: "conduit-ide" });
            }
            await supabase.from('conduit_logs').insert({ 
                repo_name: TARGET_REPO, type: 'dispatch', 
                data: { success: true, workflow_id, branch: targetBranch } 
            });
            return new Response(JSON.stringify({ success: true }), { headers: { ...corsHeaders, "Content-Type": "application/json" } });
        } catch (e: any) {
            await supabase.from('conduit_logs').insert({ 
                repo_name: TARGET_REPO, type: 'dispatch_error', 
                data: { error: e.message, workflow_id } 
            });
            return new Response(JSON.stringify({ success: false, error: e.message }), { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } });
        }
    }

    if (action === "fetch_workflows") {
      const page = payload.page || 1;
      const data = await githubFetch(TARGET_REPO, `/actions/runs?per_page=20&page=${page}`);
      return new Response(JSON.stringify({ runs: data.workflow_runs }), { headers: { ...corsHeaders, "Content-Type": "application/json" } });
    }

    if (action === "fetch_workflow_jobs") {
      const data = await githubFetch(TARGET_REPO, `/actions/runs/${run_id}/jobs`);
      return new Response(JSON.stringify({ jobs: data.jobs }), { headers: { ...corsHeaders, "Content-Type": "application/json" } });
    }

    if (action === "analyze_log") {
      const { job_id } = payload;
      const { data: cached } = await supabase.from('conduit_logs').select('data').eq('type', 'job_analysis').eq('repo_name', TARGET_REPO).filter('data->>job_id', 'eq', String(job_id)).maybeSingle();
      if (cached) return new Response(JSON.stringify({ analysis: cached.data.analysis, cached: true }), { headers: { ...corsHeaders, "Content-Type": "application/json" } });
      
      const cleanRepo = TARGET_REPO.includes('/') ? TARGET_REPO : `${GITHUB_USER}/${TARGET_REPO}`;
      const logUrl = `https://api.github.com/repos/${cleanRepo}/actions/jobs/${job_id}/logs`;
      const logRes = await fetch(logUrl, { headers: { ...getHeaders() }, redirect: "follow" });
      if(!logRes.ok) throw new Error("Log fetch failed");
      const logs = await logRes.text();
      
      const prompt = `Analyze Log:\n${logs.slice(-20000)}\nOUTPUT: specific error, file/line, brief summary.`;
      
      const result = await genericRequestAI('analyst', [{ role: "user", content: prompt }], ai_config);
      const analysis = result.content || "Failed to analyze.";
      
      await supabase.from('conduit_logs').insert({ repo_name: TARGET_REPO, type: 'job_analysis', data: { job_id: String(job_id), analysis } });
      return new Response(JSON.stringify({ analysis }), { headers: { ...corsHeaders, "Content-Type": "application/json" } });
    }

    if (action === "fetch_job_logs") {
      const cleanRepo = TARGET_REPO.includes('/') ? TARGET_REPO : `${GITHUB_USER}/${TARGET_REPO}`;
      const url = `https://api.github.com/repos/${cleanRepo}/actions/jobs/${payload.job_id}/logs`;
      const res = await fetch(url, { headers: { ...getHeaders() }, redirect: "follow" });
      return new Response(JSON.stringify({ logs: await res.text() }), { headers: { ...corsHeaders, "Content-Type": "application/json" } });
    }

    if (action === "fetch_ef_logs") {
        const { function_slug, before } = payload;
        const projectRef = Deno.env.get("SUPABASE_URL")?.split("https://")[1].split(".")[0];
        const cloudKey = Deno.env.get("CONDUIT_ACCESS_TOKEN");

        if (!projectRef || !cloudKey) throw new Error("Missing ProjectRef or CONDUIT_ACCESS_TOKEN");

        // Defensive Timestamp Handling: Handle both ms and us inputs
        let endTimestamp = before ? parseInt(before) : Date.now();
        if (endTimestamp > 10000000000000) endTimestamp = Math.floor(endTimestamp / 1000); // Convert us to ms
        
        const endDate = new Date(endTimestamp);
        const startDate = new Date(endDate.getTime() - (24 * 60 * 60 * 1000));

        // 1. Target function_logs for STDOUT (console.log)
        // 2. Target function_edge_logs for network metadata (status codes, latency)
        // Optimized query to avoid ARRAY/STRUCT metadata errors
        const sql = `
            SELECT 
                timestamp, 
                event_message
            FROM function_logs
            WHERE 
                event_message LIKE '%${function_slug}%'
            ORDER BY timestamp DESC 
            LIMIT 50
        `.trim();
        
        const url = new URL(`https://api.supabase.com/v1/projects/${projectRef}/analytics/endpoints/logs.all`);
        url.searchParams.set("iso_timestamp_start", startDate.toISOString());
        url.searchParams.set("iso_timestamp_end", endDate.toISOString());
        url.searchParams.set("sql", sql);
        
        const response = await fetch(url.toString(), { 
            headers: { 
                "Authorization": `Bearer ${cloudKey}`, 
                "Accept": "application/json"
            } 
        });

        const rawText = await response.text();
        
        // --- EXTENSIVE DEBUG LOGGING ---
        console.log("[EF_LOGS_DEBUG] HTTP Status:", response.status);
        console.log("[EF_LOGS_DEBUG] Query URL:", url.toString());
        console.log("[EF_LOGS_DEBUG] Raw Response Body:", rawText);

        let parsed;
        try {
            parsed = JSON.parse(rawText);
            console.log("[EF_LOGS_DEBUG] Parsed JSON Structure:", Array.isArray(parsed) ? "Array" : typeof parsed);
        } catch(e) {
            console.error("[EF_LOGS_DEBUG] JSON Parse Failed:", e.message);
            return new Response(JSON.stringify({ error: "Failed to parse REST response", raw: rawText }), { headers: corsHeaders });
        }

        // The logs.all endpoint returns { result: [...] }
        const rawRows = parsed.result || parsed.data || (Array.isArray(parsed) ? parsed : []);

        // 3. Map to IDE Format
        const normalizedLogs = rawRows.map((row: any) => {
            // Convert Supabase microseconds (16 digits) to JS milliseconds (13 digits)
            let ts = row.timestamp || Date.now();
            if (ts > 10000000000000) ts = Math.floor(ts / 1000);

            return {
                timestamp: ts,
                event_message: row.event_message || row.message || "(No message)",
                level: row.level || row.metadata?.level || "info",
                function_id: function_slug
            };
        });

        console.log("--- END LOG FETCH (Filtered Count: " + normalizedLogs.length + ") ---");
        return new Response(JSON.stringify({ logs: normalizedLogs, debug_raw: parsed }), { headers: { ...corsHeaders, "Content-Type": "application/json" } });
    }

    if (action === "commit_prod") {
        const scopePath = project_path || "";
        const tree = await githubFetch(TARGET_REPO, `/git/trees/${DEV_BRANCH}?recursive=1`);
        const files = tree.tree.filter((f: any) => f.type === "blob" && f.path.startsWith(scopePath));
        let copiedCount = 0;
        for (const file of files) {
            const { content } = await getFileRaw(TARGET_REPO, file.path, DEV_BRANCH);
            if (content) {
                const textContent = base64ToText(content);
                const relativePath = scopePath ? file.path.substring(scopePath.length) : file.path;
                const cleanPath = relativePath.startsWith('/') ? relativePath.slice(1) : relativePath;
                await updateFile(TARGET_REPO, `${version_name}/${cleanPath}`, textContent, "", MAIN_BRANCH, `Snapshot ${version_name}`);
                copiedCount++;
            }
        }
        const mainRef = await githubFetch(TARGET_REPO, `/git/ref/heads/${MAIN_BRANCH}`);
        const logData = { success: true, count: copiedCount, message: `Deployed ${version_name}` };
        await supabase.from('conduit_history').insert({ repo_name: TARGET_REPO, title: `Deployed ${version_name}`, type: "Prod", meta: `Snapshot ${copiedCount} files`, sha: mainRef.object.sha });
        await supabase.from('conduit_logs').insert({ repo_name: TARGET_REPO, type: 'commit_prod', data: logData });
        return new Response(JSON.stringify(logData), { headers: { ...corsHeaders, "Content-Type": "application/json" } });
    }
    
    if (action === "fetch_models") {
        const providerId = payload.provider_id;
        const provider = ai_config?.providers?.[providerId] || resolveProvider('chat', ai_config);

        if (!provider) throw new Error("Provider not found");

        const serviceMap: Record<string, string> = { 'Deepseek_API': 'deepseek', 'GEMINI_API_KEY': 'gemini', 'GROQ_API_KEY': 'groq' };
        const serviceName = serviceMap[provider.apiKeyEnv];
        let apiKey = "";

        if (serviceName) {
            const { data: keyRow } = await supabase.from('api_keys').select('api_key').eq('service', serviceName).eq('is_active', true).order('last_used_at', { ascending: true }).limit(1).maybeSingle();
            if (keyRow) apiKey = keyRow.api_key;
        }

        if (!apiKey) apiKey = Deno.env.get(provider.apiKeyEnv) || "";
        if (!apiKey) throw new Error("API Key not found");

        let targetUrl = "";
        let fetchOptions: RequestInit = { headers: { "Content-Type": "application/json" } };

        if (provider.type === 'google') {
            targetUrl = `https://generativelanguage.googleapis.com/v1beta/models?key=${apiKey}`;
        } else {
            // OpenAI-compatible: Strip /chat/completions and add /models
            targetUrl = provider.baseUrl.replace(/\/chat\/completions\/?$/, "/models");
            (fetchOptions.headers as any)["Authorization"] = `Bearer ${apiKey}`;
        }

        const res = await fetch(targetUrl, fetchOptions);
        const rawText = await res.text();

        if (!res.ok) {
            return new Response(JSON.stringify({ error: `Provider Error (${res.status})`, details: rawText }), { status: res.status, headers: { ...corsHeaders, "Content-Type": "application/json" } });
        }

        const data = JSON.parse(rawText);
        let models = [];
        if (provider.type === 'google') {
            models = (data.models || []).map((m: any) => ({ id: m.name.split('/').pop() }));
        } else {
            models = data.data || [];
        }

        return new Response(JSON.stringify({ models }), { headers: { ...corsHeaders, "Content-Type": "application/json" } });
    }

    if (action === "search_repo") {
        const { query, project_path } = payload;
        const tree = await githubFetch(TARGET_REPO, `/git/trees/${DEV_BRANCH}?recursive=1`);
        const files = tree.tree.filter((f: any) => f.type === "blob" && f.path.startsWith(project_path || ""));
        
        const results: any[] = [];
        // Batch search - processing first 50 files for performance
        for (const file of files.slice(0, 50)) {
            const { content } = await getFileRaw(TARGET_REPO, file.path, DEV_BRANCH);
            if (content) {
                const text = base64ToText(content);
                const lines = text.split('\n');
                lines.forEach((line, i) => {
                    if (line.toLowerCase().includes(query.toLowerCase())) {
                        results.push({ 
                            path: file.path, 
                            line: i + 1, 
                            context: line.trim().substring(0, 100)
                        });
                    }
                });
            }
        }
        return new Response(JSON.stringify({ results }), { headers: { ...corsHeaders, "Content-Type": "application/json" } });
    }

    if (action === "claim_deploy_token") {
        const { ticket } = payload;
        const { data, error } = await supabase.from('conduit_logs')
            .select('id, data')
            .eq('type', 'deploy_ticket')
            .filter('data->>ticket', 'eq', ticket)
            .gt('created_at', new Date(Date.now() - 300000).toISOString()) // 5 minute expiry
            .maybeSingle();

        if (!data || error) return new Response(JSON.stringify({ error: "Invalid or expired ticket" }), { status: 403, headers: corsHeaders });

        // Delete ticket after claim (Single Use)
        await supabase.from('conduit_logs').delete().eq('id', data.id);

        return new Response(JSON.stringify({
            token: Deno.env.get("CONDUIT_ACCESS_TOKEN"),
            url: Deno.env.get("SUPABASE_URL")
        }), { headers: { ...corsHeaders, "Content-Type": "application/json" } });
    }

    if (action === "populate_boilerplate") {
        const operations = Object.entries(ANDROID_BOILERPLATE).map(([path, content]) => ({
            action: "create_file",
            file_path: path,
            content: content
        }));
        
        // Bypass project_path scope for boilerplate - these are root config files
        const result = await processOperations(TARGET_REPO, [
            { action: "comment", text: "[Setup] Populate Android Project Boilerplate" },
            ...operations
        ], "", true, ai_config);
        
        return new Response(JSON.stringify(result), { headers: { ...corsHeaders, "Content-Type": "application/json" } });
    }

    if (action === "fetch_repos") {
        const url = is_org 
            ? `https://api.github.com/orgs/${ORG_NAME}/repos?per_page=100&sort=pushed` 
            : `https://api.github.com/user/repos?per_page=100&sort=pushed`;
        const res = await fetch(url, { headers: getHeaders() });
        if (!res.ok) throw new Error(`GitHub API Error: ${res.status}`);
        const data = await res.json();
        const repos = data.map((r: any) => ({ name: r.name, full_name: r.full_name }));
        return new Response(JSON.stringify({ repos }), { headers: { ...corsHeaders, "Content-Type": "application/json" } });
    }

    if (action === "migrate_repo") {
        const { target_owner, source_repo } = payload;
        const targetRepoPath = `${target_owner}/${source_repo}`;

        // 1. Fetch ALL source files
        const sourceTree = await githubFetch(TARGET_REPO, `/git/trees/${DEV_BRANCH}?recursive=1`);
        const sourceBlobs = sourceTree.tree.filter((f: any) => f.type === "blob");

        // 2. Check if target repo exists, create if not
        try {
            await githubFetch(targetRepoPath, "");
        } catch (e) {
            console.log("Target repo missing, creating...");
            await fetch(`https://api.github.com/orgs/${target_owner}/repos`, {
                method: "POST",
                headers: getHeaders(),
                body: JSON.stringify({ name: source_repo, private: true, auto_init: true })
            });
            await new Promise(r => setTimeout(r, 2000)); // Wait for init
        }

        await ensureBranchExists(targetRepoPath);

        // 3. Fetch target tree to wipe it
        const targetTree = await githubFetch(targetRepoPath, `/git/trees/${DEV_BRANCH}?recursive=1`);
        const targetBlobs = targetTree.tree.filter((f: any) => f.type === "blob");

        const migrationOps: any[] = [
            { action: "comment", text: `[Migration] Full sync from ${TARGET_REPO}` }
        ];

        // Destructive Wipe: Delete everything in target
        targetBlobs.forEach((f: any) => {
            migrationOps.push({ action: "delete_file", file_path: f.path });
        });

        // Full Sync: Create everything from source
        for (const f of sourceBlobs) {
            const { content } = await getFileRaw(TARGET_REPO, f.path, DEV_BRANCH);
            migrationOps.push({ action: "create_file", file_path: f.path, content: base64ToText(content) });
        }

        // 4. Process on the TARGET repo
        const result = await processOperations(targetRepoPath, migrationOps, "", false);
        return new Response(JSON.stringify(result), { headers: { ...corsHeaders, "Content-Type": "application/json" } });
    }

    if (action === "bulk_sync_ef") {
        const { function_name, content } = payload;
        const filePath = `supabase/functions/${function_name}/index.ts`;

        // 1. Fetch ALL repositories
        const repoUrl = is_org 
            ? `https://api.github.com/orgs/${ORG_NAME}/repos?per_page=100&sort=pushed` 
            : `https://api.github.com/user/repos?per_page=100&sort=pushed`;
        
        const reposRes = await fetch(repoUrl, { headers: getHeaders() });
        const allRepos = await reposRes.json();

        const syncResults = [];
        let syncCount = 0;

        // 2. Process in small batches to avoid timeouts
        const BATCH_SIZE = 5;
        for (let i = 0; i < allRepos.length; i += BATCH_SIZE) {
            const batch = allRepos.slice(i, i + BATCH_SIZE);
            await Promise.all(batch.map(async (repo: any) => {
                try {
                    // Check if function exists in this repo
                    const check = await fetch(`https://api.github.com/repos/${repo.full_name}/contents/${filePath}?ref=${DEV_BRANCH}`, { headers: getHeaders() });
                    
                    if (check.ok) {
                        const fileData = await check.json();
                        await updateFile(repo.full_name, filePath, content, fileData.sha, DEV_BRANCH, `Conduit: Bulk Merge Sync [${function_name}]`);
                        syncResults.push({ repo: repo.name, status: "updated" });
                        syncCount++;
                    }
                } catch (e) {} // Silently skip repos where it doesn't exist or branch is missing
            }));
        }

        const logData = { success: true, sync_count: syncCount, results: [{ file: function_name, status: "bulk_merged", operations: syncResults.map(r => ({ type: "sync", message: `Updated ${r.repo}`, success: true, score: 100 })) }] };
        await supabase.from('conduit_logs').insert({ repo_name: "FLEET_WIDE", type: 'bulk_sync', data: logData });

        return new Response(JSON.stringify(logData), { headers: { ...corsHeaders, "Content-Type": "application/json" } });
    }

    if (action === "create_repo") {
        const { name, is_private } = payload;
        if (!name) throw new Error("Repository name is required");

        const url = is_org 
            ? `https://api.github.com/orgs/${ORG_NAME}/repos` 
            : `https://api.github.com/user/repos`;
        const res = await fetch(url, {
            method: "POST",
            headers: getHeaders(),
            body: JSON.stringify({
                name: name,
                private: !!is_private,
                auto_init: true // Creates an initial commit with README
            })
        });

        if (!res.ok) {
            const err = await res.json();
            throw new Error(err.message || "GitHub creation failed");
        }

        const data = await res.json();

        // 1. Fetch the SHA of the initial auto-generated commit
        let initialSha = "";
        try {
            // Small delay to let GitHub finalize the repository reference
            await new Promise(r => setTimeout(r, 1500));
            const refData = await githubFetch(data.name, `/git/ref/heads/${MAIN_BRANCH}`);
            initialSha = refData.object.sha;
        } catch (e: any) {
            console.warn("Initial SHA fetch failed (GitHub indexing):", e.message);
        }

        // 2. Create the first History Record for the new repo
        await supabase.from('conduit_history').insert({
            repo_name: data.name,
            title: "Repository Created",
            type: "Dev",
            meta: "Initial GitHub State",
            sha: initialSha,
            ops: []
        });

        return new Response(JSON.stringify({ 
            success: true, 
            repo_name: data.name, 
            full_name: data.full_name 
        }), { headers: { ...corsHeaders, "Content-Type": "application/json" } });
    }

    if (action === "proxy_web") {
        const { url } = payload;
        try {
            const targetUrl = url.startsWith('http') ? url : `https://${url}`;
            const origin = new URL(targetUrl).origin;
            
            const response = await fetch(targetUrl, {
                headers: { 
                    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
                    'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,webp,*/*;q=0.8'
                }
            });
            
            let html = await response.text();

            // --- SERVER-SIDE REWRITING ---
            html = html.replace(/(href|src|action)="\/\//g, `$1="https://`); 
            html = html.replace(/(href|src|action)="\//g, `$1="${origin}/`); 

            const bridgeScript = `
                <script>
                (function() {
                    window.IS_CONDUIT_PROXY = true;
                    const originalConsole = { ...console };
                    ['log','warn','error','info'].forEach(m => {
                        console[m] = (...args) => {
                            window.parent.postMessage({ 
                                type: 'CONDUIT_LOG', method: m, 
                                args: args.map(a => String(a)), 
                                time: new Date().toLocaleTimeString() 
                            }, '*');
                            originalConsole[m](...args);
                        };
                    });
                    document.addEventListener('click', e => {
                        const a = e.target.closest('a');
                        if(a && a.href && !a.href.startsWith('#') && !a.getAttribute('target')) {
                            e.preventDefault();
                            window.parent.postMessage({ type: 'CONDUIT_NAVIGATE', url: a.href }, '*');
                        }
                    }, true);
                    document.addEventListener('submit', e => {
                        const form = e.target;
                        if(form.method.toLowerCase() === 'get') {
                            e.preventDefault();
                            const url = new URL(form.action || location.href);
                            const params = new URLSearchParams(new FormData(form));
                            window.parent.postMessage({ type: 'CONDUIT_NAVIGATE', url: url.origin + url.pathname + '?' + params.toString() }, '*');
                        }
                    }, true);
                })();
                </script>
            `;

            const modifiedHtml = html.replace('<head>', `<head><base href="${origin}/">${bridgeScript}`);
            
            const headers = new Headers(corsHeaders);
            headers.set("Content-Type", "text/html");
            headers.delete("X-Frame-Options");
            headers.delete("Content-Security-Policy");

            return new Response(modifiedHtml, { headers });
        } catch(e) {
            return new Response(`Proxy Error: ${e.message}`, { status: 500, headers: corsHeaders });
        }
    }

    if (action === "create_cron_job") {
        const { job_name, schedule, function_slug } = payload;
        const projectUrl = Deno.env.get("SUPABASE_URL");
        const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
        const fullUrl = `${projectUrl}/functions/v1/${function_slug}`;

        const sql = `SELECT cron.schedule(
                '${job_name}',
                '${schedule}',
                $$
                SELECT net.http_post(
                    url:='${fullUrl}',
                    headers:='{"Content-Type": "application/json", "Authorization": "Bearer ${serviceKey}"}'::jsonb,
                    body:=jsonb_build_object('job', '${job_name}', 'triggered_at', now())
                )
                $$
        );`;

        const sqlRes = await fetch("https://xvldfsmxskhemkslsbym.supabase.co/functions/v1/sql-executor", {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Authorization': `Bearer ${Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")}`,
                'apikey': Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")
            },
            body: JSON.stringify({ query: sql })
        });

        const rawSqlText = await sqlRes.text();
        console.log("[CRON_SQL_RAW]:", rawSqlText);
        
        let sqlData;
        try {
            sqlData = JSON.parse(rawSqlText);
        } catch(e) {
            throw new Error(`SQL Executor returned invalid JSON: ${rawSqlText.substring(0, 100)}`);
        }

        if (sqlData.error) throw new Error(sqlData.error);

        await supabase.from('conduit_logs').insert({
            repo_name: TARGET_REPO,
            type: 'cron_created',
            data: { job_name, schedule, function_slug, status: "Success" }
        });

        return new Response(JSON.stringify({ success: true, message: `Cron job '${job_name}' scheduled.` }), { headers: { ...corsHeaders, "Content-Type": "application/json" } });
    }

    if (action === "fetch_cron_jobs") {
        const sql = "SELECT jobid, schedule, command, active, jobname FROM cron.job ORDER BY jobid DESC;";
        try {
            const res = await fetch("https://xvldfsmxskhemkslsbym.supabase.co/functions/v1/sql-executor", {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    'Authorization': `Bearer ${Deno.env.get("SUPABASE_ANON_KEY")}`,
                    'apikey': Deno.env.get("SUPABASE_ANON_KEY")
                },
                body: JSON.stringify({ query: sql })
            });
            const result = await res.json();
            // If the executor returned a standardized object, use its data member
            const finalData = result.data || (Array.isArray(result) ? result : []);
            return new Response(JSON.stringify({ data: finalData }), { headers: { ...corsHeaders, 'Content-Type': 'application/json' } });
        } catch (e) {
            return new Response(JSON.stringify({ data: [], error: e.message }), { headers: { ...corsHeaders, 'Content-Type': 'application/json' } });
        }
    }

    if (action === "unschedule_cron_job") {
        const { job_name } = payload;
        const sql = `SELECT cron.unschedule('${job_name}');`;
        const res = await fetch("https://xvldfsmxskhemkslsbym.supabase.co/functions/v1/sql-executor", {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Authorization': `Bearer ${Deno.env.get("SUPABASE_ANON_KEY")}`,
                'apikey': Deno.env.get("SUPABASE_ANON_KEY")
            },
            body: JSON.stringify({ query: sql })
        });
        const data = await res.json();
        return new Response(JSON.stringify(data), { headers: { ...corsHeaders, 'Content-Type': 'application/json' } });
    }

    if (action === "toggle_cron_job") {
        const { job_id, active } = payload;
        // Use official pg_cron function to avoid permission issues on system tables
        const sql = `SELECT cron.alter_job(${job_id}, active := ${active});`;
        const res = await fetch("https://xvldfsmxskhemkslsbym.supabase.co/functions/v1/sql-executor", {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Authorization': `Bearer ${Deno.env.get("SUPABASE_ANON_KEY")}`,
                'apikey': Deno.env.get("SUPABASE_ANON_KEY")
            },
            body: JSON.stringify({ query: sql })
        });
        const data = await res.json();
        return new Response(JSON.stringify(data), { headers: { ...corsHeaders, 'Content-Type': 'application/json' } });
    }

    if (action === "alter_cron_job") {
        const { job_id, schedule, function_slug } = payload;
        const projectUrl = Deno.env.get("SUPABASE_URL");
        const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
        const fullUrl = `${projectUrl}/functions/v1/${function_slug}`;

        const sql = `SELECT cron.alter_job(${job_id}, schedule := '${schedule}', command := $SELECT net.http_post(url:='${fullUrl}', headers:='{"Content-Type": "application/json", "Authorization": "Bearer ${serviceKey}"}'::jsonb, body:=jsonb_build_object('job_id', '${job_id}', 'triggered_at', now()))$);`;
        const res = await fetch("https://xvldfsmxskhemkslsbym.supabase.co/functions/v1/sql-executor", {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Authorization': `Bearer ${Deno.env.get("SUPABASE_ANON_KEY")}`,
                'apikey': Deno.env.get("SUPABASE_ANON_KEY")
            },
            body: JSON.stringify({ query: sql })
        });
        const data = await res.json();
        return new Response(JSON.stringify(data), { headers: { ...corsHeaders, 'Content-Type': 'application/json' } });
    }

    if (action === "rollback") {
        if(!ref_sha) throw new Error("Target SHA required");
        await githubFetch(TARGET_REPO, `/git/refs/heads/${DEV_BRANCH}`, { method: "PATCH", body: JSON.stringify({ sha: ref_sha, force: true }) });
        // Store the SHA in the log data so the frontend can link it to History entries
        await supabase.from('conduit_logs').insert({ 
            repo_name: TARGET_REPO, 
            type: 'rollback', 
            data: { 
                message: `Rolled back to ${ref_sha.substring(0,7)}`, 
                sha: ref_sha 
            } 
        });
        return new Response(JSON.stringify({ success: true, sha: ref_sha }), { headers: { ...corsHeaders, "Content-Type": "application/json" } });
    }
    if (action === "update_note") {
        const { error } = await supabase.from('conduit_history').update({ note: payload.note }).eq('id', payload.id);
        if (error) throw error;
        return new Response(JSON.stringify({ success: true }), { headers: { ...corsHeaders, "Content-Type": "application/json" } });
    }
    if (action === "delete_history") {
        const { error } = await supabase.from('conduit_history').delete().eq('id', payload.id);
        if (error) throw error;
        return new Response(JSON.stringify({ success: true }), { headers: { ...corsHeaders, "Content-Type": "application/json" } });
    }
    if (action === "delete_log") {
        const { error } = await supabase.from('conduit_logs').delete().eq('id', payload.id);
        if (error) throw error;
        return new Response(JSON.stringify({ success: true }), { headers: { ...corsHeaders, "Content-Type": "application/json" } });
    }
    if (action === "delete_run") { await githubFetch(TARGET_REPO, `/actions/runs/${run_id}`, { method: "DELETE" }); return new Response(JSON.stringify({ success: true }), { headers: { ...corsHeaders, "Content-Type": "application/json" } }); }
    if (action === "cancel_run") { await githubFetch(TARGET_REPO, `/actions/runs/${run_id}/cancel`, { method: "POST" }); return new Response(JSON.stringify({ success: true }), { headers: { ...corsHeaders, "Content-Type": "application/json" } }); }

    if (action === "toggle_favorite") {
        const { target_id, category, metadata } = payload;
        const { data: existing } = await supabase.from('conduit_favorites').select('id').eq('repo_name', TARGET_REPO).eq('target_id', String(target_id)).maybeSingle();
        
        if (existing) {
            await supabase.from('conduit_favorites').delete().eq('id', existing.id);
            return new Response(JSON.stringify({ success: true, favorited: false }), { headers: { ...corsHeaders, "Content-Type": "application/json" } });
        } else {
            await supabase.from('conduit_favorites').insert({ repo_name: TARGET_REPO, target_id: String(target_id), category, metadata });
            return new Response(JSON.stringify({ success: true, favorited: true }), { headers: { ...corsHeaders, "Content-Type": "application/json" } });
        }
    }

    if (action === "fetch_favorites") {
        const { data } = await supabase.from('conduit_favorites').select('*').eq('repo_name', TARGET_REPO).order('created_at', { ascending: false });
        return new Response(JSON.stringify({ favorites: data || [] }), { headers: { ...corsHeaders, "Content-Type": "application/json" } });
    }
    if (action === "create_checkpoint") {
        const note = payload.note || "Manual Checkpoint";
        const ref = await githubFetch(TARGET_REPO, `/git/ref/heads/${DEV_BRANCH}`);
        const { error } = await supabase.from('conduit_history').insert({ 
            repo_name: TARGET_REPO, 
            title: note, 
            type: "Checkpoint", 
            meta: "No changes",
            ops: [], 
            sha: ref.object.sha 
        });
        if (error) throw new Error(`Database Error: ${error.message}`);
        return new Response(JSON.stringify({ success: true }), { headers: { ...corsHeaders, "Content-Type": "application/json" } });
    }

    if (action === "fetch_chats") {
        const { data, error } = await supabase.from('conduit_history')
            .select('id, title, created_at')
            .eq('repo_name', TARGET_REPO)
            .eq('type', 'Chat')
            .order('created_at', { ascending: false })
            .limit(20);
            
        if (error) {
            return new Response(JSON.stringify({ chats: [], error: error.message }), { headers: { ...corsHeaders, "Content-Type": "application/json" } });
        }
        
        return new Response(JSON.stringify({ chats: data || [] }), { headers: { ...corsHeaders, "Content-Type": "application/json" } });
    }

    if (action === "load_chat") {
        const { data } = await supabase.from('conduit_history').select('ops').eq('id', chat_id).single();
        return new Response(JSON.stringify({ messages: data?.ops || [] }), { headers: { ...corsHeaders, "Content-Type": "application/json" } });
    }

    // --- STORAGE ACTIONS ---
    if (action === "list_buckets") {
        const { data, error } = await supabase.storage.listBuckets();
        if (error) throw error;
        return new Response(JSON.stringify({ buckets: data }), { headers: { ...corsHeaders, 'Content-Type': 'application/json' } });
    }

    if (action === "list_storage_objects") {
        const { bucket, path, sort_by, sort_order, offset } = payload;
        const { data, error } = await supabase.storage.from(bucket).list(path || '', {
            limit: 100, 
            offset: offset || 0, 
            sortBy: { 
                column: sort_by || 'name', 
                order: sort_order || 'asc' 
            }
        });
        if (error) throw error;
        return new Response(JSON.stringify({ objects: data }), { headers: { ...corsHeaders, 'Content-Type': 'application/json' } });
    }

    if (action === "upload_storage_object") {
        const { bucket, path, file_body, file_type } = payload;
        const binary = Uint8Array.from(atob(file_body), c => c.charCodeAt(0));
        const { data, error } = await supabase.storage.from(bucket).upload(path, binary, {
            contentType: file_type, upsert: true
        });
        if (error) throw error;
        return new Response(JSON.stringify({ data }), { headers: { ...corsHeaders, 'Content-Type': 'application/json' } });
    }

    if (action === "delete_storage_objects") {
        const { bucket, paths } = payload;
        const { data, error } = await supabase.storage.from(bucket).remove(paths);
        if (error) throw error;
        return new Response(JSON.stringify({ data }), { headers: { ...corsHeaders, 'Content-Type': 'application/json' } });
    }

    if (action === "move_storage_object") {
        const { bucket, from_path, to_path } = payload;
        const { data: copyData, error: copyError } = await supabase.storage.from(bucket).copy(from_path, to_path);
        if (copyError) throw copyError;
        const { error: removeError } = await supabase.storage.from(bucket).remove([from_path]);
        if (removeError) throw removeError;
        return new Response(JSON.stringify({ success: true }), { headers: { ...corsHeaders, 'Content-Type': 'application/json' } });
    }

    if (action === "get_storage_signed_url") {
        const { bucket, path } = payload;
        const { data, error } = await supabase.storage.from(bucket).createSignedUrl(path, 3600); // 1 hour
        if (error) throw error;
        return new Response(JSON.stringify({ url: data.signedUrl }), { headers: { ...corsHeaders, 'Content-Type': 'application/json' } });
    }

    if (action === "save_chat") {
        // Defensively ensure messages is an array
        const chatMsgs = Array.isArray(messages) ? messages : [];
        
        // Robust Title Generation
        let chatTitle = title || "New Chat";
        if (!title && chatMsgs.length > 0) {
            const firstUserMsg = chatMsgs.find((m: any) => m.role === 'user');
            if (firstUserMsg && firstUserMsg.text) {
                chatTitle = firstUserMsg.text.substring(0, 50);
                if (firstUserMsg.text.length > 50) chatTitle += "...";
            }
        }

        let res;
        if (chat_id) {
            // Update existing session
            res = await supabase.from('conduit_history')
                .update({ ops: chatMsgs, title: chatTitle, repo_name: TARGET_REPO })
                .eq('id', chat_id)
                .select();
        } else {
            // Create new session
            res = await supabase.from('conduit_history')
                .insert({ 
                    repo_name: TARGET_REPO, 
                    type: 'Chat', 
                    title: chatTitle, 
                    ops: chatMsgs 
                })
                .select();
        
        }

        if (res.error) {
            console.error("Save Chat Error:", res.error);
            throw new Error(`DB Error: ${res.error.message}`);
        }
        
        // Handle case where data might be empty (though unlikely with .select())
        const savedId = res.data && res.data.length > 0 ? res.data[0].id : chat_id;
        
        return new Response(JSON.stringify({ success: true, chat_id: savedId }), { headers: { ...corsHeaders, "Content-Type": "application/json" } });
    }

    return new Response(JSON.stringify({ error: `Invalid Action: ${action}` }), { status: 400, headers: { ...corsHeaders, "Content-Type": "application/json" } });
  } catch (error: any) {
    return new Response(JSON.stringify({ error: error.message }), { status: 500, headers: { ...corsHeaders, "Content-Type": "application/json" } });
  }
});