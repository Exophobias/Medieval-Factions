create table `mf_gate_disruption`(
    `operation_id` varchar(36) primary key not null,
    `gate_id` varchar(36) not null,
    `result_version` integer not null,
    `faction_id` varchar(36) not null,
    `world_id` varchar(36) not null,
    `min_x` integer not null,
    `min_y` integer not null,
    `min_z` integer not null,
    `max_x` integer not null,
    `max_y` integer not null,
    `max_z` integer not null,
    `trigger_x` integer not null,
    `trigger_y` integer not null,
    `trigger_z` integer not null,
    `material` varchar(64) not null,
    `phase` varchar(16) not null default 'COMMITTED'
);
-- No foreign key to the live gate: the operation receipt survives deletion/rebuilding.
