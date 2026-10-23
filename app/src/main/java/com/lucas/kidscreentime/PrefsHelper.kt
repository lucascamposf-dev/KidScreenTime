package com.lucas.kidscreentime

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

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
    const val DEFAULT_PENALTY_MIN = 5
    const val DEFAULT_PENALTY_CAP_MIN = 60

    private const val KEY_USAGE_MIN = "usage_limit_min"
    private const val KEY_BREAK_MIN = "break_min"
    private const val KEY_REQUIRED = "required_answers"
    private const val KEY_PENALTY_MIN = "penalty_min_per_error"
    private const val KEY_PENALTY_CAP_MIN = "penalty_cap_min_per_cycle"
    private const val KEY_CYCLE_CORRECT = "cycle_correct_count"
    private const val KEY_CYCLE_WRONG = "cycle_wrong_count"
    private const val KEY_EXTRA_MINUTES = "extra_minutes_accumulated"
    private const val KEY_ACCUMULATED_USAGE_MS = "accumulated_usage_ms"
    private const val KEY_IS_LOCKED = "is_locked"
    private const val KEY_ACTIVITY_DONE = "activity_done"
    private const val KEY_CORRECT_COUNT = "correct_count"
    private const val KEY_BREAK_END_TIMESTAMP = "break_end_timestamp"
    private const val KEY_PIN_HASH = "pin_hash"
    private const val KEY_PIN_FAILS = "pin_fail_count"
    private const val KEY_PIN_LOCK_UNTIL = "pin_lock_until"
    private const val MAX_PIN_FAILS = 5
    private const val KEY_QUESTION_QUEUE = "question_queue"
    private const val KEY_CURRENT_QUESTION = "current_question"
    private const val KEY_CURRENT_SHOWN_AT = "current_question_shown_at"
    private const val KEY_CURRENT_COPY = "current_question_copy_pending"
    private const val KEY_RETRY_QUEUE = "retry_queue"
    private const val KEY_SKILL_PROGRESS = "skill_progress"
    private const val KEY_ATTEMPT_LOG = "attempt_log"
    private const val KEY_LAST_PENALTY_MIN = "last_penalty_min"
    private const val KEY_LAST_PENALTY_Q = "last_penalty_question"
    private const val KEY_HEARTBEAT = "heartbeat"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ---------- Configurações (editáveis na tela dos pais) ----------

    fun usageLimitMin(c: Context): Int = prefs(c).getInt(KEY_USAGE_MIN, DEFAULT_USAGE_MIN)
    fun breakMin(c: Context): Int = prefs(c).getInt(KEY_BREAK_MIN, DEFAULT_BREAK_MIN)
    fun requiredAnswers(c: Context): Int = prefs(c).getInt(KEY_REQUIRED, DEFAULT_REQUIRED_ANSWERS)
    fun penaltyMinPerError(c: Context): Int =
        prefs(c).getInt(KEY_PENALTY_MIN, DEFAULT_PENALTY_MIN)

    fun penaltyCapMinPerCycle(c: Context): Int =
        prefs(c).getInt(KEY_PENALTY_CAP_MIN, DEFAULT_PENALTY_CAP_MIN)

    fun usageLimitMs(c: Context): Long = usageLimitMin(c) * 60_000L
    fun breakMs(c: Context): Long = breakMin(c) * 60_000L

    fun saveSettings(
        c: Context,
        usageMin: Int,
        breakMin: Int,
        required: Int,
        penaltyMin: Int,
        penaltyCapMin: Int
    ) {
        prefs(c).edit()
            .putInt(KEY_USAGE_MIN, usageMin.coerceAtLeast(1))
            .putInt(KEY_BREAK_MIN, breakMin.coerceAtLeast(1))
            .putInt(KEY_REQUIRED, required.coerceIn(1, 10))
            .putInt(KEY_PENALTY_MIN, penaltyMin.coerceAtLeast(0))
            .putInt(KEY_PENALTY_CAP_MIN, penaltyCapMin.coerceAtLeast(0))
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
            .putInt(KEY_CYCLE_CORRECT, 0)
            .putInt(KEY_CYCLE_WRONG, 0)
            .putInt(KEY_EXTRA_MINUTES, 0)
            .remove(KEY_LAST_PENALTY_MIN)
            .remove(KEY_LAST_PENALTY_Q)
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

    // ---------- Penalidade por erro (ciclo atual) ----------

    fun cycleCorrectCount(c: Context): Int = prefs(c).getInt(KEY_CYCLE_CORRECT, 0)
    fun cycleWrongCount(c: Context): Int = prefs(c).getInt(KEY_CYCLE_WRONG, 0)
    fun extraMinutesAccumulated(c: Context): Int = prefs(c).getInt(KEY_EXTRA_MINUTES, 0)

    /** Acerto na atividade: só conta para o placar do ciclo. */
    fun registerCycleCorrect(c: Context) {
        prefs(c).edit().putInt(KEY_CYCLE_CORRECT, cycleCorrectCount(c) + 1).apply()
    }

    /**
     * Erro: conta no placar e, se [penalize], soma os minutos extras ao fim da pausa,
     * sem ultrapassar o teto do ciclo. Guarda quanto foi somado e em qual pergunta,
     * para os pais poderem devolver com [refundPenalty].
     * Retorna os minutos extras acumulados no ciclo.
     */
    fun registerCycleWrong(c: Context, questionText: String, penalize: Boolean = true): Int {
        val before = extraMinutesAccumulated(c)
        val after = if (penalize) {
            (before + penaltyMinPerError(c)).coerceAtMost(penaltyCapMinPerCycle(c))
        } else {
            before
        }
        val addedMin = after - before
        val e = prefs(c).edit()
            .putInt(KEY_CYCLE_WRONG, cycleWrongCount(c) + 1)
            .putInt(KEY_EXTRA_MINUTES, after)
            .putInt(KEY_LAST_PENALTY_MIN, addedMin)
            .putString(KEY_LAST_PENALTY_Q, questionText)
        if (addedMin > 0 && isLocked(c)) {
            e.putLong(
                KEY_BREAK_END_TIMESTAMP,
                maxOf(prefs(c).getLong(KEY_BREAK_END_TIMESTAMP, 0L), System.currentTimeMillis()) +
                        addedMin * 60_000L
            )
        }
        e.apply()
        return after
    }

    /**
     * Pergunta marcada como errada pelos pais: se o último erro foi nela,
     * tira o erro do placar e devolve os minutos de castigo.
     * Retorna os minutos devolvidos, ou null se o último erro não foi nessa pergunta.
     */
    fun refundPenalty(c: Context, questionText: String): Int? {
        val p = prefs(c)
        if (p.getString(KEY_LAST_PENALTY_Q, null) != questionText) return null
        val minutes = p.getInt(KEY_LAST_PENALTY_MIN, 0)
        val e = p.edit()
            .putInt(KEY_CYCLE_WRONG, (cycleWrongCount(c) - 1).coerceAtLeast(0))
            .putInt(KEY_EXTRA_MINUTES, (extraMinutesAccumulated(c) - minutes).coerceAtLeast(0))
            .remove(KEY_LAST_PENALTY_MIN)
            .remove(KEY_LAST_PENALTY_Q)
        if (minutes > 0 && p.contains(KEY_BREAK_END_TIMESTAMP)) {
            e.putLong(KEY_BREAK_END_TIMESTAMP, p.getLong(KEY_BREAK_END_TIMESTAMP, 0L) - minutes * 60_000L)
        }
        e.apply()
        return minutes
    }

    /** Fim do ciclo (ou liberação pelos pais): zera tudo para o próximo ciclo. */
    fun unlockAndReset(c: Context) {
        prefs(c).edit()
            .putBoolean(KEY_IS_LOCKED, false)
            .putBoolean(KEY_ACTIVITY_DONE, false)
            .putInt(KEY_CORRECT_COUNT, 0)
            .putInt(KEY_CYCLE_CORRECT, 0)
            .putInt(KEY_CYCLE_WRONG, 0)
            .putInt(KEY_EXTRA_MINUTES, 0)
            .remove(KEY_LAST_PENALTY_MIN)
            .remove(KEY_LAST_PENALTY_Q)
            .remove(KEY_BREAK_END_TIMESTAMP)
            .putLong(KEY_ACCUMULATED_USAGE_MS, 0L)
            .apply()
    }

    // ---------- PIN dos pais ----------

    fun hasPin(c: Context): Boolean = prefs(c).getString(KEY_PIN_HASH, null) != null

    fun setPin(c: Context, pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs(c).edit().putString(KEY_PIN_HASH, "${salt.toHex()}:${pbkdf2(pin, salt)}").apply()
    }

        fun checkPin(c: Context, pin: String): Boolean {
        if (pinLockRemainingMs(c) > 0L) return false
        val stored = prefs(c).getString(KEY_PIN_HASH, null) ?: return false
        val parts = stored.split(":", limit = 2)
        if (parts.size != 2) return false
        val ok = MessageDigest.isEqual(
            parts[1].toByteArray(Charsets.US_ASCII),
            pbkdf2(pin, parts[0].hexToByteArray()).toByteArray(Charsets.US_ASCII)
        )
        if (ok) clearPinFails(c) else registerPinFail(c)
        return ok
    }

    private fun pbkdf2(pin: String, salt: ByteArray): String {
        val spec = PBEKeySpec(pin.toCharArray(), salt, 60_000, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec).encoded.toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.hexToByteArray(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    fun pinFails(c: Context): Int = prefs(c).getInt(KEY_PIN_FAILS, 0)

    fun pinLockRemainingMs(c: Context): Long =
        (prefs(c).getLong(KEY_PIN_LOCK_UNTIL, 0L) - System.currentTimeMillis()).coerceAtLeast(0L)

    fun registerPinFail(c: Context) {
        val fails = pinFails(c) + 1
        val e = prefs(c).edit().putInt(KEY_PIN_FAILS, fails)
        if (fails >= MAX_PIN_FAILS) {
            val lockMs = (30_000L shl (fails - MAX_PIN_FAILS).coerceAtMost(9))
                .coerceAtMost(900_000L)
            e.putLong(KEY_PIN_LOCK_UNTIL, System.currentTimeMillis() + lockMs)
        }
        e.apply()
    }

    fun clearPinFails(c: Context) {
        prefs(c).edit().remove(KEY_PIN_FAILS).remove(KEY_PIN_LOCK_UNTIL).apply()
    }

    // ---------- Fila de atividades (geradas pela IA) ----------

    fun getQuestionQueueJson(c: Context): String? = prefs(c).getString(KEY_QUESTION_QUEUE, null)

    fun setQuestionQueueJson(c: Context, json: String) {
        prefs(c).edit().putString(KEY_QUESTION_QUEUE, json).apply()
    }

    // ---------- Pergunta em andamento e revisão ----------

    fun getCurrentQuestionJson(c: Context): String? = prefs(c).getString(KEY_CURRENT_QUESTION, null)

    fun currentQuestionShownAt(c: Context): Long = prefs(c).getLong(KEY_CURRENT_SHOWN_AT, 0L)

    fun setCurrentQuestion(c: Context, json: String, shownAt: Long) {
        prefs(c).edit()
            .putString(KEY_CURRENT_QUESTION, json)
            .putLong(KEY_CURRENT_SHOWN_AT, shownAt)
            .putBoolean(KEY_CURRENT_COPY, false)
            .apply()
    }

    fun clearCurrentQuestion(c: Context) {
        prefs(c).edit()
            .remove(KEY_CURRENT_QUESTION)
            .remove(KEY_CURRENT_SHOWN_AT)
            .remove(KEY_CURRENT_COPY)
            .apply()
    }

    /** true = a criança errou e ainda precisa digitar a resposta certa para seguir. */
    fun isCopyPending(c: Context): Boolean = prefs(c).getBoolean(KEY_CURRENT_COPY, false)

    fun setCopyPending(c: Context, pending: Boolean) {
        prefs(c).edit().putBoolean(KEY_CURRENT_COPY, pending).apply()
    }

    fun getRetryQueueJson(c: Context): String? = prefs(c).getString(KEY_RETRY_QUEUE, null)

    fun setRetryQueueJson(c: Context, json: String) {
        prefs(c).edit().putString(KEY_RETRY_QUEUE, json).apply()
    }

    // ---------- Aprendizado (nível por habilidade e histórico) ----------

    fun getSkillProgressJson(c: Context): String? = prefs(c).getString(KEY_SKILL_PROGRESS, null)

    fun setSkillProgressJson(c: Context, json: String) {
        prefs(c).edit().putString(KEY_SKILL_PROGRESS, json).apply()
    }

    fun getAttemptLogJson(c: Context): String? = prefs(c).getString(KEY_ATTEMPT_LOG, null)

    fun setAttemptLogJson(c: Context, json: String) {
        prefs(c).edit().putString(KEY_ATTEMPT_LOG, json).apply()
    }

    // ---------- Heartbeat (a tela dos pais usa para saber se o serviço está vivo) ----------

    fun setHeartbeat(c: Context, ts: Long) {
        prefs(c).edit().putLong(KEY_HEARTBEAT, ts).apply()
    }

    fun isServiceAlive(c: Context): Boolean =
        System.currentTimeMillis() - prefs(c).getLong(KEY_HEARTBEAT, 0L) < 15_000L
}
