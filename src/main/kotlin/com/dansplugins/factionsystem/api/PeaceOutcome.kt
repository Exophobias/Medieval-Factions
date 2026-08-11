package com.dansplugins.factionsystem.api

/**
 * What laying one faction's half of a war down actually achieved.
 *
 * A war in MedievalFactions is two mirrored `AT_WAR` rows, one owned by each side, and
 * [MedievalFactionsApi.layDownArms] deletes only the caller's. Whether that ends the war depends
 * entirely on what the other side is still holding, and the difference is not a detail a consumer can
 * paper over: "we have laid down our arms and they have not" and "the war is over" are two different
 * announcements, and only one of them is peace.
 *
 * Returned inside an [ApiOutcome] rather than as a boolean, so a caller cannot read the answer
 * backwards. `true` would have to mean one of these two and nothing at the call site would say which.
 *
 * @since the Patriam fork
 */
enum class PeaceOutcome {

    /**
     * The caller's rows are gone and the other faction's still stand, so the two are still at war.
     *
     * This is what `/f makepeace` calls a peace request: the war ends when the other side lays its own
     * half down, and until then nothing about the fighting has changed. Both factions still report
     * each other in [FactionView.factionsAtWarWith], because MF reads a war from a row in *either*
     * direction.
     *
     * No API event is fired for this. MF publishes nothing for a peace request -- its command
     * announces one in chat, from the command body -- so a consumer that wants this said out loud
     * must say it itself.
     */
    PEACE_REQUESTED,

    /**
     * That was the last `AT_WAR` row between them, so the war is over.
     *
     * Reached either because the other faction had already laid its half down, or because it never
     * held one. [event.FactionWarEndedEvent] is fired, so a consumer must not assume it is the only
     * observer, and must not announce peace twice.
     */
    PEACE_MADE
}
