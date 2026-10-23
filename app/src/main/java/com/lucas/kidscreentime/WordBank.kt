package com.lucas.kidscreentime

/**
 * Palavra do banco local. As sílabas foram separadas à mão e o teste
 * WordBankTest garante que juntas formam a palavra.
 *
 * [level] 1 = 1–2 sílabas simples, 2 = sílabas com encontros/dígrafos, 3 = longas.
 * [emoji] null = sem figura confiável.
 * [clear] = a figura tem UM nome óbvio para a criança (pode ser usada sem mostrar a palavra).
 */
data class Word(
    val text: String,
    val syllables: List<String>,
    val level: Int,
    val emoji: String? = null,
    val clear: Boolean = emoji != null,
    val alts: List<String> = emptyList()
)

object WordBank {

    private fun w(syl: String, level: Int, emoji: String? = null, clear: Boolean = emoji != null, alts: List<String> = emptyList()): Word {
        val parts = syl.split("-")
        return Word(parts.joinToString(""), parts, level, emoji, clear, alts)
    }

    val words: List<Word> = listOf(
        // ---- nível 1 ----
        w("GA-TO", 1, "🐱"),
        w("PA-TO", 1, "🦆"),
        w("BO-LA", 1, "⚽"),
        w("CA-SA", 1, "🏠"),
        w("DA-DO", 1, "🎲"),
        w("FO-CA", 1, "🦭"),
        w("LU-A", 1, "🌙"),
        w("SA-PO", 1, "🐸"),
        w("VA-CA", 1, "🐄"),
        w("RA-TO", 1, "🐭"),
        w("MA-LA", 1, "🧳"),
        w("PI-PA", 1, "🪁"),
        w("U-VA", 1, "🍇"),
        w("O-VO", 1, "🥚"),
        w("SOL", 1, "🌞"),
        w("PÃO", 1, "🍞"),
        w("MÃO", 1, "✋"),
        w("PÉ", 1, "🦶"),
        w("BO-CA", 1, "👄"),
        w("DE-DO", 1),
        w("ME-SA", 1),
        w("CA-MA", 1, "🛏️"),
        w("FA-DA", 1, "🧚"),
        w("BO-LO", 1, "🎂"),
        w("SU-CO", 1, "🧃"),
        w("MA-ÇÃ", 1, "🍎"),
        w("PE-RA", 1, "🍐"),
        w("MO-TO", 1, "🏍️"),
        w("LO-BO", 1, "🐺"),
        w("TA-TU", 1),
        w("FO-GO", 1, "🔥"),
        w("BE-BÊ", 1, "👶"),
        w("VE-LA", 1, "🕯️"),
        w("SI-NO", 1, "🔔"),
        w("CO-PO", 1),
        w("CA-JU", 1),
        w("MA-CA-CO", 1, "🐒"),
        w("BA-NA-NA", 1, "🍌"),
        w("SA-PA-TO", 1, "👞"),
        w("CA-VA-LO", 1, "🐴"),
        w("TO-MA-TE", 1, "🍅"),
        w("GI-RA-FA", 1, "🦒"),
        w("PI-PO-CA", 1, "🍿"),
        w("CA-ME-LO", 1, "🐫"),
        w("ME-NI-NO", 1, "👦"),
        w("ME-NI-NA", 1, "👧"),
        w("JA-NE-LA", 1),
        w("BO-NE-CA", 1),
        w("PA-NE-LA", 1),
        // ---- nível 2 ----
        w("CO-BRA", 2, "🐍"),
        w("ZE-BRA", 2, "🦓"),
        w("JA-CA-RÉ", 2, "🐊"),
        w("LE-ÃO", 2, "🦁"),
        w("PEI-XE", 2, "🐟"),
        w("FLOR", 2, "🌸"),
        w("CHA-VE", 2, "🔑"),
        w("NU-VEM", 2, "☁️"),
        w("CAR-RO", 2, "🚗"),
        w("TREM", 2, "🚆"),
        w("A-VI-ÃO", 2, "✈️"),
        w("BAR-CO", 2, "⛵"),
        w("LI-VRO", 2, "📖"),
        w("LÁ-PIS", 2, "✏️"),
        w("DEN-TE", 2, "🦷"),
        w("O-LHO", 2, "👁️"),
        w("NA-RIZ", 2, "👃"),
        w("A-BE-LHA", 2, "🐝"),
        w("CO-RU-JA", 2, "🦉"),
        w("POR-CO", 2, "🐷"),
        w("UR-SO", 2, "🐻"),
        w("BA-LÃO", 2, "🎈"),
        w("CHU-VA", 2, "🌧️"),
        w("PI-A-NO", 2, "🎹"),
        w("POL-VO", 2, "🐙"),
        w("NA-VI-O", 2, "🚢"),
        w("TI-GRE", 2, "🐯"),
        w("POR-TA", 2, "🚪"),
        w("CA-MI-SA", 2, "👕", clear = false, alts = listOf("CAMISETA")),
        w("CA-CHOR-RO", 2, "🐶", clear = false, alts = listOf("CÃO", "CACHORRINHO")),
        w("NI-NHO", 2),
        // ---- nível 3 ----
        w("CE-NOU-RA", 3, "🥕"),
        w("A-BA-CA-XI", 3, "🍍"),
        w("BOR-BO-LE-TA", 3, "🦋"),
        w("TAR-TA-RU-GA", 3, "🐢"),
        w("E-LE-FAN-TE", 3, "🐘"),
        w("ES-TRE-LA", 3, "⭐"),
        w("ÁR-VO-RE", 3, "🌳"),
        w("O-RE-LHA", 3, "👂"),
        w("ME-LAN-CI-A", 3, "🍉"),
        w("MO-RAN-GO", 3, "🍓"),
        w("GA-LI-NHA", 3, "🐔"),
        w("CO-E-LHO", 3, "🐰"),
        w("PIN-GUIM", 3, "🐧"),
        w("ES-CO-LA", 3, "🏫"),
        w("QUEI-JO", 3, "🧀"),
        w("MI-LHO", 3, "🌽"),
        w("BI-CI-CLE-TA", 3, "🚲"),
        w("SOR-VE-TE", 3, "🍦"),
        w("FOR-MI-GA", 3, "🐜"),
        w("DI-NOS-SAU-RO", 3, "🦕"),
        w("TU-BA-RÃO", 3, "🦈"),
        w("A-RA-NHA", 3, "🕷️"),
        w("CA-RA-COL", 3, "🐌"),
        w("TE-LE-FO-NE", 3, "☎️"),
        w("HI-PO-PÓ-TA-MO", 3, "🦛"),
        w("I-GRE-JA", 3, "⛪"),
        w("Ô-NI-BUS", 3, "🚌"),
        w("O-VE-LHA", 3, "🐑")
    )

    /** Palavras até o nível pedido; metade das vezes só as do nível exato, para avançar de verdade. */
    fun pool(level: Int, filter: (Word) -> Boolean = { true }): List<Word> {
        val upTo = words.filter { it.level <= level && filter(it) }
        val exact = upTo.filter { it.level == level }
        return if (exact.isNotEmpty() && kotlin.random.Random.nextBoolean()) exact else upTo
    }
}
