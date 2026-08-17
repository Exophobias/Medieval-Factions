-- Stable identity for one exact primary-owner tenure. Timestamps cannot distinguish an
-- away-and-back transition in the same millisecond, so delayed consumers compare this token too.
-- Existing tenures share the all-zero migration token; the comparison is scoped by faction id and
-- owner UUID, and MfFactionService replaces it on the next actual owner transition.
alter table `mf_faction`
    add `primary_owner_term` varchar(36) not null default '00000000-0000-0000-0000-000000000000';
