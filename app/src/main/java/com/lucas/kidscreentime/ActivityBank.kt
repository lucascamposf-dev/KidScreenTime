package com.lucas.kidscreentime

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/**
 * Pergunta de resposta DIGITADA.
 * [answer] é a resposta esperada (curta); [accepted] são variações aceitas.
 * [type] é a chave da habilidade (ex.: "letra_inicial"), usada no nível adaptativo e no relatório.
 * [speech] é o texto lido em voz alta; [explanation] aparece quando a criança erra.
 * [numeric] = true abre teclado numérico. [retries] = quantas vezes já voltou para revisão.
 */
data class EduQuestion(
    val text: String,
    val answer: String,
    val topic: String = "português",
    val type: String = "livre",
    val accepted: List<String> = emptyList(),
    val numeric: Boolean = false,
    val speech: String = "",
    val explanation: String = "",
    val source: String = SRC_LOCAL,
    val retries: Int = 0
) {

    fun isCorrect(given: String): Boolean = AnswerMatcher.matches(given, listOf(answer) + accepted)

    /** Texto para o leitor de voz (sem emojis nem quebras de linha). */
    fun speechText(): String =
        speech.ifBlank { text.replace(Regex("[^\\p{L}\\p{N}?!.,:+\\- ]"), " ").replace(Regex("\\s+"), " ").trim().lowercase() }

    fun toJson(): JSONObject = JSONObject()
        .put("t", text)
        .put("a", answer)
        .put("tp", topic)
        .put("ty", type)
        .put("x", JSONArray(accepted))
        .put("n", numeric)
        .put("sp", speech)
        .put("ex", explanation)
        .put("src", source)
        .put("rt", retries)

    companion object {
        const val SRC_LOCAL = "local"
        const val SRC_AI = "ia"

        fun normalize(s: String): String = AnswerMatcher.basic(s)

        /**
         * Itens sem "src" são do formato antigo (perguntas da IA sem verificação) e são descartados
         * — eram a origem dos gabaritos errados.
         */
        fun fromJson(o: JSONObject): EduQuestion? = try {
            val text = o.getString("t")
            val answer = o.optString("a", "")
            if (text.isBlank() || answer.isBlank() || !o.has("src")) {
                null
            } else {
                val arr = o.optJSONArray("x")
                val accepted = if (arr == null) emptyList() else List(arr.length()) { arr.getString(it) }
                EduQuestion(
                    text = text,
                    answer = answer,
                    topic = o.optString("tp", "português"),
                    type = o.optString("ty", "livre"),
                    accepted = accepted,
                    numeric = o.optBoolean("n", false),
                    speech = o.optString("sp", ""),
                    explanation = o.optString("ex", ""),
                    source = o.optString("src", SRC_LOCAL),
                    retries = o.optInt("rt", 0)
                )
            }
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Fonte das atividades:
 *  - revisão: perguntas erradas voltam 2–3 perguntas depois (até 2 vezes), só no mesmo bloqueio;
 *  - perguntas de conhecimento geradas pela IA (Groq) e já verificadas, guardadas no aparelho;
 *  - perguntas locais (português e matemática) com gabarito calculado, no nível de cada habilidade.
 * A pergunta em andamento fica salva: reabrir a tela de bloqueio não troca de pergunta.
 * As últimas [MAX_RECENT] perguntas ficam salvas para não se repetirem, mesmo entre bloqueios.
 */
object ActivityBank {

    private const val MAX_QUEUE = 40
    private const val AI_SHARE_PERCENT = 30
    private const val MAX_RETRIES = 2
    private const val MAX_RECENT = 60

    @Synchronized
    fun queueSize(context: Context): Int = loadList(PrefsHelper.getQuestionQueueJson(context)).size

    @Synchronized
    fun push(context: Context, questions: List<EduQuestion>) {
        val queue = loadList(PrefsHelper.getQuestionQueueJson(context))
        val existing = queue.map { it.text }.toMutableSet()
        for (q in questions) if (existing.add(q.text)) queue.add(q)
        while (queue.size > MAX_QUEUE) queue.removeAt(0)
        PrefsHelper.setQuestionQueueJson(context, saveList(queue))
    }

    // ---------- Pergunta em andamento ----------

    /** Pergunta atual (sorteia e salva uma nova se não houver). */
    @Synchronized
    fun current(context: Context): EduQuestion {
        PrefsHelper.getCurrentQuestionJson(context)
            ?.let { runCatching { EduQuestion.fromJson(JSONObject(it)) }.getOrNull() }
            ?.let { return it }
        val q = pickNext(context)
        PrefsHelper.setCurrentQuestion(context, q.toJson().toString(), System.currentTimeMillis())
        return q
    }

    /** Encerra a pergunta atual; a próxima chamada de [current] sorteia outra. */
    @Synchronized
    fun finishCurrent(context: Context) {
        PrefsHelper.clearCurrentQuestion(context)
    }

    /** Pergunta errada: volta para revisão daqui a 2–3 perguntas. */
    @Synchronized
    fun scheduleRetry(context: Context, q: EduQuestion) {
        if (q.retries >= MAX_RETRIES) return
        val arr = retryArray(context)
        for (i in 0 until arr.length()) {
            if (arr.getJSONObject(i).getJSONObject("q").optString("t") == q.text) return
        }
        arr.put(JSONObject().put("q", q.copy(retries = q.retries + 1).toJson()).put("w", Random.nextInt(2, 4)))
        PrefsHelper.setRetryQueueJson(context, arr.toString())
    }

    /** Remove uma pergunta da revisão (ex.: pais marcaram como errada). */
    @Synchronized
    fun dropRetry(context: Context, text: String) {
        val arr = retryArray(context)
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.getJSONObject("q").optString("t") != text) out.put(o)
        }
        PrefsHelper.setRetryQueueJson(context, out.toString())
    }

    // ---------- Sorteio ----------

    private fun pickNext(context: Context): EduQuestion {
        val recent = recentTexts(context)
        val q = dueRetry(context) ?: aiQuestion(context, recent) ?: localQuestion(context, recent)
        recent.remove(q.text)
        recent.add(q.text)
        PrefsHelper.setRecentQuestionsJson(context, JSONArray(recent.takeLast(MAX_RECENT)).toString())
        return q
    }

    private fun recentTexts(context: Context): MutableList<String> {
        val arr = PrefsHelper.getRecentQuestionsJson(context)?.let { runCatching { JSONArray(it) }.getOrNull() }
            ?: return mutableListOf()
        return MutableList(arr.length()) { arr.optString(it) }
    }

    /** Desconta 1 de espera em cada revisão e devolve a primeira que venceu. */
    private fun dueRetry(context: Context): EduQuestion? {
        val arr = retryArray(context)
        if (arr.length() == 0) return null
        var due: EduQuestion? = null
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val w = o.optInt("w", 0) - 1
            if (due == null && w <= 0) {
                due = EduQuestion.fromJson(o.getJSONObject("q"))
                if (due != null) continue
            }
            out.put(o.put("w", w))
        }
        PrefsHelper.setRetryQueueJson(context, out.toString())
        return due
    }

    /** Tira a próxima pergunta da IA da fila, descartando as que já apareceram. */
    private fun aiQuestion(context: Context, recent: List<String>): EduQuestion? {
        if (Random.nextInt(100) >= AI_SHARE_PERCENT) return null
        val queue = loadList(PrefsHelper.getQuestionQueueJson(context))
        queue.removeAll { it.text in recent }
        val q = queue.removeFirstOrNull()
        PrefsHelper.setQuestionQueueJson(context, saveList(queue))
        return q
    }

    /** Sorteia uma habilidade, dando mais peso às que a criança erra mais. */
    private fun localQuestion(context: Context, recent: List<String>): EduQuestion {
        val skills = LocalQuestions.skills
        val weights = skills.map { 1.0 + 2.0 * Progress.errorRate(context, it.key) }
        repeat(30) {
            var r = Random.nextDouble() * weights.sum()
            var chosen = skills.last()
            for ((i, s) in skills.withIndex()) {
                r -= weights[i]
                if (r <= 0) { chosen = s; break }
            }
            val q = LocalQuestions.make(chosen.key, Progress.level(context, chosen.key))
            if (q != null && q.text !in recent) return q
        }
        return LocalQuestions.make("soma", 1)!!
    }

    // ---------- Persistência ----------

    private fun retryArray(context: Context): JSONArray =
        PrefsHelper.getRetryQueueJson(context)?.let { runCatching { JSONArray(it) }.getOrNull() } ?: JSONArray()

    private fun loadList(json: String?): MutableList<EduQuestion> {
        json ?: return mutableListOf()
        return try {
            val arr = JSONArray(json)
            val out = mutableListOf<EduQuestion>()
            for (i in 0 until arr.length()) EduQuestion.fromJson(arr.getJSONObject(i))?.let { out.add(it) }
            out
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    private fun saveList(queue: List<EduQuestion>): String {
        val arr = JSONArray()
        queue.forEach { arr.put(it.toJson()) }
        return arr.toString()
    }
}
