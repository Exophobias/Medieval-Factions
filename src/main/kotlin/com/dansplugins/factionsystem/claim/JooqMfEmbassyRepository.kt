package com.dansplugins.factionsystem.claim

import com.dansplugins.factionsystem.faction.MfFactionId
import com.dansplugins.factionsystem.jooq.Tables.MF_EMBASSY
import com.dansplugins.factionsystem.jooq.tables.records.MfEmbassyRecord
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.util.UUID

class JooqMfEmbassyRepository(private val dsl: DSLContext) : MfEmbassyRepository {
    override fun getAll(): List<MfEmbassy> = dsl.selectFrom(MF_EMBASSY).fetch().map { it.toDomain() }

    override fun upsert(embassy: MfEmbassy) {
        upsert(dsl, embassy)
    }

    override fun applyChanges(upserts: List<MfEmbassy>, deletes: List<MfEmbassy>) {
        dsl.transaction { configuration ->
            val transaction = DSL.using(configuration)
            deletes.forEach { delete(transaction, it.worldId, it.chunkX, it.chunkZ) }
            upserts.forEach { upsert(transaction, it) }
        }
    }

    private fun upsert(context: DSLContext, embassy: MfEmbassy) {
        context.insertInto(MF_EMBASSY)
            .set(MF_EMBASSY.WORLD_ID, embassy.worldId.toString())
            .set(MF_EMBASSY.CHUNK_X, embassy.chunkX)
            .set(MF_EMBASSY.CHUNK_Z, embassy.chunkZ)
            .set(MF_EMBASSY.HOST_ID, embassy.hostId.value)
            .set(MF_EMBASSY.GUEST_ID, embassy.guestId.value)
            .set(MF_EMBASSY.STATUS, embassy.status.name)
            .set(MF_EMBASSY.CREATED_AT, embassy.createdAt)
            .set(MF_EMBASSY.CHANGED_AT, embassy.changedAt)
            .set(MF_EMBASSY.DEADLINE_AT, embassy.deadlineAt)
            .set(MF_EMBASSY.CONQUEROR_ID, embassy.conquerorId?.value)
            .set(MF_EMBASSY.PAUSED_AT, embassy.pausedAt)
            .set(MF_EMBASSY.OFFER_SIZE, embassy.offerSize)
            .onConflict(MF_EMBASSY.WORLD_ID, MF_EMBASSY.CHUNK_X, MF_EMBASSY.CHUNK_Z).doUpdate()
            .set(MF_EMBASSY.HOST_ID, embassy.hostId.value)
            .set(MF_EMBASSY.GUEST_ID, embassy.guestId.value)
            .set(MF_EMBASSY.STATUS, embassy.status.name)
            .set(MF_EMBASSY.CREATED_AT, embassy.createdAt)
            .set(MF_EMBASSY.CHANGED_AT, embassy.changedAt)
            .set(MF_EMBASSY.DEADLINE_AT, embassy.deadlineAt)
            .set(MF_EMBASSY.CONQUEROR_ID, embassy.conquerorId?.value)
            .set(MF_EMBASSY.PAUSED_AT, embassy.pausedAt)
            .set(MF_EMBASSY.OFFER_SIZE, embassy.offerSize)
            .execute()
    }

    override fun delete(worldId: UUID, chunkX: Int, chunkZ: Int) {
        delete(dsl, worldId, chunkX, chunkZ)
    }

    private fun delete(context: DSLContext, worldId: UUID, chunkX: Int, chunkZ: Int) {
        context.deleteFrom(MF_EMBASSY)
            .where(MF_EMBASSY.WORLD_ID.eq(worldId.toString()))
            .and(MF_EMBASSY.CHUNK_X.eq(chunkX))
            .and(MF_EMBASSY.CHUNK_Z.eq(chunkZ))
            .execute()
    }

    private fun MfEmbassyRecord.toDomain() = MfEmbassy(
        UUID.fromString(worldId), chunkX, chunkZ, MfFactionId(hostId), MfFactionId(guestId),
        MfEmbassyStatus.valueOf(status), createdAt, changedAt, deadlineAt,
        conquerorId?.let(::MfFactionId), pausedAt, offerSize
    )
}
