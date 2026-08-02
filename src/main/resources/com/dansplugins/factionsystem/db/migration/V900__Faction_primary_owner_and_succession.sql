-- Numbered 900, not 9, and the gap is deliberate.
--
-- This is a FORK. Upstream Medieval-Factions is at V8 and its next migration will be V9 -- and
-- Flyway refuses to start at all when two migrations share a version: "Found more than one
-- migration with version 9", thrown before any faction data loads. Renaming to fix that after a
-- server has applied it means editing the schema-history table on live data, because the version is
-- the key Flyway records. So the fork's migrations live in a reserved band from 900 up, leaving 9
-- through 899 to upstream.
--
-- Records who the head of a faction is, instead of inferring it from a role name.
--
-- primary_owner_id is nullable. MedievalFactions already ships factions.allowLeaderlessFactions,
-- which gates /f admin create (creates a faction with an empty member list) and changes /f leave so
-- the last member can walk out and leave the faction standing. A faction with no members cannot have
-- a head, so "no primary owner" is a state the plugin must already be able to represent. It is not
-- the ordinary outcome of a departure though: succession runs first, and only an empty faction whose
-- config permits it ends up here.
--
-- heir_id is the head's own nomination, taking precedence over every automatic rule. It is separate
-- from primary_owner_id because it names someone who is NOT the head yet, and it is cleared the
-- moment it is either used or the nominee stops being a member.
--
-- Existing faction rows are deliberately left null rather than backfilled. The only signal a backfill
-- could use is the "Owner" role name this column exists to stop trusting, so seeding the trusted
-- column from the forgeable one would simply carry the forgery forward. Operators appoint a head with
-- /f admin setleader.
--
-- joined_at exists because succession has to break ties by standing, and MF had no record of when a
-- player joined. Without it "the longest-standing member" would mean "whichever row the database
-- happens to return first", which under a clustered primary key is uuid order - stable, but arbitrary
-- and nothing to do with standing. Epoch milliseconds rather than a timestamp type so the column
-- behaves identically on MariaDB and H2, both of which MF supports. Default 0 so pre-existing members
-- sort as founding members, which is the truthful reading of "we do not know, but they were here
-- first".
--
-- No foreign keys, matching every other column added after V1. Nothing in MF deletes a player row, so
-- there is no cascade to define, and both owner columns are cleared whenever the player they name
-- stops being a member anyway.
alter table `mf_faction`
    add `primary_owner_id` varchar(36) null;

alter table `mf_faction`
    add `heir_id` varchar(36) null;

alter table `mf_faction_member`
    add `joined_at` bigint not null default 0;
