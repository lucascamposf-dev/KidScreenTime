package com.lucas.kidscreentime

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/** Pergunta de múltipla escolha (3 opções, 1 correta). */
data class EduQuestion(val text: String, val options: List<String>, val correctIndex: Int) {

    fun toJson(): JSONObject = JSONObject()
        .put("t", text)
        .put("o", JSONArray(options))
        .put("c", correctIndex)

    companion object {
        fun fromJson(o: JSONObject): EduQuestion? = try {
            val arr = o.getJSONArray("o")
            val opts = List(arr.length()) { arr.getString(it) }
            EduQuestion(o.getString("t"), opts, o.getInt("c"))
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

    private fun build(text: String, correct: Int): EduQuestion {
        val set = linkedSetOf(correct)
        while (set.size < 3) {
            val d = correct + Random.nextInt(-3, 4)
            if (d >= 0) set.add(d)
        }
        val list = set.toList().shuffled()
        return EduQuestion(text, list.map { it.toString() }, list.indexOf(correct))
    }

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
