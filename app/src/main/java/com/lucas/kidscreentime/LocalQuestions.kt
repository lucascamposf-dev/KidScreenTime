package com.lucas.kidscreentime

import kotlin.random.Random

/**
 * Perguntas geradas no aparelho a partir do [WordBank] e de contas.
 * O gabarito é CALCULADO pelo código, então nunca vem errado (ao contrário da IA).
 * Cada habilidade tem 3 níveis; quem escolhe o nível é o [Progress].
 */
object LocalQuestions {

    class Skill(val key: String, val label: String, val topic: String, val make: (Int) -> EduQuestion?)

    private const val PT = "português"
    private const val MAT = "matemática"

    val skills: List<Skill> = listOf(
        Skill("letra_inicial", "Letra inicial", PT, ::letraInicial),
        Skill("letra_final", "Letra final", PT, ::letraFinal),
        Skill("letra_faltando", "Letra que falta", PT, ::letraFaltando),
        Skill("silaba_inicial", "Primeira sílaba", PT, ::silabaInicial),
        Skill("juntar_silabas", "Juntar sílabas", PT, ::juntarSilabas),
        Skill("contar_silabas", "Contar sílabas", PT, ::contarSilabas),
        Skill("contar_letras", "Contar letras", PT, ::contarLetras),
        Skill("vogais", "Vogais", PT, ::vogais),
        Skill("escrever_palavra", "Escrever palavra", PT, ::escreverPalavra),
        Skill("alfabeto", "Ordem do alfabeto", PT, ::alfabeto),
        Skill("contagem", "Contar objetos", MAT, ::contagem),
        Skill("soma", "Soma", MAT, ::soma),
        Skill("subtracao", "Subtração", MAT, ::subtracao),
        Skill("sequencia", "Sequência de números", MAT, ::sequencia),
        Skill("maior_menor", "Maior e menor", MAT, ::maiorMenor)
    )

    fun skill(key: String): Skill? = skills.firstOrNull { it.key == key }

    /** Gera uma pergunta da habilidade; tenta algumas vezes caso o sorteio não sirva. */
    fun make(key: String, level: Int): EduQuestion? {
        val s = skill(key) ?: return null
        repeat(8) { s.make(level.coerceIn(1, 3))?.let { return it } }
        return null
    }

    // ---------- Português ----------

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private val VOWELS = setOf('A', 'E', 'I', 'O', 'U')

    private val LETTER_NAMES = mapOf(
        'A' to "á", 'B' to "bê", 'C' to "cê", 'D' to "dê", 'E' to "é", 'F' to "éfe", 'G' to "gê",
        'H' to "agá", 'I' to "i", 'J' to "jota", 'K' to "cá", 'L' to "éle", 'M' to "ême", 'N' to "êne",
        'O' to "ó", 'P' to "pê", 'Q' to "quê", 'R' to "érre", 'S' to "ésse", 'T' to "tê", 'U' to "u",
        'V' to "vê", 'W' to "dáblio", 'X' to "xis", 'Y' to "ípsilon", 'Z' to "zê"
    )

    private fun letterName(c: Char): String = LETTER_NAMES[base(c)] ?: c.toString()

    /** Como o leitor de voz deve falar a resposta ("B" -> "bê", "GATO" -> "gato"). */
    fun spoken(answer: String): String =
        if (answer.length == 1 && answer[0].isLetter()) letterName(answer[0].uppercaseChar()) else answer.lowercase()

    /** Letra sem acento/til/cedilha (Ã -> A, Ç -> C). */
    private fun base(c: Char): Char = AnswerMatcher.basic(c.toString()).uppercase().firstOrNull() ?: c

    private fun q(
        key: String, text: String, answer: String, speech: String, explanation: String,
        numeric: Boolean = false, accepted: List<String> = emptyList()
    ) = EduQuestion(
        text = text, answer = answer, topic = skill(key)?.topic ?: PT, type = key,
        accepted = accepted, numeric = numeric, speech = speech, explanation = explanation, source = EduQuestion.SRC_LOCAL
    )

    private fun pick(level: Int, filter: (Word) -> Boolean = { true }): Word? =
        WordBank.pool(level, filter).randomOrNull()

    private fun letraInicial(level: Int): EduQuestion? {
        // Figura sem a palavra escrita: a criança precisa pensar no SOM, não copiar.
        val w = pick(level) { it.clear } ?: return null
        val first = base(w.text[0]).toString()
        return q(
            "letra_inicial",
            "${w.emoji}\nCom qual letra começa o nome desta figura?",
            first,
            "Com qual letra começa a palavra ${w.text.lowercase()}?",
            "${w.text} começa com $first."
        )
    }

    private fun letraFinal(level: Int): EduQuestion? {
        val w = pick(level) { it.text.length >= 3 } ?: return null
        val last = base(w.text.last()).toString()
        return q(
            "letra_final",
            "${w.emoji ?: ""}\nCom qual letra termina\n${w.text}?".trim(),
            last,
            "Com qual letra termina a palavra ${w.text.lowercase()}?",
            "${w.text} termina com $last."
        )
    }

