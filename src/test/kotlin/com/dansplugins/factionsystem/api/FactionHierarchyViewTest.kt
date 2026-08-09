package com.dansplugins.factionsystem.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Covers [FactionHierarchyView] as a contract rather than as a data holder.
 *
 * The reason this type exists is that consumers derive a rank from it, so the tests that matter are
 * the ones proving it carries enough to do that. [rankOf] below is a consumer, written against
 * nothing but the four fields, and every rung of the ladder is exercised through it. If a future
 * change to this type ever makes a rung underivable, these fail rather than the consumer.
 *
 * The ladder itself is deliberately NOT in MedievalFactions. It is one server's naming of positions
 * MF merely reports, and MF neither stores it nor gates anything on it.
 */
class FactionHierarchyViewTest {

    private val liege = FactionId("liege")
    private val vassalA = FactionId("vassal-a")
    private val vassalB = FactionId("vassal-b")

    private enum class Rank { EMPEROR, KING, DUKE, COUNT, BARON, LORD }

    /**
     * A consumer, using only what the view exposes.
     *
     * Note the order: holding vassals is tested before having a liege, which is what lets a sworn
     * faction keep its crown. Test it the other way round and an empire becomes impossible to
     * express, because vassalising a king would silently demote him.
     */
    private fun rankOf(hierarchy: FactionHierarchyView): Rank = when {
        !hierarchy.hasLiege && hierarchy.vassalsHoldingVassals >= 2 -> Rank.EMPEROR
        hierarchy.hasVassals -> Rank.KING
        !hierarchy.hasLiege -> Rank.LORD
        hierarchy.depthBelowSovereign == 1 -> Rank.DUKE
        hierarchy.depthBelowSovereign == 2 -> Rank.COUNT
        else -> Rank.BARON
    }

    private fun sworn(depth: Int, vassals: List<FactionId> = emptyList(), vassalsHoldingVassals: Int = 0) =
        FactionHierarchyView(liege, vassals, depth, vassalsHoldingVassals)

    private fun sovereign(vassals: List<FactionId> = emptyList(), vassalsHoldingVassals: Int = 0) =
        FactionHierarchyView(null, vassals, 0, vassalsHoldingVassals)

    @Test
    fun independentIsAFactionInNoHierarchyAtAll() {
        val hierarchy = FactionHierarchyView.INDEPENDENT

        assertNull(hierarchy.liege)
        assertFalse(hierarchy.hasLiege)
        assertFalse(hierarchy.hasVassals)
        assertEquals(emptyList<FactionId>(), hierarchy.vassals)
        assertEquals(0, hierarchy.depthBelowSovereign)
        assertEquals(0, hierarchy.vassalsHoldingVassals)
    }

    @Test
    fun theDefaultOnAViewThatDoesNotModelVassalageIsIndependent() {
        val view = object : FactionView {
            override val id = FactionId("x")
            override val name = "X"
            override val description = ""
            override val home = null
            override val memberIds = emptyList<java.util.UUID>()
            override val claimCount = 0
            override val color = "#ffffff"
            override val factionsAtWarWith = emptyList<FactionId>()
            override fun isAtWarWith(other: FactionId) = false
            override fun roleOf(playerId: java.util.UUID): FactionRoleView? = null
        }

        assertEquals(FactionHierarchyView.INDEPENDENT, view.hierarchy)
        assertEquals(Rank.LORD, rankOf(view.hierarchy))
    }

    @Test
    fun holdingAndBeingHeldAreIndependentFacts() {
        assertTrue(sworn(depth = 1, vassals = listOf(vassalA)).hasLiege)
        assertTrue(sworn(depth = 1, vassals = listOf(vassalA)).hasVassals)
        assertFalse(sovereign().hasLiege)
        assertFalse(sovereign().hasVassals)
    }

    // --- the ladder, rung by rung ---

    @Test
    fun aSovereignRulingTwoRulersIsAnEmperor() {
        assertEquals(
            Rank.EMPEROR,
            rankOf(sovereign(vassals = listOf(vassalA, vassalB), vassalsHoldingVassals = 2))
        )
    }

    /** Ruling one crowned vassal is a thin empire, and deliberately is not one. */
    @Test
    fun oneCrownedVassalIsNotEnoughForAnEmpire() {
        assertEquals(
            Rank.KING,
            rankOf(sovereign(vassals = listOf(vassalA, vassalB), vassalsHoldingVassals = 1))
        )
    }

    @Test
    fun aSovereignRulingOnlySubjectsIsAKing() {
        assertEquals(Rank.KING, rankOf(sovereign(vassals = listOf(vassalA, vassalB))))
    }

    /** The correction that made empires legible: an emperor rules kings, so a vassal keeps his crown. */
    @Test
    fun aVassalWhoHoldsVassalsIsStillAKing() {
        assertEquals(Rank.KING, rankOf(sworn(depth = 1, vassals = listOf(vassalA))))
        assertEquals(Rank.KING, rankOf(sworn(depth = 3, vassals = listOf(vassalA))))
    }

    /** No liege and no vassals: sovereign over one faction and nobody else. */
    @Test
    fun anIndependentFactionHoldingNothingIsALord() {
        assertEquals(Rank.LORD, rankOf(sovereign()))
    }

    @Test
    fun depthDecidesTheRankOfASwornFactionHoldingNothing() {
        assertEquals(Rank.DUKE, rankOf(sworn(depth = 1)))
        assertEquals(Rank.COUNT, rankOf(sworn(depth = 2)))
        assertEquals(Rank.BARON, rankOf(sworn(depth = 3)))
        assertEquals(Rank.BARON, rankOf(sworn(depth = 9)))
    }

    /**
     * A faction with a liege can never be styled a monarch, and it falls out of the data rather than
     * needing a rule: a sovereign rank is only ever reached through the branches that test hasLiege.
     */
    @Test
    fun aSwornFactionIsNeverAnEmperorHoweverManyCrownedVassalsItHolds() {
        assertEquals(
            Rank.KING,
            rankOf(sworn(depth = 1, vassals = listOf(vassalA, vassalB), vassalsHoldingVassals = 2))
        )
    }

    /**
     * The interesting half of an earned title: it is derived live, so the structure breaking takes it
     * away in the same breath. Nothing has to remember to revoke it.
     */
    @Test
    fun anEmpireLosesTheTitleTheMomentItFragments() {
        val empire = sovereign(vassals = listOf(vassalA, vassalB), vassalsHoldingVassals = 2)
        assertEquals(Rank.EMPEROR, rankOf(empire))

        // One crowned vassal is granted independence.
        assertEquals(Rank.KING, rankOf(sovereign(vassals = listOf(vassalB), vassalsHoldingVassals = 1)))
        // Or keeps its oath but loses its own last vassal.
        assertEquals(Rank.KING, rankOf(empire.copy(vassalsHoldingVassals = 1)))
        // Or the overlord swears fealty to somebody else.
        assertEquals(Rank.KING, rankOf(empire.copy(liege = liege, depthBelowSovereign = 1)))
    }
}
