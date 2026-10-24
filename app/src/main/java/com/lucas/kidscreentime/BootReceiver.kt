package com.lucas.kidscreentime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Religa o serviço quando o celular reinicia, o app é atualizado ou o alarme de vigia
 * ([TimeTrackerService.ACTION_WATCHDOG]) toca — caso o Android ou alguém tenha fechado o serviço.
 * O serviço, ao subir, já recoloca a tela de bloqueio se o ciclo estava em andamento.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val a = intent.action
        if (a != Intent.ACTION_BOOT_COMPLETED && a != Intent.ACTION_MY_PACKAGE_REPLACED &&
            a != TimeTrackerService.ACTION_WATCHDOG) return
        if (!PrefsHelper.hasPin(context)) return // app ainda não foi configurado pelos pais
        try {
            ContextCompat.startForegroundService(context, Intent(context, TimeTrackerService::class.java))
        } catch (e: Exception) {
            Log.e("BootReceiver", "Não foi possível iniciar o serviço: ${e.message}")
        }
    }
}
