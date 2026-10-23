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
    private lateinit var tvReport: TextView
    private lateinit var llUpdate: View
    private lateinit var tvUpdate: TextView
    private lateinit var btnUpdate: Button
    private lateinit var etUsageMin: EditText
    private lateinit var etBreakMin: EditText
    private lateinit var etRequired: EditText
    private lateinit var etPenaltyMin: EditText
    private lateinit var etPenaltyCap: EditText

    private var authenticated = false
    private var leavingForSettings = false
    private var pinDialog: AlertDialog? = null

    private var pendingUpdate: UpdateInfo? = null
    private var updateChecked = false
    private var downloading = false

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
        tvReport = findViewById(R.id.tvReport)
        llUpdate = findViewById(R.id.llUpdate)
        tvUpdate = findViewById(R.id.tvUpdate)
        btnUpdate = findViewById(R.id.btnUpdate)
        etUsageMin = findViewById(R.id.etUsageMin)
        etBreakMin = findViewById(R.id.etBreakMin)
        etRequired = findViewById(R.id.etRequired)
        etPenaltyMin = findViewById(R.id.etPenaltyMin)
        etPenaltyCap = findViewById(R.id.etPenaltyCap)

        findViewById<Button>(R.id.btnOverlay).setOnClickListener { requestOverlayPermission() }
        findViewById<Button>(R.id.btnBattery).setOnClickListener { requestIgnoreBatteryOptimizations() }
        findViewById<Button>(R.id.btnNotif).setOnClickListener { requestNotificationPermission() }
        findViewById<Button>(R.id.btnStart).setOnClickListener { startTrackerService(null) }
        findViewById<Button>(R.id.btnSave).setOnClickListener { saveSettings() }
        findViewById<Button>(R.id.btnGenerate).setOnClickListener { generateNow() }
        findViewById<Button>(R.id.btnTestLock).setOnClickListener { testLock() }
        findViewById<Button>(R.id.btnUnlockNow).setOnClickListener { unlockNow() }
        findViewById<Button>(R.id.btnCheckUpdate).setOnClickListener { checkForUpdate(silent = false) }
        btnUpdate.setOnClickListener { startUpdate() }

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
                dialog.dismiss()
                onAuthenticated()
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
                dialog.dismiss()
                onAuthenticated()
            }
        }
        pinDialog = dialog
    }

    private fun onAuthenticated() {
        authenticated = true
        content.visibility = View.VISIBLE
        updateStatus()
        // Procura atualização uma vez por abertura da área dos pais, sem incomodar se não houver.
        if (!updateChecked) checkForUpdate(silent = true)
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
        val p = etPenaltyMin.text.toString().toIntOrNull()
        val cap = etPenaltyCap.text.toString().toIntOrNull()
        if (u == null || b == null || r == null || p == null || cap == null ||
            u < 1 || b < 1 || r < 1 || p < 0 || cap < 0
        ) {
            Toast.makeText(
                this,
                "Preencha os 5 campos com números válidos (extras podem ser 0)",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        PrefsHelper.saveSettings(this, u, b, r, p, cap)
        loadSettingsIntoFields()
        Toast.makeText(this, "Regras salvas ✔", Toast.LENGTH_SHORT).show()
    }

    private fun loadSettingsIntoFields() {
        etUsageMin.setText(PrefsHelper.usageLimitMin(this).toString())
        etBreakMin.setText(PrefsHelper.breakMin(this).toString())
        etRequired.setText(PrefsHelper.requiredAnswers(this).toString())
        etPenaltyMin.setText(PrefsHelper.penaltyMinPerError(this).toString())
        etPenaltyCap.setText(PrefsHelper.penaltyCapMinPerCycle(this).toString())
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
                Toast.makeText(this@MainActivity, "Falhou: ${GroqClient.lastError.take(150)}", Toast.LENGTH_LONG).show()
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

    // ---------- Atualização do app ----------

    private fun checkForUpdate(silent: Boolean) {
        if (!UpdateChecker.isConfigured()) {
            if (!silent) {
                Toast.makeText(this, "Atualização não configurada: coloque UPDATE_REPO no local.properties e gere o APK de novo", Toast.LENGTH_LONG).show()
            }
            return
        }
        if (!silent) Toast.makeText(this, "Procurando atualização...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { UpdateChecker.fetchLatest() } }
            updateChecked = true
            val info = result.getOrNull()
            if (info != null && UpdateChecker.isNewer(info)) {
                pendingUpdate = info
                tvUpdate.text = "🔄 Nova versão disponível: v${info.versionName}" +
                        (if (info.notes.isNotBlank()) "\n${info.notes.take(300)}" else "")
                llUpdate.visibility = View.VISIBLE
            } else {
                pendingUpdate = null
                llUpdate.visibility = View.GONE
                if (!silent) {
                    val msg = result.exceptionOrNull()?.let { "Não foi possível procurar: ${it.message}" }
                        ?: "Você já tem a versão mais nova (v${BuildConfig.VERSION_NAME})"
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun startUpdate() {
        val info = pendingUpdate ?: return
        if (downloading) return
        if (!UpdateChecker.canInstall(this)) {
            Toast.makeText(this, "Permita \"instalar apps desconhecidos\" e toque em Atualizar de novo", Toast.LENGTH_LONG).show()
            leavingForSettings = true
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        downloading = true
        btnUpdate.isEnabled = false
        btnUpdate.text = "Baixando..."
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    UpdateChecker.download(this@MainActivity, info) { pct ->
                        runOnUiThread { btnUpdate.text = if (pct >= 0) "Baixando... $pct%" else "Baixando..." }
                    }
                }
            }
            downloading = false
            btnUpdate.isEnabled = true
            result.onSuccess { apk ->
                btnUpdate.text = "Atualizar agora"
                // O instalador do Android abre por cima; voltar dele não deve pedir o PIN de novo.
                leavingForSettings = true
                startActivity(UpdateChecker.installIntent(this@MainActivity, apk))
            }.onFailure {
                btnUpdate.text = "Tentar de novo"
                Toast.makeText(this@MainActivity, "Falha ao baixar: ${it.message}", Toast.LENGTH_LONG).show()
            }
        }
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
            "✅ IA Groq: ${ActivityBank.queueSize(this)} atividades guardadas" + (if (GroqClient.lastError.isNotBlank()) "\n⚠ Último erro da IA: ${GroqClient.lastError}" else "")
        else "⚠ IA Groq sem chave (só perguntas do próprio app)"

        tvStatus.text = listOf(
            "Versão do app: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            state,
            "${mark(Settings.canDrawOverlays(this))} Exibir sobre outros apps",
            "${mark(pm.isIgnoringBatteryOptimizations(packageName))} Bateria sem restrição",
            "${mark(notificationsGranted())} Notificações",
            "${mark(PrefsHelper.isServiceAlive(this))} Serviço rodando",
            ia
        ).joinToString("\n")
        tvReport.text = buildReport()
    }

    // ---------- Relatório de aprendizado ----------

    private fun buildReport(): String {
        val now = System.currentTimeMillis()
        val all = AttemptLog.all(this).filter { !it.flagged }
        if (all.isEmpty()) return "Ainda não há respostas registradas."

        val week = all.filter { it.timestampMs >= now - 7 * DAY_MS }
        val lines = mutableListOf(
            "Últimos 7 dias: ${week.count { it.isCorrect }} ✔ acertos · ${week.count { !it.isCorrect }} ✖ erros"
        )
        for ((skill, list) in week.groupBy { it.skill }.entries.sortedByDescending { it.value.size }) {
            val label = LocalQuestions.skill(skill)?.label
                ?: if (skill == "conhecimento") "Conhecimentos gerais (IA)" else skill
            val pct = 100 * list.count { it.isCorrect } / list.size
            lines += "• $label — nível ${Progress.level(this, skill)} — $pct% de ${list.size}"
        }

        val guess = GuessDetector.analyze(all.filter { it.timestampMs >= now - DAY_MS })
        val guessLabel = when (guess.level) {
            GuessLevel.BAIXO -> "baixo ✅"
            GuessLevel.MEDIO -> "médio ⚠"
            GuessLevel.ALTO -> "alto ❗"
        }
        lines += ""
        lines += "Sinais de chute (24 h): $guessLabel"
        guess.reasons.forEach { lines += "  – $it" }

        val errors = all.filter { !it.isCorrect }.takeLast(5).reversed()
        if (errors.isNotEmpty()) {
            lines += ""
            lines += "Últimos erros:"
            errors.forEach {
                lines += "• ${it.questionText.replace('\n', ' ')} → digitou \"${it.givenAnswer}\", certo: ${it.correctAnswer}"
            }
        }
        return lines.joinToString("\n")
    }

    companion object {
        private const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}
