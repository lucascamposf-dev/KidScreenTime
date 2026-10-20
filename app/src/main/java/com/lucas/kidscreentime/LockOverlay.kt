package com.lucas.kidscreentime

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Tela de bloqueio desenhada como JANELA SOBREPOSTA (permissão "Exibir sobre outros apps").
 * Diferente de uma Activity, ela continua por cima de QUALQUER app, inclusive se a criança
 * apertar Home/Recentes, e não depende de "abrir activity em segundo plano" (bloqueado no Android 10+).
 *
 * Quem decide quando mostrar/esconder/liberar é o [TimeTrackerService]; aqui só desenhamos
 * e registramos os acertos. [onStateChanged] avisa o serviço para reavaliar na hora.
 */
class LockOverlay(
    private val context: Context,
    private val onStateChanged: () -> Unit
) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var root: View? = null

    private lateinit var tvTitle: TextView
    private lateinit var tvProgress: TextView
    private lateinit var tvQuestion: TextView
    private lateinit var tvFeedback: TextView
    private lateinit var tvCountdown: TextView
    private lateinit var llOptions: LinearLayout
    private lateinit var llPin: LinearLayout
    private lateinit var etPin: EditText

    private var etAnswer: EditText? = null
    private var btnAnswer: Button? = null

    private var current: EduQuestion? = null
    private var lastAnswerAt = 0L
    private var inputBlocked = false

    val isShowing: Boolean get() = root?.isAttachedToWindow == true

    fun show() {
        if (root != null) return
        val themed = ContextThemeWrapper(context, R.style.Theme_KidScreenTime_Overlay)
        val v = LayoutInflater.from(themed).inflate(R.layout.overlay_lock, null)

        tvTitle = v.findViewById(R.id.tvTitle)
        tvProgress = v.findViewById(R.id.tvProgress)
        tvQuestion = v.findViewById(R.id.tvQuestion)
        tvFeedback = v.findViewById(R.id.tvFeedback)
        tvCountdown = v.findViewById(R.id.tvCountdown)
        llOptions = v.findViewById(R.id.llOptions)
        llPin = v.findViewById(R.id.llPin)
        etPin = v.findViewById(R.id.etPin)
        val btnParent: Button = v.findViewById(R.id.btnParent)
        val btnPinOk: Button = v.findViewById(R.id.btnPinOk)

        btnParent.visibility = if (PrefsHelper.hasPin(context)) View.VISIBLE else View.GONE
        btnParent.setOnClickListener { llPin.visibility = View.VISIBLE }
        btnPinOk.setOnClickListener { tryParentUnlock() }

        // Engole o botão Voltar.
        v.isFocusableInTouchMode = true
        v.setOnKeyListener { _, keyCode, _ -> keyCode == KeyEvent.KEYCODE_BACK }
        @Suppress("DEPRECATION")
        v.systemUiVisibility = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION

        // Sem FLAG_NOT_FOCUSABLE: a janela precisa de foco para o teclado aparecer.
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        try {
            wm.addView(v, lp)
        } catch (e: Exception) {
            Log.e(TAG, "Não foi possível exibir o bloqueio (permissão de sobreposição?): ${e.message}")
            return
        }
        root = v
        v.requestFocus()
        loadQuestion()
        refresh()
    }

    fun hide() {
        handler.removeCallbacksAndMessages(null)
        root?.let {
            try {
                wm.removeView(it)
            } catch (e: Exception) {
                Log.w(TAG, "removeView: ${e.message}")
            }
        }
        root = null
        etAnswer = null
        btnAnswer = null
    }

    /** Chamado a cada segundo pelo serviço: atualiza contagem regressiva e estado. */
    fun refresh() {
        if (root == null) return
        val remaining = PrefsHelper.remainingBreakMs(context)
        val done = PrefsHelper.isActivityDone(context)
        val total = PrefsHelper.requiredAnswers(context)
        val correct = PrefsHelper.correctCount(context)
        val stats = "✔ ${PrefsHelper.cycleCorrectCount(context)} acertos · " +
                "✖ ${PrefsHelper.cycleWrongCount(context)} erros · " +
                "+${PrefsHelper.extraMinutesAccumulated(context)} min"

        val timeLeft = formatMs(remaining)
        if (done) {
            tvTitle.text = "Parabéns! 🎉"
            tvProgress.text = stats
            tvQuestion.text = "Você já fez a atividade.\nAgora descanse um pouquinho!"
            llOptions.visibility = View.GONE
            tvCountdown.text = if (remaining > 0) "Volte a usar o celular em: $timeLeft" else "Liberando..."
        } else {
            tvTitle.text = "Hora da pausa! 🧠"
            tvProgress.text = "$stats\nAcertos para liberar: $correct de $total"
            llOptions.visibility = View.VISIBLE
            tvCountdown.text = if (remaining > 0) "Pausa termina em: $timeLeft" else "A pausa já acabou. Falta só a atividade!"
        }
    }

    // ---------- Perguntas ----------

    private fun loadQuestion() {
        val q = ActivityBank.next(context)
        current = q
        inputBlocked = false
        tvQuestion.text = q.text
        llOptions.removeAllViews()

        val et = makeAnswerField(q)
        val btn = makeAnswerButton()
        llOptions.addView(et)
        llOptions.addView(btn)
        etAnswer = et
        btnAnswer = btn

        et.requestFocus()
        et.postDelayed({ showKeyboard(et) }, 250)
    }

    private fun makeAnswerField(q: EduQuestion): EditText {
        val et = EditText(context)
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 32f)
        et.setTextColor(Color.parseColor("#111827"))
        et.setHintTextColor(Color.parseColor("#9CA3AF"))
        et.hint = if (q.numeric) "Digite o número" else "Digite a resposta"
        et.gravity = Gravity.CENTER
        et.isSingleLine = true
        et.background = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dp(16f)
        }
        et.inputType = if (q.numeric) {
            InputType.TYPE_CLASS_NUMBER
        } else {
            // Sem sugestões nem autocorreção (senão o teclado entrega a resposta).
            InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                    InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        et.imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        et.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit()
                true
            } else {
                false
            }
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(72f).toInt()
        )
        lp.topMargin = dp(12f).toInt()
        et.layoutParams = lp
        return et
    }

    private fun makeAnswerButton(): Button {
        val b = Button(context)
        b.text = "Responder"
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
        b.setTextColor(Color.WHITE)
        b.isAllCaps = false
        b.background = GradientDrawable().apply {
            setColor(Color.parseColor("#22C55E"))
            cornerRadius = dp(16f)
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(72f).toInt()
        )
        lp.topMargin = dp(12f).toInt()
        b.layoutParams = lp
        b.setOnClickListener { submit() }
        return b
    }

    private fun submit() {
        val q = current ?: return
        if (inputBlocked) return
        if (PrefsHelper.isActivityDone(context)) return
        val given = etAnswer?.text?.toString().orEmpty()
        if (given.isBlank()) {
            tvFeedback.setTextColor(Color.parseColor("#FDE68A"))
            tvFeedback.text = "Digite a resposta 🙂"
            return
        }
        // Ignora toques repetidos em menos de 1s (toque duplo não conta dois acertos).
        val now = SystemClock.uptimeMillis()
        if (now - lastAnswerAt < 1_000L) return
        lastAnswerAt = now

        if (q.isCorrect(given)) {
            PrefsHelper.registerCorrect(context)
            PrefsHelper.registerCycleCorrect(context)
            tvFeedback.setTextColor(Color.parseColor("#A7F3D0"))
            tvFeedback.text = "Muito bem! 🎉"
            if (!PrefsHelper.isActivityDone(context)) loadQuestion() else hideKeyboard()
        } else {
            PrefsHelper.registerCycleWrong(context)
            inputBlocked = true
            etAnswer?.isEnabled = false
            btnAnswer?.isEnabled = false
            tvFeedback.setTextColor(Color.parseColor("#FCA5A5"))
            tvFeedback.text = "A resposta era: ${q.answer}"
            // Mostra a resposta certa por 4s e só então traz pergunta nova.
            handler.postDelayed({
                if (root != null && !PrefsHelper.isActivityDone(context)) {
                    tvFeedback.text = ""
                    loadQuestion()
                }
            }, WRONG_ANSWER_DELAY_MS)
        }
        refresh()
        onStateChanged()
    }

    private fun showKeyboard(view: View) {
        try {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        } catch (e: Exception) {
            Log.w(TAG, "showKeyboard: ${e.message}")
        }
    }

    private fun hideKeyboard() {
        try {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            root?.windowToken?.let { imm.hideSoftInputFromWindow(it, 0) }
        } catch (e: Exception) {
            Log.w(TAG, "hideKeyboard: ${e.message}")
        }
    }

    // ---------- Liberação pelos pais ----------

    private fun tryParentUnlock() {
        val pin = etPin.text.toString()
        val wait = PrefsHelper.pinLockRemainingMs(context)
        if (wait > 0) {
            etPin.setText("")
            etPin.hint = "Aguarde ${(wait + 999) / 1000}s"
            return
        }
        if (PrefsHelper.checkPin(context, pin)) {
            PrefsHelper.unlockAndReset(context)
            onStateChanged()
        } else {
            etPin.setText("")
            etPin.hint = "PIN incorreto"
        }
    }

    // ---------- Util ----------

    private fun formatMs(ms: Long): String {
        val s = ms / 1000
        return "%02d:%02d".format(s / 60, s % 60)
    }

    private fun dp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics)

    companion object {
        private const val TAG = "LockOverlay"
        private const val WRONG_ANSWER_DELAY_MS = 4_000L
    }
}