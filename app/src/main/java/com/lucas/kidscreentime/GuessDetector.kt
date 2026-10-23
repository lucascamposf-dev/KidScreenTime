package com.lucas.kidscreentime

/**
 * Analisa tentativas de um período (respostas do aluno) e classifica o nível
 * de "chute" (baixo, médio ou alto) com uma lista de motivos em português.
 *
 * Os dados vêm do [AttemptLog]; cabe ao chamador recortar o período desejado
 * (ex.: últimas 24 h). Tentativas marcadas pelos pais ([AttemptRecord.flagged]) são ignoradas.
 *
 * Sinais detectados (só em respostas ERRADAS — responder rápido e certo não é chute):
 *   - resposta errada rápida demais para o tamanho do enunciado;
 *   - sequência de 3 ou mais erros em poucos segundos;
 *   - respostas sem sentido (uma letra só quando se esperava palavra/número, caractere
 *     repetido, mesma resposta errada em perguntas diferentes);
 *   - acerto só depois de várias tentativas no mesmo tema.
 */
data class AttemptRecord(
    /** Momento da resposta em ms (epoch). */
    val timestampMs: Long,
    /** Enunciado da pergunta. */
    val questionText: String,
    /** Resposta digitada pelo usuário. */
    val givenAnswer: String,
    /** Se a resposta foi correta. */
    val isCorrect: Boolean,
    /** Tema da pergunta (ex.: "matemática", "português"). */
    val topic: String = "português",
    /** Resposta correta esperada. */
    val correctAnswer: String = "",
    /** Momento em que a pergunta apareceu na tela (0 = desconhecido). */
    val shownAtMs: Long = 0L,
    /** Habilidade (ex.: "letra_inicial"). */
    val skill: String = "",
    /** Origem: [EduQuestion.SRC_LOCAL] ou [EduQuestion.SRC_AI]. */
    val source: String = EduQuestion.SRC_LOCAL,
    /** Pergunta marcada como errada pelos pais: não conta. */
    val flagged: Boolean = false
)

enum class GuessLevel { BAIXO, MEDIO, ALTO }

data class GuessResult(val level: GuessLevel, val reasons: List<String>)

object GuessDetector {

    // ---------- Limites (constantes no topo) ----------
    private const val MS_POR_CARACTER = 60L          // ritmo esperado de leitura de quem está aprendendo
    private const val RESPOSTA_RAPIDA_MIN_MS = 1_500L // piso de sanidade para resposta rápida
    private const val SEQUENCIA_ERROS = 3            // qtd. de erros seguidos que dispara o sinal
    private const val JANELA_SEQUENCIA_MS = 15_000L  // janela de "poucos segundos" (15 s)
    private const val MIN_RESPS_SEM_SENTIDO = 3      // mín. perguntas diferentes com a mesma resposta errada
    private const val MAX_TENTATIVAS_ACERTO_TEMA = 3 // acerto só depois de N tentativas no tema
    private const val NIVEL_MEDIO = 2                // nº de sinais para nível médio
    private const val NIVEL_ALTO = 3                 // nº de sinais para nível alto

    /**
     * Analisa uma lista de tentativas já recortadas ao período de interesse.
     *
     * @param attempts tentativas do período (não ordenadas; são ordenadas internamente).
     * @return [GuessResult] com nível e motivos em português.
     */
    fun analyze(attempts: List<AttemptRecord>): GuessResult {
        val sorted = attempts.filter { !it.flagged }.sortedBy { it.timestampMs }
        val reasons = mutableListOf<String>()

        var signals = 0

        // 1) Respostas erradas rápidas demais para o tamanho do enunciado.
        val rapidas = contarRespostasRapidas(sorted)
        if (rapidas > 0) {
            signals++
            reasons.add("$rapidas resposta(s) errada(s) dada(s) antes do tempo de ler o enunciado.")
        }

        // 2) Sequência de 3 ou mais erros em poucos segundos.
        val seqErros = detectaSequenciaErros(sorted)
        if (seqErros >= SEQUENCIA_ERROS) {
            signals++
            reasons.add("Sequência de $seqErros erro(s) seguido(s) em menos de ${JANELA_SEQUENCIA_MS / 1000} segundos.")
        }

        // 3) Respostas sem sentido.
        val semSentido = detectaRespostasSemSentido(sorted)
        if (semSentido > 0) {
            signals++
            reasons.add("$semSentido resposta(s) sem sentido (letra solta, caractere repetido ou mesma resposta em perguntas diferentes).")
        }

        // 4) Acerto só depois de várias tentativas no mesmo tema.
        val temasAcertoTardio = detectaAcertoTardioPorTema(sorted)
        if (temasAcertoTardio.isNotEmpty()) {
            signals++
            val lista = temasAcertoTardio.joinToString(", ") { "${it.first} (${it.second} tentativa(s))" }
            reasons.add("Acerto só após várias tentativas nos temas: $lista.")
        }

        val level = if (signals >= NIVEL_ALTO) GuessLevel.ALTO
        else if (signals >= NIVEL_MEDIO) GuessLevel.MEDIO
        else GuessLevel.BAIXO

        return GuessResult(level, reasons)
    }

