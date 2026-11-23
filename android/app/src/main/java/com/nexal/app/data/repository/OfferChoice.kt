package com.nexal.app.data.repository

/** Offer tag in Play Console for the discounted first month new subscribers get. */
const val OFFER_TAG_INTRO = "intro"

/** Offer tag in Play Console for the comeback price shown to lapsed subscribers. */
const val OFFER_TAG_WINBACK = "winback"

/** One pricing phase of a Play offer, in a form that can be tested without Play. */
data class OfferPhase(
    val priceMicros: Long,
    val formattedPrice: String,
    val billingPeriod: String,
    val cycles: Int,
    val currencyCode: String = ""
)

data class OfferCandidate(
    val offerId: String?,
    val tags: List<String>,
    val phases: List<OfferPhase>,
    val token: String
) {
    /** The price that renews, i.e. the last phase. */
    val recurring: OfferPhase? get() = phases.lastOrNull()

    /** A cheaper first phase, if this offer has one (intro or win-back). */
    val introPhase: OfferPhase?
        get() {
            val first = phases.firstOrNull() ?: return null
            val last = recurring ?: return null
            return first.takeIf { phases.size >= 2 && it.priceMicros in 1 until last.priceMicros }
        }

    val hasFreePhase: Boolean get() = phases.any { it.priceMicros == 0L }
}

/**
 * Picks the offer to sell for a base plan with renewal [period] ("P1M"/"P1Y").
 * Nexal no longer gives free trials, so trial offers are skipped even while
 * still active in Play. Lapsed subscribers get the win-back price; everyone
 * else gets the intro offer when Play says they qualify, else the base plan.
 * Play only returns new-customer offers to eligible users, but win-back offers
 * are developer-determined, so the app must keep them from new users itself.
 */
fun chooseOffer(candidates: List<OfferCandidate>, period: String, lapsedSubscriber: Boolean): OfferCandidate? {
    val paid = candidates.filter { it.recurring?.billingPeriod == period && !it.hasFreePhase }
    if (lapsedSubscriber) {
        paid.firstOrNull { OFFER_TAG_WINBACK in it.tags && it.introPhase != null }?.let { return it }
    }
    return paid.firstOrNull { OFFER_TAG_INTRO in it.tags && it.introPhase != null }
        ?: paid.firstOrNull { it.offerId == null }
        ?: paid.firstOrNull { OFFER_TAG_WINBACK !in it.tags }
}

/** Price per week of a recurring phase, e.g. 12.99 a month -> about 3.00 a week. */
fun weeklyMicros(phase: OfferPhase): Long? = when (phase.billingPeriod) {
    "P1W" -> phase.priceMicros
    "P1M" -> phase.priceMicros * 12 / 52
    "P1Y" -> phase.priceMicros / 52
    else -> null
}
