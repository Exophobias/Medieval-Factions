package com.dansplugins.factionsystem.gate

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.area.MfBlockPosition
import com.dansplugins.factionsystem.area.MfCuboidArea
import com.dansplugins.factionsystem.db.MfVersionedWrite
import com.dansplugins.factionsystem.faction.MfFactionId
import com.dansplugins.factionsystem.failure.OptimisticLockingFailureException
import com.dansplugins.factionsystem.jooq.Tables.MF_GATE
import com.dansplugins.factionsystem.jooq.tables.records.MfGateRecord
import org.bukkit.Material
import org.jooq.DSLContext
import java.util.*

class JooqMfGateRepository(
    private val plugin: MedievalFactions,
    private val dsl: DSLContext
) : MfGateRepository {
    override fun getGate(id: MfGateId): MfGate? {
        return dsl.selectFrom(MF_GATE)
            .where(MF_GATE.ID.eq(id.value))
            .fetchOne()
            ?.toDomain()
    }

    override fun getGates(): List<MfGate> {
        return dsl.selectFrom(MF_GATE)
            .fetch()
            .map { it.toDomain() }
    }

    override fun upsert(gate: MfGate): MfGate {
        // See MfVersionedWrite for why this is not a single guarded upsert (#2076).
        val written = MfVersionedWrite.write(
            update = {
                dsl.update(MF_GATE)
                    .set(MF_GATE.FACTION_ID, gate.factionId.value)
                    .set(MF_GATE.WORLD_ID, gate.area.minPosition.worldId.toString())
                    .set(MF_GATE.MIN_X, gate.area.minPosition.x)
                    .set(MF_GATE.MIN_Y, gate.area.minPosition.y)
                    .set(MF_GATE.MIN_Z, gate.area.minPosition.z)
                    .set(MF_GATE.MAX_X, gate.area.maxPosition.x)
                    .set(MF_GATE.MAX_Y, gate.area.maxPosition.y)
                    .set(MF_GATE.MAX_Z, gate.area.maxPosition.z)
                    .set(MF_GATE.TRIGGER_X, gate.trigger.x)
                    .set(MF_GATE.TRIGGER_Y, gate.trigger.y)
                    .set(MF_GATE.TRIGGER_Z, gate.trigger.z)
                    .set(MF_GATE.MATERIAL, gate.material.name)
                    .set(MF_GATE.STATUS, gate.status.name)
                    .set(MF_GATE.VERSION, gate.version + 1)
                    .where(MF_GATE.ID.eq(gate.id.value))
                    .and(MF_GATE.VERSION.eq(gate.version))
                    .execute()
            },
            rowExists = {
                dsl.select(MF_GATE.ID).from(MF_GATE).where(MF_GATE.ID.eq(gate.id.value)).forUpdate().fetchOne() != null
            },
            insert = {
                dsl.insertInto(MF_GATE)
                    .set(MF_GATE.ID, gate.id.value)
                    .set(MF_GATE.VERSION, 1)
                    .set(MF_GATE.FACTION_ID, gate.factionId.value)
                    .set(MF_GATE.WORLD_ID, gate.area.minPosition.worldId.toString())
                    .set(MF_GATE.MIN_X, gate.area.minPosition.x)
                    .set(MF_GATE.MIN_Y, gate.area.minPosition.y)
                    .set(MF_GATE.MIN_Z, gate.area.minPosition.z)
                    .set(MF_GATE.MAX_X, gate.area.maxPosition.x)
                    .set(MF_GATE.MAX_Y, gate.area.maxPosition.y)
                    .set(MF_GATE.MAX_Z, gate.area.maxPosition.z)
                    .set(MF_GATE.TRIGGER_X, gate.trigger.x)
                    .set(MF_GATE.TRIGGER_Y, gate.trigger.y)
                    .set(MF_GATE.TRIGGER_Z, gate.trigger.z)
                    .set(MF_GATE.MATERIAL, gate.material.name)
                    .set(MF_GATE.STATUS, gate.status.name)
                    .execute()
            }
        )
        if (!written) throw OptimisticLockingFailureException("Invalid version: ${gate.version}")
        return getGate(gate.id).let(::requireNotNull)
    }

    override fun getGateDisruptions(): List<MfGateDisruption> =
        dsl.fetch(
            "SELECT d.* FROM mf_gate_disruption d WHERE d.phase = 'PREPARED' OR (d.phase = 'COMMITTED' AND EXISTS " +
            "(SELECT 1 FROM mf_gate g WHERE g.id = d.gate_id AND g.version = d.result_version AND g.status = 'DISRUPTED'))"
        ).map(::disruption)

    override fun getGateDisruption(operationId: UUID): MfGateDisruption? =
        dsl.fetchOne("SELECT * FROM mf_gate_disruption WHERE operation_id = ?", operationId.toString())?.let(::disruption)

    override fun getDisruption(operationId: UUID): MfGate? = getGateDisruption(operationId)
        ?.takeIf { it.phase == "COMMITTED" }?.original?.let { it.copy(version = it.version + 1, status = MfGateStatus.DISRUPTED) }

    private fun disruption(row: org.jooq.Record): MfGateDisruption {
        fun number(name: String) = (row.get(name) as Number).toInt()
        val world = UUID.fromString(row.get("world_id", String::class.java))
        val original = MfGate(
            plugin,
            MfGateId(row.get("gate_id", String::class.java)),
            number("result_version") - 1,
            MfFactionId(row.get("faction_id", String::class.java)),
            MfCuboidArea(
                MfBlockPosition(world, number("min_x"), number("min_y"), number("min_z")),
                MfBlockPosition(world, number("max_x"), number("max_y"), number("max_z"))
            ),
            MfBlockPosition(world, number("trigger_x"), number("trigger_y"), number("trigger_z")),
            requireNotNull(Material.getMaterial(row.get("material", String::class.java))),
            MfGateStatus.CLOSED
        )
        return MfGateDisruption(
            UUID.fromString(row.get("operation_id", String::class.java)),
            original,
            requireNotNull(row.get("phase", String::class.java))
        )
    }

    override fun prepareDisruption(operationId: UUID, gate: MfGate): MfGateDisruption = dsl.transactionResult { configuration ->
        val transaction = JooqMfGateRepository(plugin, configuration.dsl())
        transaction.getGateDisruption(operationId)?.let {
            require(it.original == gate) { "Disruption operation identity mismatch" }
            return@transactionResult it
        }
        require(gate.status == MfGateStatus.CLOSED && transaction.getGate(gate.id) == gate) { "Gate revision/state changed" }
        require(
            transaction.dsl.fetchCount(
                transaction.dsl.selectFrom(org.jooq.impl.DSL.table("mf_gate_disruption"))
            .where(org.jooq.impl.DSL.field("gate_id").eq(gate.id.value)).and(org.jooq.impl.DSL.field("phase").eq("PREPARED"))
            ) == 0
        ) {
            "Gate already has an unresolved disruption"
        }
        transaction.insertDisruption(operationId, gate, "PREPARED")
        requireNotNull(transaction.getGateDisruption(operationId))
    }

    override fun finishDisruption(operationId: UUID, gate: MfGate, after: Boolean): MfGateDisruption = dsl.transactionResult { configuration ->
        val transaction = JooqMfGateRepository(plugin, configuration.dsl())
        val prior = requireNotNull(transaction.getGateDisruption(operationId)) { "No durable gate preparation" }
        require(prior.original == gate) { "Disruption operation identity mismatch" }
        val desired = if (after) "COMMITTED" else "CANCELLED"
        if (prior.phase == desired) return@transactionResult prior
        require(prior.phase == "PREPARED") { "Gate disruption is already terminal" }
        require(transaction.getGate(gate.id) == gate) { "Prepared gate identity/revision changed" }
        if (after) transaction.upsert(gate.copy(status = MfGateStatus.DISRUPTED))
        check(
            transaction.dsl.execute(
                "UPDATE mf_gate_disruption SET phase = ? WHERE operation_id = ? AND phase = 'PREPARED'",
            desired,
                operationId.toString()
            ) == 1
        ) { "Gate disruption completion raced" }
        requireNotNull(transaction.getGateDisruption(operationId))
    }

    private fun insertDisruption(operationId: UUID, gate: MfGate, phase: String) {
        val min = gate.area.minPosition; val max = gate.area.maxPosition
        dsl.execute(
            "INSERT INTO mf_gate_disruption (operation_id, gate_id, result_version, faction_id, world_id, " +
                "min_x, min_y, min_z, max_x, max_y, max_z, trigger_x, trigger_y, trigger_z, material, phase) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            operationId.toString(), gate.id.value, gate.version + 1, gate.factionId.value, min.worldId.toString(),
            min.x, min.y, min.z, max.x, max.y, max.z, gate.trigger.x, gate.trigger.y, gate.trigger.z, gate.material.name, phase
        )
    }

    /** Low-level transaction compatibility; production artillery uses prepare/activate/commit. */
    override fun disrupt(operationId: UUID, gate: MfGate): MfGate = dsl.transactionResult { configuration ->
        val transaction = JooqMfGateRepository(plugin, configuration.dsl())
        transaction.getDisruption(operationId)?.let {
            require(it.id == gate.id && it.version == gate.version + 1) { "Disruption operation identity mismatch" }
            return@transactionResult it
        }
        transaction.prepareDisruption(operationId, gate)
        transaction.finishDisruption(operationId, gate, true)
        requireNotNull(transaction.getDisruption(operationId))
    }

    override fun delete(gateId: MfGateId) {
        dsl.deleteFrom(MF_GATE)
            .where(MF_GATE.ID.eq(gateId.value))
            .execute()
    }

    override fun deleteAll(factionId: MfFactionId) {
        dsl.deleteFrom(MF_GATE)
            .where(MF_GATE.FACTION_ID.eq(factionId.value))
            .execute()
    }

    private fun MfGateRecord.toDomain() = MfGate(
        plugin,
        id.let(::MfGateId),
        version,
        factionId.let(::MfFactionId),
        MfCuboidArea(
            MfBlockPosition(worldId.let(UUID::fromString), minX, minY, minZ),
            MfBlockPosition(worldId.let(UUID::fromString), maxX, maxY, maxZ)
        ),
        MfBlockPosition(worldId.let(UUID::fromString), triggerX, triggerY, triggerZ),
        material.let(Material::getMaterial).let(::requireNotNull),
        status.let(MfGateStatus::valueOf)
    )
}