    private fun letraFaltando(level: Int): EduQuestion? {
        val w = pick(level) { it.emoji != null && it.text.length >= 3 } ?: return null
        val i = Random.nextInt(w.text.length)
        val masked = w.text.substring(0, i) + "_" + w.text.substring(i + 1)
        // Garante resposta única: nenhuma outra palavra do banco cabe no mesmo molde.
        val plain = AnswerMatcher.basic(w.text)
        val pattern = Regex("^" + plain.substring(0, i) + "." + plain.substring(i + 1) + "$")
        val others = WordBank.words.count { it.text != w.text && pattern.matches(AnswerMatcher.basic(it.text)) }
        if (others > 0) return null
        val missing = base(w.text[i]).toString()
        return q(
            "letra_faltando",
            "${w.emoji}\nQual letra falta?\n$masked",
            missing,
            "Qual letra falta na palavra ${w.text.lowercase()}?",
            "A palavra é ${w.text}: falta o $missing."
        )
    }

    private fun silabaInicial(level: Int): EduQuestion? {
        val w = pick(level) { it.syllables.size >= 2 && (level == 1 || it.clear) } ?: return null
        // Nível 1 mostra a palavra; depois só a figura (consciência fonológica).
        val shown = if (level == 1) "${w.emoji ?: ""}\nQual é a primeira sílaba de\n${w.text}?".trim()
        else "${w.emoji}\nQual é a primeira sílaba do nome desta figura?"
        return q(
            "silaba_inicial",
            shown,
            w.syllables[0],
            "Qual é a primeira sílaba da palavra ${w.text.lowercase()}?",
            "${w.syllables.joinToString("-")}: a primeira sílaba é ${w.syllables[0]}."
        )
    }

    private fun juntarSilabas(level: Int): EduQuestion? {
        val w = pick(level) { it.syllables.size >= 2 } ?: return null
        return q(
            "juntar_silabas",
            "Junte as sílabas e escreva a palavra:\n${w.syllables.joinToString(" + ")}",
            w.text,
            "Junte as sílabas: ${w.syllables.joinToString(", ") { it.lowercase() }}.",
            "${w.syllables.joinToString(" + ")} = ${w.text}${w.emoji?.let { " $it" } ?: ""}"
        )
    }

    private fun contarSilabas(level: Int): EduQuestion? {
        val w = pick(level) ?: return null
        val n = w.syllables.size
        return q(
            "contar_silabas",
            "${w.emoji ?: ""}\nQuantas sílabas tem\n${w.text}?".trim(),
            n.toString(),
            "Quantas sílabas tem a palavra ${w.text.lowercase()}? Bata palmas para contar.",
            "${w.syllables.joinToString("-")}: $n ${if (n == 1) "sílaba" else "sílabas"}.",
            numeric = true
        )
    }

    private fun contarLetras(level: Int): EduQuestion? {
        val w = pick(level) ?: return null
        val n = w.text.length
        return q(
            "contar_letras",
            "${w.emoji ?: ""}\nQuantas letras tem\n${w.text}?".trim(),
            n.toString(),
            "Quantas letras tem a palavra ${w.text.lowercase()}?",
            "${w.text.toList().joinToString("-")}: $n letras.",
            numeric = true
        )
    }

    private fun vogais(level: Int): EduQuestion? {
        // Só palavras sem acento e sem QU/GU, para não confundir quem está começando.
        val w = pick(level) { AnswerMatcher.basic(it.text) == it.text.lowercase() && "QU" !in it.text && "GU" !in it.text }
            ?: return null
        val found = w.text.filter { it in VOWELS }
        return q(
            "vogais",
            "${w.emoji ?: ""}\nQuantas vogais tem\n${w.text}?".trim(),
            found.length.toString(),
            "Quantas vogais tem a palavra ${w.text.lowercase()}? As vogais são a, e, i, o, u.",
            "As vogais de ${w.text} são ${found.toList().joinToString(", ")}: ${found.length}.",
            numeric = true
        )
    }

    private fun escreverPalavra(level: Int): EduQuestion? {
        val w = pick(level) { it.clear } ?: return null
        return q(
            "escrever_palavra",
            "${w.emoji}\nEscreva o nome desta figura",
            w.text,
            "Escreva a palavra ${w.text.lowercase()}.",
            "Essa figura é ${w.text}: ${w.text.toList().joinToString("-")}.",
            accepted = w.alts
        )
    }

