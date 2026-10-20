package com.lucas.kidscreentime

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import kotlin.random.Random

/**
 * Pergunta de resposta DIGITADA.
 * [answer] é a resposta esperada (curta); [accepted] são variações aceitas.
 * [numeric] = true abre teclado numérico.
 */
data class EduQuestion(
    val text: String,
    val answer: String,
    val topic: String = "português",
    val type: String = "livre",
    val accepted: List<String> = emptyList(),
    val numeric: Boolean = false
) {

    /** Ignora maiúsculas, espaços nas pontas/duplicados e acentos. */
    fun isCorrect(given: String): Boolean {
        val g = normalize(given)
        if (g.isEmpty()) return false
        return (listOf(answer) + accepted).any { normalize(it) == g }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("t", text)
        .put("a", answer)
        .put("tp", topic)
        .put("ty", type)
        .put("x", JSONArray(accepted))
        .put("n", numeric)

    companion object {
        fun normalize(s: String): String =
            Normalizer.normalize(s.trim(), Normalizer.Form.NFD)
                .replace(Regex("\\p{Mn}+"), "")
                .lowercase()
                .replace(Regex("\\s+"), " ")

        /** Itens no formato antigo (múltipla escolha, sem chave "a") são descartados. */
        fun fromJson(o: JSONObject): EduQuestion? = try {
            val text = o.getString("t")
            val answer = o.optString("a", "")
            if (text.isBlank() || answer.isBlank()) {
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
                    numeric = o.optBoolean("n", false)
                )
            }
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Fonte das atividades:
 *  - fila de perguntas geradas pela IA (Groq), guardada no aparelho (funciona offline);
 *  - continhas de matemática geradas localmente (sempre disponíveis, nunca erram a conta).
 */
object ActivityBank {

    private const val MAX_QUEUE = 40

    @Synchronized
    fun queueSize(context: Context): Int = loadQueue(context).size

    @Synchronized
    fun push(context: Context, questions: List<EduQuestion>) {
        val queue = loadQueue(context)
        val existing = queue.map { it.text }.toMutableSet()
        for (q in questions) if (existing.add(q.text)) queue.add(q)
        while (queue.size > MAX_QUEUE) queue.removeAt(0)
        saveQueue(context, queue)
    }

    /** Próxima pergunta: mistura IA (65%) com matemática local. Sem internet/fila, cai na matemática. */
    @Synchronized
    fun next(context: Context): EduQuestion {
        val queue = loadQueue(context)
        if (queue.isNotEmpty() && Random.nextInt(100) < 65) {
            val q = queue.removeAt(0)
            saveQueue(context, queue)
            return q
        }
        return mathQuestion()
    }

    fun mathQuestion(): EduQuestion {
        val a = Random.nextInt(1, 10)
        val b = Random.nextInt(1, 10)
        return if (Random.nextBoolean()) {
            build("Quanto é $a + $b ?", a + b)
        } else {
            val bigger = maxOf(a, b)
            val smaller = minOf(a, b)
            build("Quanto é $bigger - $smaller ?", bigger - smaller)
        }
    }

    private fun build(text: String, correct: Int): EduQuestion =
        EduQuestion(
            text = text,
            answer = correct.toString(),
            topic = "matemática",
            type = "conta",
            numeric = true
        )

    private fun loadQueue(context: Context): MutableList<EduQuestion> {
        val json = PrefsHelper.getQuestionQueueJson(context) ?: return mutableListOf()
        return try {
            val arr = JSONArray(json)
            val out = mutableListOf<EduQuestion>()
            for (i in 0 until arr.length()) EduQuestion.fromJson(arr.getJSONObject(i))?.let { out.add(it) }
            out
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    private fun saveQueue(context: Context, queue: List<EduQuestion>) {
        val arr = JSONArray()
        queue.forEach { arr.put(it.toJson()) }
        PrefsHelper.setQuestionQueueJson(context, arr.toString())
    }
}