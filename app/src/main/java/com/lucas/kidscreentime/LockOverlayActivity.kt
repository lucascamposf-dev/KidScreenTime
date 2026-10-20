package com.lucas.kidscreentime

import android.os.Bundle
import android.os.CountDownTimer
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Tela que trava o uso do celular. Fluxo:
 * 1) Mostra uma atividade educacional (conta de matemática).
 * 2) Ao acertar, inicia uma pausa obrigatória de 30 minutos (contagem regressiva).
 * 3) Só quando a pausa termina é que a tela fecha e libera o celular de novo.
 *
 * O estado (atividade feita / fim da pausa) fica salvo em SharedPreferences,
 * então se a criança fechar o app à força ou reiniciar o celular, a tela
 * volta a aparecer exatamente de onde parou.
 */
class LockOverlayActivity : AppCompatActivity() {

    private lateinit var tvQuestion: TextView
    private lateinit var etAnswer: EditText
    private lateinit var tvFeedback: TextView
    private lateinit var btnCheck: Button
    private lateinit var tvCountdown: TextView
    private lateinit var tvTitle: TextView

    private var currentAnswer: Int = 0
    private var countDownTimer: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lock_overlay)

        tvTitle = findViewById(R.id.tvTitle)
        tvQuestion = findViewById(R.id.tvQuestion)
        etAnswer = findViewById(R.id.etAnswer)
        tvFeedback = findViewById(R.id.tvFeedback)
        btnCheck = findViewById(R.id.btnCheck)
        tvCountdown = findViewById(R.id.tvCountdown)

        btnCheck.setOnClickListener { checkAnswer() }

        resumeCorrectScreen()
    }

    /** Decide se mostra a atividade ou a contagem da pausa, dependendo do estado salvo. */
    private fun resumeCorrectScreen() {
        val breakRemaining = PrefsHelper.getBreakRemainingMs(this)
        if (PrefsHelper.isActivityDone(this) && breakRemaining >= 0) {
            showBreakCountdown(breakRemaining)
        } else {
            showActivity()
        }
    }

    private fun showActivity() {
        tvTitle.text = "Hora da pausa! Responda para continuar:"
        tvQuestion.visibility = TextView.VISIBLE
        etAnswer.visibility = EditText.VISIBLE
        btnCheck.visibility = Button.VISIBLE
        tvCountdown.visibility = TextView.GONE

        val question = ActivityBank.randomQuestion()
        currentAnswer = question.answer
        tvQuestion.text = question.text
        etAnswer.setText("")
        tvFeedback.text = ""
    }

    private fun checkAnswer() {
        val typed = etAnswer.text.toString().toIntOrNull()
        if (typed == null) {
            tvFeedback.text = "Digite um número"
            return
        }
        if (typed == currentAnswer) {
            PrefsHelper.setActivityDone(this, true)
            PrefsHelper.startBreak(this)
            showBreakCountdown(PrefsHelper.BREAK_DURATION_MS)
        } else {
            tvFeedback.text = "Tente de novo!"
            // nova pergunta para não deixar decorar a resposta certa
            val question = ActivityBank.randomQuestion()
            currentAnswer = question.answer
            tvQuestion.text = question.text
            etAnswer.setText("")
        }
    }

    private fun showBreakCountdown(remainingMs: Long) {
        tvTitle.text = "Parabéns! Agora é hora da pausa."
        tvQuestion.visibility = TextView.GONE
        etAnswer.visibility = EditText.GONE
        btnCheck.visibility = Button.GONE
        tvCountdown.visibility = TextView.VISIBLE

        countDownTimer?.cancel()
        countDownTimer = object : CountDownTimer(remainingMs, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val totalSeconds = millisUntilFinished / 1000
                val minutes = totalSeconds / 60
                val seconds = totalSeconds % 60
                tvCountdown.text = "Volte a usar o celular em: %02d:%02d".format(minutes, seconds)
            }

            override fun onFinish() {
                unlockDevice()
            }
        }.start()
    }

    private fun unlockDevice() {
        PrefsHelper.unlockAndReset(this)
        finish()
    }

    // Impede que a criança feche a tela de bloqueio apertando "voltar".
    override fun onBackPressed() {
        // Não faz nada de propósito.
    }

    override fun onDestroy() {
        super.onDestroy()
        countDownTimer?.cancel()
    }
}
