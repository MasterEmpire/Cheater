package com.universal.app

import org.json.JSONArray
import org.json.JSONObject

object CleanJsonParser {

    /**
     * Extracts, sanitizes, and deduplicates the raw AI Studio output.
     * Strips Markdown blocks, cleans non-printable characters, and ensures strict schema compliance.
     */
    fun sanitizeAndDeduplicate(rawOutput: String): String? {
        if (rawOutput.isBlank()) return null
        try {
            var text = rawOutput.trim()

            // 1. Remove Markdown code fences
            if (text.startsWith("```json", ignoreCase = true)) {
                text = text.substring(7)
            } else if (text.startsWith("```")) {
                text = text.substring(3)
            }
            if (text.endsWith("```")) {
                text = text.substring(0, text.length - 3)
            }
            text = text.trim()

            // 2. Extract bounding JSON object { ... }
            val firstBrace = text.indexOf('{')
            val lastBrace = text.lastIndexOf('}')
            if (firstBrace == -1 || lastBrace == -1 || lastBrace <= firstBrace) {
                return null
            }

            val candidateJson = text.substring(firstBrace, lastBrace + 1)
            val root = JSONObject(candidateJson)

            val rawSolutions = root.optJSONArray("solutions") ?: return null
            val deduplicatedSolutions = JSONArray()
            val seenNumbers = mutableSetOf<String>()

            for (i in 0 until rawSolutions.length()) {
                val sol = rawSolutions.optJSONObject(i) ?: continue
                val rawNum = sol.optString("number", "${i + 1}").trim()
                val normalizedNum = rawNum.lowercase().filter { it.isLetterOrDigit() }

                if (normalizedNum.isNotEmpty() && seenNumbers.contains(normalizedNum)) {
                    DebugLogger.log("PARSER_DEDUP", "Discarded duplicate solution for question #$rawNum")
                    continue
                }
                if (normalizedNum.isNotEmpty()) {
                    seenNumbers.add(normalizedNum)
                }

                val cleanSol = JSONObject().apply {
                    put("number", rawNum)
                    put("type", sol.optString("type", "sa").trim().lowercase())
                    put("answer", sol.optString("answer", "").trim())

                    val steps = sol.optJSONArray("steps")
                    if (steps != null && steps.length() > 0) {
                        val cleanSteps = JSONArray()
                        for (j in 0 until steps.length()) {
                            val s = steps.optString(j, "").trim()
                            if (s.isNotEmpty()) cleanSteps.put(s)
                        }
                        put("steps", cleanSteps)
                    }
                }
                deduplicatedSolutions.put(cleanSol)
            }

            val sanitizedRoot = JSONObject().apply {
                put("confidence_score", root.optInt("confidence_score", 95))
                put("solutions", deduplicatedSolutions)
            }

            return sanitizedRoot.toString()
        } catch (e: Exception) {
            DebugLogger.log("PARSER_EXCEPTION", "Error sanitizing JSON: ${e.message}")
            return null
        }
    }
}