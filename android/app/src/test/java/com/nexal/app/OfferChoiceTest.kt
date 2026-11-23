package com.nexal.app

import com.nexal.app.data.repository.OfferCandidate
import com.nexal.app.data.repository.OfferPhase
import com.nexal.app.data.repository.chooseOffer
import com.nexal.app.data.repository.weeklyMicros
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OfferChoiceTest {
    private val monthly = OfferPhase(12_990_000, "$12.99", "P1M", 0)
    private val yearly = OfferPhase(109_990_000, "$109.99", "P1Y", 0)

    private val baseMonthly = OfferCandidate(null, emptyList(), listOf(monthly), "base-m")
    private val baseYearly = OfferCandidate(null, emptyList(), listOf(yearly), "base-y")
    private val trial = OfferCandidate("free-trial-14d", emptyList(), listOf(OfferPhase(0, "Free", "P2W", 1), yearly), "trial")
    private val intro = OfferCandidate("intro-first-month", listOf("intro"), listOf(OfferPhase(4_990_000, "$4.99", "P1M", 1), monthly), "intro")
    private val winback = OfferCandidate("winback-50", listOf("winback"), listOf(OfferPhase(6_490_000, "$6.49", "P1M", 1), monthly), "winback")

    private val all = listOf(trial, baseYearly, baseMonthly, intro, winback)

    @Test
    fun `new users get the intro month, never the trial`() {
        assertEquals("intro", chooseOffer(all, "P1M", lapsedSubscriber = false)?.token)
        assertEquals("base-y", chooseOffer(all, "P1Y", lapsedSubscriber = false)?.token)
    }

    @Test
    fun `lapsed subscribers get the win-back price`() {
        assertEquals("winback", chooseOffer(all, "P1M", lapsedSubscriber = true)?.token)
    }

    @Test
    fun `win-back never reaches new users`() {
        val noIntro = listOf(baseMonthly, winback)
        assertEquals("base-m", chooseOffer(noIntro, "P1M", lapsedSubscriber = false)?.token)
    }

    @Test
    fun `returning user who still qualifies for nothing special gets the base plan`() {
        assertEquals("base-m", chooseOffer(listOf(baseMonthly), "P1M", lapsedSubscriber = true)?.token)
    }

    @Test
    fun `a period with only a trial offer has nothing to sell`() {
        assertNull(chooseOffer(listOf(trial), "P1Y", lapsedSubscriber = false))
    }

    @Test
    fun `intro phase is the cheaper first phase`() {
        assertEquals(4_990_000L, intro.introPhase?.priceMicros)
        assertNull(baseMonthly.introPhase)
    }

    @Test
    fun `weekly price`() {
        assertEquals(2_997_692L, weeklyMicros(monthly))
        assertEquals(2_115_192L, weeklyMicros(yearly))
    }
}
