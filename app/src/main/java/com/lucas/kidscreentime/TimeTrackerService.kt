package com.lucas.kidscreentime

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Serviço em foreground — o "cérebro" do app. A cada segundo:
 *  - se a tela está realmente em uso (ligada e destravada), soma o tempo;
 *  - ao bater o limite, inicia o bloqueio (pausa conta a partir daí) e mostra a [LockOverlay];
 *  - durante o bloqueio, garante que a tela de bloqueio continua visível e libera
 *    quando (atividade feita E pausa terminou);
 *  - mantém uma fila de atividades da IA (Groq) abastecida para usar offline.
 */
class TimeTrackerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var tickJob: Job? = null
    private var refillJob: Job? = null

    private lateinit var overlay: LockOverlay

    private var lastTick = 0L
    private var prevCounting = false
    private var tickCount = 0
    private var lastRefillAttempt = 0L

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
        overlay = LockOverlay(this) { evaluateLock() }
        lastTick = SystemClock.elapsedRealtime()
        startTicking()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_FORCE_LOCK && !PrefsHelper.isLocked(this)) {
            lockNow()
        }
        return START_STICKY
    }

    private fun startTicking() {
        tickJob?.cancel()
        tickJob = scope.launch {
            while (isActive) {
                delay(1000L)
                try {
                    tick()
                } catch (e: Exception) {
                    Log.e(TAG, "Erro no tick: ${e.message}", e)
                }
            }
        }
    }

    private fun tick() {
        val now = SystemClock.elapsedRealtime()
        val delta = now - lastTick
        lastTick = now

        if (++tickCount % 5 == 0) PrefsHelper.setHeartbeat(this, System.currentTimeMillis())
        maybeRefillQuestions()

        if (PrefsHelper.isLocked(this)) {
            evaluateLock()
            return
        }

        // Só conta quando a tela está ligada E destravada (tempo na tela de bloqueio não conta).
        val counting = isScreenInUse()
        if (counting && prevCounting) {
            PrefsHelper.addUsageMs(this, delta.coerceAtMost(5000L))
        }
        prevCounting = counting

        if (PrefsHelper.getAccumulatedUsageMs(this) >= PrefsHelper.usageLimitMs(this)) {
            lockNow()
        }
    }

    private fun isScreenInUse(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        return pm.isInteractive && !km.isKeyguardLocked
    }

    private fun lockNow() {
        PrefsHelper.startLock(this)
        prevCounting = false
        evaluateLock()
    }

    /** Fonte única da verdade do estado de bloqueio: mostra, atualiza ou libera. */
    private fun evaluateLock() {
        if (!PrefsHelper.isLocked(this)) {
            overlay.hide()
            return
        }
        if (PrefsHelper.canUnlock(this)) {
            PrefsHelper.unlockAndReset(this)
            overlay.hide()
            return
        }
        if (!overlay.isShowing) {
            if (Settings.canDrawOverlays(this)) {
                overlay.show()
            } else {
                Log.w(TAG, "Sem permissão de sobreposição: bloqueio não pode ser exibido")
            }
        }
        overlay.refresh()
    }

    // ---------- Fila de atividades da IA ----------

    private fun maybeRefillQuestions() {
        if (!GroqClient.isConfigured()) return
        if (refillJob?.isActive == true) return
        if (ActivityBank.queueSize(this) >= REFILL_BELOW) return
        val now = SystemClock.elapsedRealtime()
        if (lastRefillAttempt != 0L && now - lastRefillAttempt < REFILL_RETRY_MS) return
        lastRefillAttempt = now

        refillJob = scope.launch {
            val list = withContext(Dispatchers.IO) { GroqClient.fetchQuestions(10) }
            if (list.isNotEmpty()) ActivityBank.push(this@TimeTrackerService, list)
            Log.i(TAG, "Atividades da IA recebidas: ${list.size}")
        }
    }

    // ---------- Notificação ----------

    private fun buildNotification(): Notification {
        val channelId = "kid_screen_time_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Controle de tempo de tela",
                NotificationManager.IMPORTANCE_MIN
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Controle de tempo de tela ativo")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        overlay.hide()
        scope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "TimeTrackerService"
        private const val NOTIFICATION_ID = 1001
        private const val REFILL_BELOW = 6
        private const val REFILL_RETRY_MS = 5 * 60 * 1000L
        const val ACTION_FORCE_LOCK = "com.lucas.kidscreentime.FORCE_LOCK"
    }
}
