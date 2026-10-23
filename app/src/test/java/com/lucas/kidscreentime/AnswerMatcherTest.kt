package com.lucas.kidscreentime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnswerMatcherTest {

    private fun ok(given: String, vararg answers: String) =
        assertTrue("\"$given\" deveria valer ${answers.toList()}", AnswerMatcher.matches(given, answers.toList()))

    private fun no(given: String, vararg answers: String) =
        assertFalse("\"$given\" NÃO deveria valer ${answers.toList()}", AnswerMatcher.matches(given, answers.toList()))

    @Test
    fun aceitaVariacoesQueNaoSaoConteudo() {
        ok("b", "B")
        ok(" Gato. ", "GATO")
        ok("o gato", "GATO")
        ok("um gato", "GATO")
        ok("maca", "MAÇÃ")
        ok("terca feira", "terça-feira")
        ok("terça", "terça-feira")
        ok("tercafeira", "terça-feira")
        ok("quatro", "4")
        ok("4", "quatro")
        ok("vinte e um", "21")
        ok("patos", "PATO")
        ok("gatinho", "GATO", "GATINHO")
    }

    @Test
    fun recusaRespostasErradas() {
        no("", "GATO")
        no("gado", "GATO")
        no("as", "A")      // plural não vale para letra solta
        no("e", "A")
        no("a", "E")
        no("5", "4")
        no("rato", "GATO")
        no("gto", "GATO")  // ortografia conta
    }

    @Test
    fun letraAQueTambemEArtigo() {
        ok("a", "A")
        ok("A", "A")
        ok("o", "O")
    }
}
