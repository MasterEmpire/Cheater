package com.universal.app

object SupabaseConfig {
    const val PROJECT_ID = "xvldfsmxskhemkslsbym"
    const val BASE_URL = "https://$PROJECT_ID.supabase.co"
    const val ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Inh2bGRmc214c2toZW1rc2xzYnltIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NjI2ODgxNzksImV4cCI6MjA3ODI2NDE3OX0.5arqrx8Tt7v-hpXpo_ncoK4IX8th9IibxAuv93SSoOU"

    const val FUNCTION_URL = "$BASE_URL/functions/v1/upload-image"
    const val STORAGE_URL = "$BASE_URL/storage/v1/object/images/"
    const val REST_URL = "$BASE_URL/rest/v1/processed_images"
}
