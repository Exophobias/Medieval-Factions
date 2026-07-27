package com.dansplugins.factionsystem.api

/**
 * Where a faction sits in the liege/vassal hierarchy, as a flat snapshot taken when it was asked for.
 *
 * MedievalFactions models vassalage as a pair of mirrored relationship rows and walks them with its
 * own node types. None of that is exposed here. What a consumer deriving a title, a rank, a display
 * name or a permission tier actually needs is three facts - who this faction swears to, whether it is
 * itself somebody's liege, and how far below the top of its chain it sits - plus one more to tell a
 * realm of realms apart from a realm of subjects. This carries those and nothing else, so the
 * internal tree stays free to change.
 *
 * The snapshot is not live. Vassalage changes the instant a faction swears fealty or declares
 * independence, so ask again rather than caching one of these across events.
 *
 * ### Cost
 * Building one is proportional to the depth of this faction's liege chain plus the number of its
 * direct vassals, and it never touches the database or the world. Concretely: two in-memory lookups
 * per level of the chain, and two per direct vassal. It deliberately does not materialise the vassal
 * subtree, which would grow with the size of the whole realm and is not something to do while
 * rendering a chat line. In a three-level hierarchy with a handful of vassals apiece this is a couple
 * of dozen map lookups and no allocation beyond the returned lists.
 */
data class FactionHierarchyView(
    /** The faction this one has sworn fealty to, or null if it swears to nobody. */
    val liege: FactionId?,
    /** The factions that have sworn fealty to this one, in the order MF holds them. Never null. */
    val vassals: List<FactionId>,
    /**
     * How many liege links separate this faction from the top of its chain.
     *
     * 0 for a faction with no liege, 1 for the direct vassal of a sovereign, 2 for its vassal in
     * turn, and so on. A faction with a liege always reports at least 1, so a consumer can use this
     * and [hasLiege] interchangeably for the "is it sovereign" question.
     */
    val depthBelowSovereign: Int,
    /**
     * How many of this faction's direct vassals are themselves somebody's liege.
     *
     * The one fact that cannot be read off the other three, and the reason this type is not just a
     * liege plus a vassal list. It separates a faction that rules subjects from one that rules
     * rulers, which is the whole distinction between a kingdom and an empire, without handing the
     * caller a subtree to walk. Counted two levels deep and no further.
     */
    val vassalsHoldingVassals: Int
) {

    /** Whether this faction has sworn fealty to another. */
    val hasLiege: Boolean
        get() = liege != null

    /** Whether any faction has sworn fealty to this one. */
    val hasVassals: Boolean
        get() = vassals.isNotEmpty()

    companion object {
        /**
         * A faction that swears to nobody and is sworn to by nobody.
         *
         * The correct answer for any faction outside a hierarchy, and the default a [FactionView]
         * reports when its implementation does not model vassalage at all - a consumer's test fake,
         * typically. Keeping the accessor non-null costs callers nothing at the point of use, which
         * matters when the point of use is a line of chat.
         */
        @JvmField
        val INDEPENDENT = FactionHierarchyView(
            liege = null,
            vassals = emptyList(),
            depthBelowSovereign = 0,
            vassalsHoldingVassals = 0
        )
    }
}