    private fun alfabeto(level: Int): EduQuestion? {
        val mode = if (level == 1) 0 else Random.nextInt(if (level == 2) 2 else 3)
        return when (mode) {
            0 -> {
                val i = Random.nextInt(0, ALPHABET.length - 1)
                val a = ALPHABET[i]; val ans = ALPHABET[i + 1]
                q("alfabeto", "Que letra vem DEPOIS do $a?", ans.toString(),
                    "Que letra vem depois do ${letterName(a)}?", "$a, $ans… depois do $a vem o $ans.")
            }
            1 -> {
                val i = Random.nextInt(1, ALPHABET.length)
                val a = ALPHABET[i]; val ans = ALPHABET[i - 1]
                q("alfabeto", "Que letra vem ANTES do $a?", ans.toString(),
                    "Que letra vem antes do ${letterName(a)}?", "$ans, $a… antes do $a vem o $ans.")
            }
            else -> {
                val i = Random.nextInt(1, ALPHABET.length - 1)
                val a = ALPHABET[i - 1]; val ans = ALPHABET[i]; val b = ALPHABET[i + 1]
                q("alfabeto", "Que letra fica entre $a e $b?", ans.toString(),
                    "Que letra fica entre ${letterName(a)} e ${letterName(b)}?", "$a, $ans, $b.")
            }
        }
    }

    // ---------- Matemática ----------

    private val COUNT_EMOJIS = listOf("🍎", "⭐", "🐟", "🎈", "🌸", "🚗", "🍌", "⚽")

    private fun contagem(level: Int): EduQuestion? {
        val n = when (level) { 1 -> Random.nextInt(1, 7); 2 -> Random.nextInt(4, 11); else -> Random.nextInt(8, 16) }
        val e = COUNT_EMOJIS.random()
        val row = List(n) { e }.chunked(5).joinToString("\n") { it.joinToString("") }
        return q("contagem", "Quantos $e tem aqui?\n$row", n.toString(),
            "Conte os desenhos. Quantos tem?", "Contando um por um: ${(1..n).joinToString(", ")}. São $n.", numeric = true)
    }

    private fun soma(level: Int): EduQuestion? {
        val (a, b) = when (level) {
            1 -> Random.nextInt(1, 6).let { a -> a to Random.nextInt(1, 11 - a).coerceAtMost(5) }
            2 -> Random.nextInt(2, 11).let { a -> a to Random.nextInt(1, 21 - a).coerceAtMost(10) }
            else -> Random.nextInt(1, 5).let { t -> t * 10 to Random.nextInt(1, 10) }
                .let { if (Random.nextBoolean()) it else it.first to Random.nextInt(1, 4) * 10 }
        }
        val hint = if (level == 1) " Comece no $a e conte mais $b." else ""
        return q("soma", "Quanto é $a + $b ?", (a + b).toString(),
            "Quanto é $a mais $b?", "$a + $b = ${a + b}.$hint", numeric = true)
    }

    private fun subtracao(level: Int): EduQuestion? {
        val (a, b) = when (level) {
            1 -> Random.nextInt(2, 11).let { a -> a to Random.nextInt(1, a).coerceAtMost(5) }
            2 -> Random.nextInt(10, 21).let { a -> a to Random.nextInt(1, 11) }
            else -> Random.nextInt(2, 10).let { t -> t * 10 to Random.nextInt(1, t) * 10 }
        }
        val hint = if (level == 1) " Tire $b de $a e conte o que sobrou." else ""
        return q("subtracao", "Quanto é $a - $b ?", (a - b).toString(),
            "Quanto é $a menos $b?", "$a - $b = ${a - b}.$hint", numeric = true)
    }

    private fun sequencia(level: Int): EduQuestion? {
        val max = when (level) { 1 -> 10; 2 -> 20; else -> 100 }
        return if (level == 1 || Random.nextBoolean()) {
            val n = Random.nextInt(0, max)
            q("sequencia", "Que número vem DEPOIS do $n?", (n + 1).toString(),
                "Que número vem depois do $n?", "$n, ${n + 1}… depois do $n vem o ${n + 1}.", numeric = true)
        } else {
            val n = Random.nextInt(1, max + 1)
            q("sequencia", "Que número vem ANTES do $n?", (n - 1).toString(),
                "Que número vem antes do $n?", "${n - 1}, $n… antes do $n vem o ${n - 1}.", numeric = true)
        }
    }

    private fun maiorMenor(level: Int): EduQuestion? {
        val max = when (level) { 1 -> 10; 2 -> 20; else -> 100 }
        val a = Random.nextInt(0, max + 1)
        var b = Random.nextInt(0, max + 1)
        if (a == b) b = (a + 1 + Random.nextInt(max)) % (max + 1)
        if (a == b) return null
        val bigger = Random.nextBoolean()
        val ans = if (bigger) maxOf(a, b) else minOf(a, b)
        val other = if (ans == a) b else a
        val word = if (bigger) "MAIOR" else "MENOR"
        return q("maior_menor", "Qual número é $word:\n$a ou $b ?", ans.toString(),
            "Qual número é ${word.lowercase()}: $a ou $b?",
            "$ans é ${word.lowercase()} que $other.", numeric = true)
    }
}
