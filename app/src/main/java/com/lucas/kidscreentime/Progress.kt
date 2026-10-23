package com.lucas.kidscreentime

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Nível adaptativo por habilidade (1 a 3), guardado no aparelho.
 * Olha as últimas [WINDOW] respostas: acertou >= 80% sobe, < 50% desce.
 * Ao mudar de nível o histórico zera, para medir o nível novo do zero.
 */
object Progress {

    private const val WINDOW = 10
    private const val MIN_SAMPLES = 6
    private const val LEVEL_UP_RATE = 0.8
    private const val LEVEL_DOWN_RATE = 0.5
    const val MAX_LEVEL = 3

    @Synchronized
    fun level(c: Context, skill: String): Int = load(c).optJSONObject(skill)?.optInt("l", 1) ?: 1

    /** Taxa de erro recente (0.0 se ainda não há dados). */
    @Synchronized
    fun errorRate(c: Context, skill: String): Double {
        val h = load(c).optJSONObject(skill)?.optString("h", "").orEmpty()
        if (h.isEmpty()) return 0.0
        return h.count { it == '0' }.toDouble() / h.length
    }

    /** Registra um resultado; devolve o nível (possivelmente novo) da habilidade. */
    @Synchronized
    fun record(c: Context, skill: String, correct: Boolean): Int {
        val all = load(c)
        val o = all.optJSONObject(skill) ?: JSONObject().put("l", 1).put("h", "")
        var level = o.optInt("l", 1)
        var h = (o.optString("h", "") + if (correct) "1" else "0").takeLast(WINDOW)
        if (h.length >= MIN_SAMPLES) {
            val rate = h.count { it == '1' }.toDouble() / h.length
            if (rate >= LEVEL_UP_RATE && level < MAX_LEVEL) {
                level++; h = ""
            } else if (rate < LEVEL_DOWN_RATE && level > 1) {
                level--; h = ""
            }
        }
        all.put(skill, o.put("l", level).put("h", h))
        PrefsHelper.setSkillProgressJson(c, all.toString())
        return level
    }

    /** Desfaz o último erro (pergunta marcada como errada pelos pais). */
    @Synchronized
    fun undoLastWrong(c: Context, skill: String) {
        val all = load(c)
        val o = all.optJSONObject(skill) ?: return
        val h = o.optString("h", "")
        if (h.endsWith("0")) {
            all.put(skill, o.put("h", h.dropLast(1)))
            PrefsHelper.setSkillProgressJson(c, all.toString())
        }
    }

    private fun load(c: Context): JSONObject =
        PrefsHelper.getSkillProgressJson(c)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
}

/** Histórico de tentativas (últimas [MAX]) para o relatório dos pais e o [GuessDetector]. */
object AttemptLog {

    private const val MAX = 200

    @Synchronized
    fun add(c: Context, r: AttemptRecord) {
        val arr = loadArray(c)
        arr.put(
            JSONObject()
                .put("ts", r.timestampMs)
                .put("sh", r.shownAtMs)
                .put("q", r.questionText)
                .put("g", r.givenAnswer)
                .put("ok", r.isCorrect)
                .put("tp", r.topic)
                .put("a", r.correctAnswer)
                .put("sk", r.skill)
                .put("src", r.source)
        )
        val out = JSONArray()
        for (i in maxOf(0, arr.length() - MAX) until arr.length()) out.put(arr.get(i))
        PrefsHelper.setAttemptLogJson(c, out.toString())
    }

    @Synchronized
    fun all(c: Context): List<AttemptRecord> {
        val arr = loadArray(c)
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            AttemptRecord(
                timestampMs = o.optLong("ts"),
                questionText = o.optString("q"),
                givenAnswer = o.optString("g"),
                isCorrect = o.optBoolean("ok"),
                topic = o.optString("tp", "português"),
                correctAnswer = o.optString("a"),
                shownAtMs = o.optLong("sh", 0L),
                skill = o.optString("sk"),
                source = o.optString("src", EduQuestion.SRC_LOCAL),
                flagged = o.optBoolean("fl", false)
            )
        }
    }

    /** Marca as tentativas dessa pergunta como inválidas (pergunta com problema). */
    @Synchronized
    fun flag(c: Context, questionText: String) {
        val arr = loadArray(c)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("q") == questionText) o.put("fl", true)
        }
        PrefsHelper.setAttemptLogJson(c, arr.toString())
    }

    private fun loadArray(c: Context): JSONArray =
        PrefsHelper.getAttemptLogJson(c)?.let { runCatching { JSONArray(it) }.getOrNull() } ?: JSONArray()
}
