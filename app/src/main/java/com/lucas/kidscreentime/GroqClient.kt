package com.lucas.kidscreentime

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Gera perguntas de CONHECIMENTO (animais, natureza, corpo…) com a API gratuita do Groq.
 * Letras, sílabas e contas NÃO vêm da IA: os modelos erram muito nisso, e o app
 * gera essas perguntas sozinho em [LocalQuestions] com gabarito calculado.
 *
 * Toda pergunta passa por uma CONFERÊNCIA: uma segunda chamada (temperatura 0), sem ver o gabarito,
 * lista todas as respostas certas. Pergunta confusa ou com muitas respostas é descartada; as outras
 * respostas certas passam a ser aceitas (ex.: som PIU -> PASSARINHO ou PINTINHO).
 *
 * Chamada BLOQUEANTE: rode sempre fora da thread principal (Dispatchers.IO).
 * Qualquer falha devolve lista vazia — o app continua funcionando com as perguntas locais.
 */
object GroqClient {

    private const val TAG = "GroqClient"
    private const val API_URL = "https://api.groq.com/openai/v1/chat/completions"
    private const val MAX_ANSWER_LEN = 12
    private const val MAX_TEXT_LEN = 80
    private const val MAX_VALID_ANSWERS = 3

    /** Uma palavra só: letras e hífen (ex.: "terça-feira"). */
    private val ANSWER_RE = Regex("^[\\p{L}-]{1,$MAX_ANSWER_LEN}$")

    /** Última falha da IA (vazio = tudo certo). A tela dos pais exibe isso. */
    @Volatile
    var lastError: String = ""

    // Se um modelo for descontinuado, troque aqui (veja console.groq.com/docs/models).
    private val MODELS = listOf("openai/gpt-oss-120b", "openai/gpt-oss-20b")

    private const val SYSTEM_PROMPT =
        "Você cria atividades educativas para uma criança brasileira de 6 anos que está aprendendo a ler. " +
        "Use português do Brasil, frases curtas (até 10 palavras) e palavras simples, pois ela vai LER SOZINHA, sem ajuda. " +
        "Toda pergunta deve ser uma frase COMPLETA e correta: com verbo, sem faltar palavra, terminando em \"?\". " +
        "Escreva em MAIÚSCULAS a palavra mais importante da pergunta. " +
        "Responda SOMENTE com JSON válido, sem texto fora do JSON."

    private const val CHECK_PROMPT =
        "Você é um professor revisando perguntas para uma criança de 6 anos. " +
        "Para cada pergunta, liste TODAS as respostas de uma palavra que uma criança daria e que estão certas. " +
        "Marque ok=false se a pergunta estiver errada, confusa, estranha, incompleta (faltando palavra), " +
        "com erro de português, ou se tiver muitas respostas certas diferentes. " +
        "Responda SOMENTE com JSON válido."

    fun isConfigured(): Boolean = BuildConfig.GROQ_API_KEY.isNotBlank()

    fun fetchQuestions(count: Int = 10): List<EduQuestion> {
        if (!isConfigured()) return emptyList()
        for (model in MODELS) {
            try {
                val generated = generate(model, count)
                if (generated.isEmpty()) {
                    lastError = "$model: resposta sem perguntas válidas"
                    continue
                }
                val verified = verify(generated)
                Log.i(TAG, "$model: ${generated.size} geradas, ${verified.size} aprovadas na conferência")
                if (verified.isNotEmpty()) {
                    lastError = ""
                    return verified
                }
                lastError = "$model: nenhuma pergunta passou na conferência"
            } catch (e: Exception) {
                Log.w(TAG, "Falha com $model: ${e.message}")
                lastError = "$model: ${e.message}"
            }
        }
        return emptyList()
    }

    private fun userPrompt(count: Int): String =
        "Crie $count perguntas em que a criança DIGITA a resposta, todas diferentes entre si (sorteio #${(1000..9999).random()}). " +
        "Temas: animais (som, onde vivem, o que comem), natureza, partes do corpo, cores de coisas conhecidas, " +
        "frutas, dias da semana, estações, profissões, meios de transporte. " +
        "NÃO faça perguntas sobre letras, sílabas, rimas ou contas — isso já existe no app. " +
        "A resposta deve ser UMA palavra só (até 10 letras), sem artigo, e só UMA resposta pode estar certa. " +
        "Bom: \"Qual animal faz MIAU?\" (GATO). Ruim: \"Fale um animal\" (tem muitas respostas). " +
        "Em \"accepted\" coloque só sinônimos realmente corretos (pode ser lista vazia). " +
        "Em \"explanation\" escreva uma frase curta que ensina, até 8 palavras. " +
        "Formato exato: {\"questions\":[{\"topic\":\"animais\",\"text\":\"Qual animal faz MIAU?\"," +
        "\"answer\":\"GATO\",\"accepted\":[\"GATINHO\"],\"explanation\":\"O GATO faz miau.\"}]}"

