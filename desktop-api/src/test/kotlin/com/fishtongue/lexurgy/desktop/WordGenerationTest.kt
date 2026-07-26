package com.fishtongue.lexurgy.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WordGenerationTest {
    private val profile = WordGenerationProfile(
        categories = listOf(
            PhonemeCategory("C", listOf(WeightedSymbol("k", 2), WeightedSymbol("ng", 1))),
            PhonemeCategory("V", listOf(WeightedSymbol("a", 1), WeightedSymbol("i", 1))),
        ),
        templates = listOf(WeightedTemplate("{C}{V}", 3), WeightedTemplate("{C}{V}{C}", 1)),
        syllableCounts = listOf(WeightedSyllableCount(1, 2), WeightedSyllableCount(2, 1)),
        forbiddenPatterns = listOf("ngng"),
    )

    @Test
    fun splitMix64HasFixedVectors() {
        val random = SplitMix64(0uL)
        assertEquals(0xE220A8397B1DCDAFuL, random.nextULong())
        assertEquals(0x6E789E6AA1B965F4uL, random.nextULong())
        assertEquals(0x06C45D188009454FuL, random.nextULong())
    }

    @Test
    fun validProfileSupportsMultiCharacterSymbols() {
        assertTrue(validateProfile(WORDGEN_PROFILE_VERSION, profile).valid)
    }

    @Test
    fun unknownCategoryIsRejectedWithAPath() {
        val result = validateProfile(
            WORDGEN_PROFILE_VERSION,
            profile.copy(templates = listOf(WeightedTemplate("{missing}", 1))),
        )
        assertFalse(result.valid)
        assertEquals("templates[0].pattern", result.issues.single().path)
    }
}
