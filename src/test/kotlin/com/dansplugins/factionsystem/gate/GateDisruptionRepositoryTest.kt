package com.dansplugins.factionsystem.gate

import com.dansplugins.factionsystem.MedievalFactions
import com.dansplugins.factionsystem.area.MfBlockPosition
import com.dansplugins.factionsystem.area.MfCuboidArea
import com.dansplugins.factionsystem.faction.MfFactionId
import org.bukkit.Material
import org.h2.jdbcx.JdbcDataSource
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.util.UUID

class GateDisruptionRepositoryTest {
    @Test fun durableReceiptSurvivesDeleteAndRejectsOperationReuse() {
        val source = JdbcDataSource().apply {setURL("jdbc:h2:mem:gate-${UUID.randomUUID()};MODE=MYSQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1")}
        val dsl = DSL.using(source,SQLDialect.H2)
        dsl.execute("""create table mf_gate(id varchar(36) primary key, version integer, faction_id varchar(36),world_id varchar(36),
            min_x integer,min_y integer,min_z integer,max_x integer,max_y integer,max_z integer,
            trigger_x integer,trigger_y integer,trigger_z integer,material varchar(64),status varchar(32))""")
        val migration = requireNotNull(javaClass.getResource("/com/dansplugins/factionsystem/db/migration/V908__Durable_gate_disruption.sql")).readText()
        dsl.execute(migration)
        val plugin=mock(MedievalFactions::class.java)
        val repository=JooqMfGateRepository(plugin,dsl)
        val world=UUID.randomUUID();val area=MfCuboidArea(MfBlockPosition(world,-2,64,-2),MfBlockPosition(world,2,67,2))
        val original=repository.upsert(MfGate(plugin,factionId=MfFactionId("realm"),area=area,trigger=area.minPosition,material=Material.IRON_BARS))
        val operation=UUID.randomUUID();val disrupted=repository.disrupt(operation,original)
        assertEquals(original.version+1,disrupted.version);assertEquals(MfGateStatus.DISRUPTED,disrupted.status)
        assertEquals(disrupted,repository.disrupt(operation,original))
        repository.delete(original.id)
        assertEquals(disrupted,JooqMfGateRepository(plugin,dsl).getDisruption(operation))
        assertThrows(IllegalArgumentException::class.java) {repository.disrupt(operation,original.copy(id=MfGateId.generate()))}
    }
    @Test fun receiptInsertFailureRollsBackActuatorDisable() {
        val source=JdbcDataSource().apply {setURL("jdbc:h2:mem:gate-rollback-${UUID.randomUUID()};MODE=MYSQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1")}
        val dsl=DSL.using(source,SQLDialect.H2)
        dsl.execute("""create table mf_gate(id varchar(36) primary key,version integer,faction_id varchar(36),world_id varchar(36),
            min_x integer,min_y integer,min_z integer,max_x integer,max_y integer,max_z integer,
            trigger_x integer,trigger_y integer,trigger_z integer,material varchar(64),status varchar(32))""")
        val plugin=mock(MedievalFactions::class.java);val repo=JooqMfGateRepository(plugin,dsl);val p=MfBlockPosition(UUID.randomUUID(),0,64,0)
        val gate=repo.upsert(MfGate(plugin,factionId=MfFactionId("realm"),area=MfCuboidArea(p,p),trigger=p,material=Material.OAK_FENCE))
        val migration=requireNotNull(javaClass.getResource("/com/dansplugins/factionsystem/db/migration/V908__Durable_gate_disruption.sql")).readText()
        dsl.execute(migration)
        dsl.execute("alter table mf_gate_disruption add constraint reject_receipt check (phase <> 'COMMITTED')")
        // The injected receipt constraint fails after the actuator update, so both must roll back.
        assertThrows(Exception::class.java) {repo.disrupt(UUID.randomUUID(),gate)}
        assertEquals(gate,repo.getGate(gate.id))
    }
}
