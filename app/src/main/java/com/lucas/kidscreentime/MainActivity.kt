package com.lucas.kidscreentime

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Tela dos PAIS (protegida por PIN): permissões, regras e ferramentas de teste.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var content: View
    private lateinit var tvStatus: TextView
    private lateinit var etUsageMin: EditText
    private lateinit var etBreakMin: EditText
    private lateinit var etRequired: EditText

    private var authenticated = false
    private var leavingForSettings = false
    private var pinDialog: AlertDialog? = null

    private val handler = Handler(Looper.getMainLooper())
    private val statusRunnable = object : Runnable {
        override fun run() {
            updateStatus()
            handler.postDelayed(this, 2000L)
        }
    }

    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { updateStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        content = findViewById(R.id.content)
        tvStatus = findViewById(R.id.tvStatus)
        etUsageMin = findViewById(R.id.etUsageMin)
        etBreakMin = findViewById(R.id.etBreakMin)
        etRequired = findViewById(R.id.etRequired)

        findViewById<Button>(R.id.btnOverlay).setOnClickListener { requestOverlayPermission() }
        findViewById<Button>(R.id.btnBattery).setOnClickListener { requestIgnoreBatteryOptimizations() }
        findViewById<Button>(R.id.btnNotif).setOnClickListener { requestNotificationPermission() }
        findViewById<Button>(R.id.btnStart).setOnClickListener { startTrackerService(null) }
        findViewById<Button>(R.id.btnSave).setOnClickListener { saveSettings() }
        findViewById<Button>(R.id.btnGenerate).setOnClickListener { generateNow() }
        findViewById<Button>(R.id.btnTestLock).setOnClickListener { testLock() }
        findViewById<Button>(R.id.btnUnlockNow).setOnClickListener { unlockNow() }

        loadSettingsIntoFields()
    }

    override fun onStart() {
        super.onStart()
        if (!authenticated) {
            content.visibility = View.GONE
            if (PrefsHelper.hasPin(this)) askPin() else createPin()
        }
    }

    override fun onResume() {
        super.onResume()
        leavingForSettings = false
        handler.post(statusRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(statusRunnable)
    }

    override fun onStop() {
        super.onStop()
        // Sai da tela dos pais (Home etc.) -> pede o PIN de novo. Ir para as telas de
        // permissão do Android não conta como sair.
        if (!leavingForSettings) {
            authenticated = false
            content.visibility = View.GONE
        }
        leavingForSettings = false
        pinDialog?.dismiss()
        pinDialog = null
    }

    // ---------- PIN ----------

    private fun pinField(hint: String) = EditText(this).apply {
        this.hint = hint
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
    }

    private fun askPin() {
        if (pinDialog?.isShowing == true) return
        val et = pinField("PIN dos pais")
        val dialog = AlertDialog.Builder(this)
            .setTitle("Área dos pais")
            .setView(padded(et))
            .setCancelable(false)
            .setPositiveButton("Entrar", null)
            .setNegativeButton("Sair") { _, _ -> finish() }
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val wait = PrefsHelper.pinLockRemainingMs(this)
            if (wait > 0) {
                et.setText("")
                et.hint = "Aguarde ${(wait + 999) / 1000}s"
                return@setOnClickListener
            }
            if (PrefsHelper.checkPin(this, et.text.toString())) {
                authenticated = true
                content.visibility = View.VISIBLE
                dialog.dismiss()
                updateStatus()
            } else {
                et.setText("")
                et.hint = "PIN incorreto"
            }
        }
        pinDialog = dialog
    }

    private fun createPin() {
        if (pinDialog?.isShowing == true) return
        val et1 = pinField("Crie um PIN (4 a 8 números)")
        val et2 = pinField("Repita o PIN")
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(et1)
            addView(et2)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Primeiro acesso: crie o PIN dos pais")
            .setMessage("Esse PIN protege estas configurações e libera o celular em emergências. Seu filho não deve saber.")
            .setView(padded(box))
            .setCancelable(false)
            .setPositiveButton("Salvar", null)
            .setNegativeButton("Sair") { _, _ -> finish() }
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val a = et1.text.toString()
            val b = et2.text.toString()
            if (a.length !in 4..8) {
                Toast.makeText(this, "O PIN precisa ter de 4 a 8 números", Toast.LENGTH_SHORT).show()
            } else if (a != b) {
                Toast.makeText(this, "Os PINs não são iguais", Toast.LENGTH_SHORT).show()
            } else {
                PrefsHelper.setPin(this, a)
                authenticated = true
                content.visibility = View.VISIBLE
                dialog.dismiss()
                updateStatus()
            }
        }
        pinDialog = dialog
    }

    private fun padded(v: View): View = LinearLayout(this).apply {
        val p = (20 * resources.displayMetrics.density).toInt()
        setPadding(p, p / 2, p, 0)
        addView(v)
    }

    // ---------- Permissões ----------

    private fun requestOverlayPermission() {
        if (Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Já permitido ✔", Toast.LENGTH_SHORT).show()
            return
        }
        leavingForSettings = true
        startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        )
    }

    private fun requestIgnoreBatteryOptimizations() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            leavingForSettings = true
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
        } else {
            Toast.makeText(this, "Já permitido ✔", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            leavingForSettings = true
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun notificationsGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED

    // ---------- Ações ----------

    private fun startTrackerService(action: String?) {
        val intent = Intent(this, TimeTrackerService::class.java)
        if (action != null) intent.action = action
        ContextCompat.startForegroundService(this, intent)
        updateStatus()
    }

    private fun saveSettings() {
        val u = etUsageMin.text.toString().toIntOrNull()
        val b = etBreakMin.text.toString().toIntOrNull()
        val r = etRequired.text.toString().toIntOrNull()
        if (u == null || b == null || r == null || u < 1 || b < 1 || r < 1) {
            Toast.makeText(this, "Preencha os 3 campos com números maiores que 0", Toast.LENGTH_SHORT).show()
            return
        }
        PrefsHelper.saveSettings(this, u, b, r)
        loadSettingsIntoFields()
        Toast.makeText(this, "Regras salvas ✔", Toast.LENGTH_SHORT).show()
    }

    private fun loadSettingsIntoFields() {
        etUsageMin.setText(PrefsHelper.usageLimitMin(this).toString())
        etBreakMin.setText(PrefsHelper.breakMin(this).toString())
        etRequired.setText(PrefsHelper.requiredAnswers(this).toString())
    }

    private fun generateNow() {
        if (!GroqClient.isConfigured()) {
            Toast.makeText(this, "Sem chave do Groq: coloque GROQ_API_KEY no local.properties e gere o APK de novo", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, "Gerando atividades...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) { GroqClient.fetchQuestions(10) }
            if (list.isEmpty()) {
                Toast.makeText(this@MainActivity, "Falhou (sem internet, chave inválida ou limite do Groq). Veja o Logcat: GroqClient", Toast.LENGTH_LONG).show()
            } else {
                ActivityBank.push(this@MainActivity, list)
                Toast.makeText(this@MainActivity, "${list.size} atividades novas ✔", Toast.LENGTH_SHORT).show()
            }
            updateStatus()
        }
    }

    private fun testLock() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Primeiro conceda a permissão do passo 1", Toast.LENGTH_LONG).show()
            return
        }
        startTrackerService(TimeTrackerService.ACTION_FORCE_LOCK)
        Toast.makeText(this, "Bloqueio de teste ativado. Use 'Área dos pais' na tela de bloqueio para sair.", Toast.LENGTH_LONG).show()
    }

    private fun unlockNow() {
        PrefsHelper.unlockAndReset(this)
        updateStatus()
        Toast.makeText(this, "Liberado e contador zerado", Toast.LENGTH_SHORT).show()
    }

    // ---------- Status ----------

    private fun updateStatus() {
        if (!::tvStatus.isInitialized) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        fun mark(ok: Boolean) = if (ok) "✅" else "❌"

        val usedMin = PrefsHelper.getAccumulatedUsageMs(this) / 60000
        val state = if (PrefsHelper.isLocked(this)) {
            "🔒 Bloqueado — acertos ${PrefsHelper.correctCount(this)}/${PrefsHelper.requiredAnswers(this)}, " +
                    "pausa restante ${PrefsHelper.remainingBreakMs(this) / 60000} min"
        } else {
            "▶ Em uso: $usedMin de ${PrefsHelper.usageLimitMin(this)} min"
        }

        val ia = if (GroqClient.isConfigured())
            "✅ IA Groq: ${ActivityBank.queueSize(this)} atividades guardadas"
        else "⚠ IA Groq sem chave (só continhas de matemática)"

        tvStatus.text = listOf(
            state,
            "${mark(Settings.canDrawOverlays(this))} Exibir sobre outros apps",
            "${mark(pm.isIgnoringBatteryOptimizations(packageName))} Bateria sem restrição",
            "${mark(notificationsGranted())} Notificações",
            "${mark(PrefsHelper.isServiceAlive(this))} Serviço rodando",
            ia
        ).joinToString("\n")
    }
}
