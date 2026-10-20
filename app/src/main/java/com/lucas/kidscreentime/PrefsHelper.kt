package com.lucas.kidscreentime

import android.content.Context
import java.security.MessageDigest

/**
 * Estado do controle de tempo de tela em SharedPreferences
 * (sobrevive a reinício do app e do aparelho).
 *
 * Regra do ciclo:
 *  1) criança usa a tela até somar [usageLimitMs] (padrão 60 min)
 *  2) bloqueia: começa uma pausa de [breakMs] (padrão 30 min) contada A PARTIR DO BLOQUEIO
 *  3) só libera quando: atividade concluída (acertos >= requiredAnswers) E pausa terminou
 */
object PrefsHelper {

    private const val PREFS_NAME = "kid_screen_time_prefs"

    const val DEFAULT_USAGE_MIN = 60
    const val DEFAULT_BREAK_MIN = 30
    const val DEFAULT_REQUIRED_ANSWERS = 3

    private const val KEY_USAGE_MIN = "usage_limit_min"
    private const val KEY_BREAK_MIN = "break_min"
    private const val KEY_REQUIRED = "required_answers"
    private const val KEY_ACCUMULATED_USAGE_MS = "accumulated_usage_ms"
    private const val KEY_IS_LOCKED = "is_locked"
    private const val KEY_ACTIVITY_DONE = "activity_done"
    private const val KEY_CORRECT_COUNT = "correct_count"
    private const val KEY_BREAK_END_TIMESTAMP = "break_end_timestamp"
    private const val KEY_PIN_HASH = "pin_hash"
    private const val KEY_QUESTION_QUEUE = "question_queue"
    private const val KEY_HEARTBEAT = "heartbeat"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ---------- Configurações (editáveis na tela dos pais) ----------

    fun usageLimitMin(c: Context): Int = prefs(c).getInt(KEY_USAGE_MIN, DEFAULT_USAGE_MIN)
    fun breakMin(c: Context): Int = prefs(c).getInt(KEY_BREAK_MIN, DEFAULT_BREAK_MIN)
    fun requiredAnswers(c: Context): Int = prefs(c).getInt(KEY_REQUIRED, DEFAULT_REQUIRED_ANSWERS)

    fun usageLimitMs(c: Context): Long = usageLimitMin(c) * 60_000L
    fun breakMs(c: Context): Long = breakMin(c) * 60_000L

    fun saveSettings(c: Context, usageMin: Int, breakMin: Int, required: Int) {
        prefs(c).edit()
            .putInt(KEY_USAGE_MIN, usageMin.coerceAtLeast(1))
            .putInt(KEY_BREAK_MIN, breakMin.coerceAtLeast(1))
            .putInt(KEY_REQUIRED, required.coerceIn(1, 10))
            .apply()
    }

    // ---------- Tempo de uso ----------

    fun getAccumulatedUsageMs(c: Context): Long =
        prefs(c).getLong(KEY_ACCUMULATED_USAGE_MS, 0L)

    fun addUsageMs(c: Context, deltaMs: Long) {
        prefs(c).edit().putLong(KEY_ACCUMULATED_USAGE_MS, getAccumulatedUsageMs(c) + deltaMs).apply()
    }

    fun resetUsage(c: Context) {
        prefs(c).edit().putLong(KEY_ACCUMULATED_USAGE_MS, 0L).apply()
    }

    // ---------- Bloqueio ----------

    fun isLocked(c: Context): Boolean = prefs(c).getBoolean(KEY_IS_LOCKED, false)

    /** Inicia o bloqueio: a pausa já começa a contar daqui. */
    fun startLock(c: Context) {
        prefs(c).edit()
            .putBoolean(KEY_IS_LOCKED, true)
            .putBoolean(KEY_ACTIVITY_DONE, false)
            .putInt(KEY_CORRECT_COUNT, 0)
            .putLong(KEY_BREAK_END_TIMESTAMP, System.currentTimeMillis() + breakMs(c))
            .apply()
    }

    fun remainingBreakMs(c: Context): Long {
        val endsAt = prefs(c).getLong(KEY_BREAK_END_TIMESTAMP, 0L)
        return (endsAt - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    fun isActivityDone(c: Context): Boolean = prefs(c).getBoolean(KEY_ACTIVITY_DONE, false)

    fun correctCount(c: Context): Int = prefs(c).getInt(KEY_CORRECT_COUNT, 0)

    /** Registra um acerto. Marca a atividade como concluída ao atingir o mínimo. Retorna o total de acertos. */
    fun registerCorrect(c: Context): Int {
        val n = correctCount(c) + 1
        val e = prefs(c).edit().putInt(KEY_CORRECT_COUNT, n)
        if (n >= requiredAnswers(c)) e.putBoolean(KEY_ACTIVITY_DONE, true)
        e.apply()
        return n
    }

    /** Pode liberar quando a atividade foi feita E os minutos de pausa passaram. */
    fun canUnlock(c: Context): Boolean = isActivityDone(c) && remainingBreakMs(c) <= 0L

    /** Fim do ciclo (ou liberação pelos pais): zera tudo para o próximo ciclo. */
    fun unlockAndReset(c: Context) {
        prefs(c).edit()
            .putBoolean(KEY_IS_LOCKED, false)
            .putBoolean(KEY_ACTIVITY_DONE, false)
            .putInt(KEY_CORRECT_COUNT, 0)
            .remove(KEY_BREAK_END_TIMESTAMP)
            .putLong(KEY_ACCUMULATED_USAGE_MS, 0L)
            .apply()
    }

    // ---------- PIN dos pais ----------

    fun hasPin(c: Context): Boolean = prefs(c).getString(KEY_PIN_HASH, null) != null

    fun setPin(c: Context, pin: String) {
        prefs(c).edit().putString(KEY_PIN_HASH, hash(pin)).apply()
    }

    fun checkPin(c: Context, pin: String): Boolean =
        prefs(c).getString(KEY_PIN_HASH, null) == hash(pin)

    private fun hash(pin: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest("kidscreentime:$pin".toByteArray())
            .joinToString("") { "%02x".format(it) }

    // ---------- Fila de atividades (geradas pela IA) ----------

    fun getQuestionQueueJson(c: Context): String? = prefs(c).getString(KEY_QUESTION_QUEUE, null)

    fun setQuestionQueueJson(c: Context, json: String) {
        prefs(c).edit().putString(KEY_QUESTION_QUEUE, json).apply()
    }

    // ---------- Heartbeat (a tela dos pais usa para saber se o serviço está vivo) ----------

    fun setHeartbeat(c: Context, ts: Long) {
        prefs(c).edit().putLong(KEY_HEARTBEAT, ts).apply()
    }

    fun isServiceAlive(c: Context): Boolean =
        System.currentTimeMillis() - prefs(c).getLong(KEY_HEARTBEAT, 0L) < 15_000L
}