    private fun generate(model: String, count: Int): List<EduQuestion> {
        val content = chat(model, SYSTEM_PROMPT, userPrompt(count), temperature = 0.3, effort = "low")
        return parse(content)
    }

    /**
     * Conferência: o modelo mais forte lista às cegas as respostas certas. Fica a pergunta aprovada,
     * com até [MAX_VALID_ANSWERS] respostas e que inclua o gabarito; as demais passam a ser aceitas.
     */
    private fun verify(questions: List<EduQuestion>): List<EduQuestion> {
        val list = questions.mapIndexed { i, q -> "${i + 1}. ${q.text}" }.joinToString("\n")
        val prompt = "Revise as perguntas abaixo, na mesma ordem.\n$list\n" +
            "Formato exato: {\"answers\":[{\"ok\":true,\"all\":[\"PASSARINHO\",\"PINTINHO\"]}]}"
        val content = chat(MODELS.first(), CHECK_PROMPT, prompt, temperature = 0.0, effort = "medium")
        val arr = JSONObject(extractJson(content)).getJSONArray("answers")
        return questions.mapIndexedNotNull { i, q ->
            val o = arr.optJSONObject(i) ?: return@mapIndexedNotNull null
            val all = o.optJSONArray("all") ?: return@mapIndexedNotNull null
            val valid = List(all.length()) { all.optString(it).trim() }
                .filter { ANSWER_RE.matches(it) }
                .distinctBy { AnswerMatcher.canonical(it) }
            if (!o.optBoolean("ok", false) || valid.size > MAX_VALID_ANSWERS || valid.none { q.isCorrect(it) }) {
                null
            } else {
                q.copy(accepted = (q.accepted + valid.map { it.uppercase() }).distinct())
            }
        }
    }

    private fun chat(model: String, system: String, user: String, temperature: Double, effort: String): String {
        val body = JSONObject()
            .put("model", model)
            .put("temperature", temperature)
            .put("max_tokens", 4000)
            .put("reasoning_effort", effort)
            .put("response_format", JSONObject().put("type", "json_object"))
            .put(
                "messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", user))
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

            return JSONObject(text)
                .getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content")
        } finally {
            conn.disconnect()
        }
    }

    private fun extractJson(content: String): String {
        val s = content.indexOf('{')
        val e = content.lastIndexOf('}')
        return if (s >= 0 && e > s) content.substring(s, e + 1) else content
    }

    /**
     * Valida cada item: descarta sem enunciado, enunciado longo, resposta que não é UMA palavra
     * (ex.: "B (de bola)", "gato ou cão"), frase que não termina em "?" ou com menos de 3 palavras
     * (sinal de palavra faltando) e perguntas cuja resposta já está escrita no enunciado.
     */
    private fun parse(content: String): List<EduQuestion> {
        val arr = JSONObject(extractJson(content)).getJSONArray("questions")
        val out = mutableListOf<EduQuestion>()
        for (i in 0 until arr.length()) {
            try {
                val o = arr.getJSONObject(i)
                val text = o.optString("text", "").trim()
                val answer = o.optString("answer", "").trim().trimEnd('.', '!')
                if (text.length > MAX_TEXT_LEN || !text.endsWith("?") || text.split(" ").size < 3) continue
                if (!ANSWER_RE.matches(answer)) continue
                val answerNorm = AnswerMatcher.canonical(answer)
                if (answerNorm.length < 2 || AnswerMatcher.basic(text).split(" ").contains(answerNorm)) continue

                val accArr = o.optJSONArray("accepted")
                val accepted = if (accArr == null) {
                    emptyList()
                } else {
                    List(accArr.length()) { accArr.optString(it).trim() }
                        .filter { ANSWER_RE.matches(it) }
                }
                val topic = o.optString("topic", "").trim().lowercase().ifBlank { "conhecimento" }
                val explanation = o.optString("explanation", "").trim().take(MAX_TEXT_LEN)
                out.add(
                    EduQuestion(
                        text = text,
                        answer = answer.uppercase(),
                        topic = topic,
                        type = "conhecimento",
                        accepted = accepted,
                        explanation = explanation,
                        source = EduQuestion.SRC_AI
                    )
                )
            } catch (e: Exception) {
                // ignora item mal formado
            }
        }
        return out
    }
}
