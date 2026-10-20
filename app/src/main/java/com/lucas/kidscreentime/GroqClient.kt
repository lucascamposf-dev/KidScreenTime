package com.lucas.kidscreentime

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Gera atividades educativas com a API gratuita do Groq (formato compatível com OpenAI).
 * Chamada BLOQUEANTE: rode sempre fora da thread principal (Dispatchers.IO).
 * Qualquer falha devolve lista vazia — o app continua funcionando com as continhas locais.
 */
object GroqClient {

    private const val TAG = "GroqClient"
    private const val API_URL = "https://api.groq.com/openai/v1/chat/completions"

    // Se um modelo for descontinuado, troque aqui (veja console.groq.com/docs/models).
    private val MODELS = listOf("llama-3.3-70b-versatile", "llama-3.1-8b-instant")

    private const val SYSTEM_PROMPT =
        "Você cria atividades educativas para uma criança brasileira de 6 anos que está aprendendo a ler. " +
        "Use português do Brasil, frases MUITO curtas (até 8 palavras) e palavras simples, pois ela vai LER SOZINHA, sem ajuda. Prefira palavras de 1 a 3 sílabas e letras MAIÚSCULAS nas palavras-alvo. " +
        "Responda SOMENTE com JSON válido, sem texto fora do JSON."

    fun isConfigured(): Boolean = BuildConfig.GROQ_API_KEY.isNotBlank()

    fun fetchQuestions(count: Int = 10): List<EduQuestion> {
        if (!isConfigured()) return emptyList()
        for (model in MODELS) {
            try {
                val result = request(model, count)
                if (result.isNotEmpty()) return result
            } catch (e: Exception) {
                Log.w(TAG, "Falha com $model: ${e.message}")
            }
        }
        return emptyList()
    }

    private fun userPrompt(count: Int): String =
        "Crie $count perguntas de múltipla escolha, todas diferentes entre si (sorteio #${(1000..9999).random()}). " +
        "Varie os temas: letra inicial de uma palavra, completar palavra simples, sílaba inicial, rimas, " +
        "animais e natureza, cores e formas, partes do corpo, 'qual é diferente', dias da semana, frutas. " +
        "NÃO faça perguntas de contas de matemática. " +
        "Cada pergunta tem exatamente 3 opções curtas e apenas UMA correta (não repita a opção). " +
        "Formato exato: {\"questions\":[{\"text\":\"Com qual letra começa BOLA?\",\"options\":[\"B\",\"M\",\"P\"],\"answer_index\":0}]}"

    private fun request(model: String, count: Int): List<EduQuestion> {
        val body = JSONObject()
            .put("model", model)
            .put("temperature", 0.9)
            .put("max_tokens", 2500)
            .put("response_format", JSONObject().put("type", "json_object"))
            .put(
                "messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                    .put(JSONObject().put("role", "user").put("content", userPrompt(count)))
            )

        val conn = (URL(API_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 40_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer ${BuildConfig.GROQ_API_KEY}")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", "KidScreenTime/1.0")
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IOException("HTTP $code: ${text.take(200)}")

            val content = JSONObject(text)
                .getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content")
            return parse(content)
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(content: String): List<EduQuestion> {
        val arr = JSONObject(content).getJSONArray("questions")
        val out = mutableListOf<EduQuestion>()
        for (i in 0 until arr.length()) {
            try {
                val o = arr.getJSONObject(i)
                val text = o.getString("text").trim()
                val optsArr = o.getJSONArray("options")
                val opts = List(optsArr.length()) { optsArr.getString(it).trim() }
                val idx = o.getInt("answer_index")
                if (text.isBlank() || opts.size != 3 || opts.toSet().size != 3 || idx !in 0..2) continue

                // IA costuma colocar a resposta certa sempre na 1ª posição: embaralha.
                val order = (0..2).shuffled()
                out.add(EduQuestion(text, order.map { opts[it] }, order.indexOf(idx)))
            } catch (e: Exception) {
                // ignora item mal formado
            }
        }
        return out
    }
}
