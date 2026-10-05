package com.dansplugins.factionsystem.api;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Shared game roster contract. Read on the server thread; implementations must not perform I/O. */
public interface MercenaryRosterSource {
    List<Pledge> pledgedCompanies(String campaignId);
    List<Fighter> registeredFighters(String campaignId);
    /** Nonblocking read generation through a bounded server-thread native effect. Null means deny. */
    default RosterLease tryAcquireRosterLease() { return null; }
    interface RosterLease extends AutoCloseable { @Override void close(); }

    record Pledge(String campaignId, String factionId, String side) {
        public Pledge { Objects.requireNonNull(campaignId); Objects.requireNonNull(factionId); Objects.requireNonNull(side); }
    }
    record Fighter(String campaignId, String factionId, UUID playerId, String side) {
        public Fighter { Objects.requireNonNull(campaignId); Objects.requireNonNull(factionId); Objects.requireNonNull(playerId); Objects.requireNonNull(side); }
    }
}
