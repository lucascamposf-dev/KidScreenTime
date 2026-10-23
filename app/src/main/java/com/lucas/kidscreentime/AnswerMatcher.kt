package com.lucas.kidscreentime

import java.text.Normalizer

/**
 * Compara a resposta digitada com o gabarito de forma tolerante ao que NÃO é o conteúdo
 * avaliado (acento, maiúscula, pontuação, hífen, artigo, número por extenso, plural simples),
 * mas sem aceitar erro de ortografia — escrever certo faz parte do aprendizado.
 */
object AnswerMatcher {

    private val ARTICLES = setOf("o", "a", "os", "as", "um", "uma", "uns", "umas")

    private val NUMBER_WORDS = mapOf(
        "zero" to 0, "um" to 1, "uma" to 1, "dois" to 2, "duas" to 2, "tres" to 3, "quatro" to 4,
        "cinco" to 5, "seis" to 6, "sete" to 7, "oito" to 8, "nove" to 9, "dez" to 10,
        "onze" to 11, "doze" to 12, "treze" to 13, "catorze" to 14, "quatorze" to 14,
        "quinze" to 15, "dezesseis" to 16, "dezessete" to 17, "dezoito" to 18, "dezenove" to 19,
        "vinte" to 20, "trinta" to 30, "quarenta" to 40, "cinquenta" to 50, "sessenta" to 60,
        "setenta" to 70, "oitenta" to 80, "noventa" to 90, "cem" to 100
    )

    /** Sem acento, minúsculas, só letras/dígitos separados por um espaço. */
    fun basic(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

    /** Forma canônica: [basic] + sem artigo inicial + número por extenso vira dígito. */
    fun canonical(s: String): String {
        var tokens = basic(s).split(" ").filter { it.isNotEmpty() }
        if (tokens.size > 1 && tokens[0] in ARTICLES) tokens = tokens.drop(1)
        numberValue(tokens)?.let { return it.toString() }
        return tokens.joinToString(" ")
    }

    /** "vinte e um" -> 21, "4" -> 4; null se não for um número. */
    private fun numberValue(tokens: List<String>): Int? {
        if (tokens.isEmpty() || tokens.all { it == "e" }) return null
        var sum = 0
        for (t in tokens) {
            sum += when {
                t == "e" -> 0
                t.all { it.isDigit() } && t.length <= 4 -> t.toInt()
                else -> NUMBER_WORDS[t] ?: return null
            }
        }
        return sum
    }

    fun matches(given: String, candidates: List<String>): Boolean {
        val g = canonical(given)
        if (g.isEmpty()) return false
        return candidates.any { cand ->
            val c = canonical(cand)
            c.isNotEmpty() && (
                g == c ||
                g.replace(" ", "") == c.replace(" ", "") ||
                // plural simples (só palavras, nunca letra solta: "A" != "AS")
                (c.length >= 3 && (g == c + "s" || g + "s" == c || g == c + "es")) ||
                // "terça" para "terça-feira"
                (c.endsWith(" feira") && g == c.removeSuffix(" feira"))
            )
        }
    }
}
