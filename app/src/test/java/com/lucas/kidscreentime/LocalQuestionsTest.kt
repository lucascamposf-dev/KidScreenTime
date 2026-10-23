package com.lucas.kidscreentime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalQuestionsTest {

    @Test
    fun silabasFormamAPalavra() {
        for (w in WordBank.words) {
            assertEquals(w.text, w.syllables.joinToString(""))
            assertTrue("${w.text}: nível inválido", w.level in 1..3)
        }
        val dup = WordBank.words.groupBy { it.text }.filter { it.value.size > 1 }.keys
        assertTrue("Palavras repetidas: $dup", dup.isEmpty())
    }

    /** O próprio gabarito sempre tem que ser aceito, em todas as habilidades e níveis. */
    @Test
    fun gabaritoSempreAceito() {
        for (s in LocalQuestions.skills) {
            for (level in 1..3) {
                repeat(300) {
                    val q = LocalQuestions.make(s.key, level)
                    assertNotNull("${s.key} nível $level não gerou pergunta", q)
                    q!!
                    assertTrue("${s.key}: ${q.text} -> ${q.answer}", q.isCorrect(q.answer))
                    assertTrue("${s.key}: resposta vazia", q.answer.isNotBlank())
                    assertEquals(s.key, q.type)
                    if (q.numeric) assertTrue("${q.text} -> ${q.answer}", q.answer.all { it.isDigit() })
                }
            }
        }
    }

    @Test
    fun contasCorretasEDentroDoNivel() {
        val re = Regex("Quanto é (\\d+) ([+-]) (\\d+) \\?")
        for (level in 1..3) {
            repeat(500) {
                for (key in listOf("soma", "subtracao")) {
                    val q = LocalQuestions.make(key, level)!!
                    val m = re.find(q.text)!!
                    val a = m.groupValues[1].toInt()
                    val b = m.groupValues[3].toInt()
                    val r = if (m.groupValues[2] == "+") a + b else a - b
                    assertEquals(q.text, r.toString(), q.answer)
                    assertTrue("${q.text}: resultado negativo", r >= 0)
                    if (level == 1) assertTrue("${q.text}: grande demais para o nível 1", a <= 10 && r <= 10)
                }
            }
        }
    }

    @Test
    fun letraFaltandoTemRespostaUnica() {
        repeat(500) {
            val q = LocalQuestions.make("letra_faltando", 3)!!
            val masked = q.text.lines().last()
            val i = masked.indexOf('_')
            val matches = WordBank.words.count { w ->
                w.text.length == masked.length &&
                    AnswerMatcher.basic(w.text).withIndex().all { (j, c) -> j == i || c == AnswerMatcher.basic(masked[j].toString())[0] }
            }
            assertEquals("$masked tem mais de uma palavra possível", 1, matches)
        }
    }

    @Test
    fun alfabetoCorreto() {
        val abc = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        repeat(500) {
            val q = LocalQuestions.make("alfabeto", 3)!!
            val letters = Regex("\\b([A-Z])\\b").findAll(q.text.substringAfter("letra")).map { it.value[0] }.toList()
            val ans = q.answer[0]
            when {
                "DEPOIS" in q.text -> assertEquals(abc[abc.indexOf(letters[0]) + 1], ans)
                "ANTES" in q.text -> assertEquals(abc[abc.indexOf(letters[0]) - 1], ans)
                else -> assertEquals(abc[abc.indexOf(letters[0]) + 1], ans)
            }
        }
    }
}