    /** Conta erros dados antes de dar tempo de ler o enunciado (tempo medido desde que a pergunta apareceu). */
    private fun contarRespostasRapidas(sorted: List<AttemptRecord>): Int {
        var count = 0
        var previousMs: Long? = null
        for (attempt in sorted) {
            val inicio = if (attempt.shownAtMs > 0L) attempt.shownAtMs else previousMs
            previousMs = attempt.timestampMs
            if (attempt.isCorrect || inicio == null) continue
            val tempoLeituraEsperado = maxOf(
                attempt.questionText.length * MS_POR_CARACTER,
                RESPOSTA_RAPIDA_MIN_MS
            )
            if (attempt.timestampMs - inicio < tempoLeituraEsperado) count++
        }
        return count
    }

    /** Detecta a maior sequência de erros dentro de uma janela de tempo curta. */
    private fun detectaSequenciaErros(sorted: List<AttemptRecord>): Int {
        var maxSeq = 0
        var seqAtual = 0
        var janelaInicio = 0L

        for (i in sorted.indices) {
            val a = sorted[i]
            if (!a.isCorrect) {
                if (seqAtual == 0) janelaInicio = a.timestampMs
                seqAtual++
                if (a.timestampMs - janelaInicio > JANELA_SEQUENCIA_MS) {
                    // Janela excedeu: reinicia contando a partir deste erro.
                    janelaInicio = a.timestampMs
                    seqAtual = 1
                }
                maxSeq = maxOf(maxSeq, seqAtual)
            } else {
                seqAtual = 0
            }
        }
        return maxSeq
    }

    /**
     * Respostas erradas sem sentido. Uma letra só é normal quando o gabarito é uma letra
     * (ex.: "Com qual letra começa…"), então só conta quando se esperava algo maior.
     */
    private fun detectaRespostasSemSentido(sorted: List<AttemptRecord>): Int {
        var count = 0
        val respostaParaPerguntas = mutableMapOf<String, MutableSet<String>>() // resposta -> perguntas distintas

        for (attempt in sorted) {
            if (attempt.isCorrect) continue
            val resp = EduQuestion.normalize(attempt.givenAnswer)
            if (resp.isEmpty()) continue
            val esperado = EduQuestion.normalize(attempt.correctAnswer)

            // Uma letra só quando a resposta certa é palavra.
            if (resp.length == 1 && esperado.length > 1 && !resp[0].isDigit()) {
                count++
                continue
            }

            // Caractere repetido (ex.: "aaa").
            if (resp.length > 2 && resp.toSet().size == 1) {
                count++
                continue
            }

            // Mesma resposta errada em perguntas diferentes.
            respostaParaPerguntas.getOrPut(resp) { mutableSetOf() }.add(EduQuestion.normalize(attempt.questionText))
        }

        for ((resp, perguntas) in respostaParaPerguntas) {
            if (perguntas.size >= MIN_RESPS_SEM_SENTIDO) count += perguntas.size
        }

        return count
    }

    /** Temas onde houve acerto apenas após várias tentativas (máx. [MAX_TENTATIVAS_ACERTO_TEMA]). */
    private fun detectaAcertoTardioPorTema(sorted: List<AttemptRecord>): List<Pair<String, Int>> {
        val resultado = mutableListOf<Pair<String, Int>>()
        val tentativasPorTema = mutableMapOf<String, MutableList<AttemptRecord>>()

        for (attempt in sorted) {
            tentativasPorTema.getOrPut(attempt.topic) { mutableListOf() }.add(attempt)
        }

        for ((tema, lista) in tentativasPorTema) {
            var tentativasAntesAcerto = 0
            var achouAcertoTardio = false
            for (a in lista) {
                if (!a.isCorrect) {
                    tentativasAntesAcerto++
                } else {
                    if (tentativasAntesAcerto >= MAX_TENTATIVAS_ACERTO_TEMA) {
                        achouAcertoTardio = true
                    }
                    break // primeiro acerto fecha a análise deste tema
                }
            }
            if (achouAcertoTardio) {
                resultado.add(tema to tentativasAntesAcerto)
            }
        }

        return resultado
    }

}
