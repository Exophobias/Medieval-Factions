-- The host keeps the claim. Unclaim cascades the parcel row; a claim-owner update does not,
-- allowing the guest's conquest decision/passage period to be persisted against that same chunk.
-- Guest disband is refused while a live retrieval period exists.
create table `mf_embassy`(
    `world_id` varchar(36) not null,
    `chunk_x` integer not null,
    `chunk_z` integer not null,
    `host_id` varchar(36) not null,
    `guest_id` varchar(36) not null,
    `status` varchar(24) not null,
    `created_at` bigint not null,
    `changed_at` bigint not null,
    `deadline_at` bigint null,
    `conqueror_id` varchar(36) null,
    `paused_at` bigint null,
    `offer_size` integer not null default 1,
    primary key (`world_id`, `chunk_x`, `chunk_z`),
    foreign key (`world_id`, `chunk_x`, `chunk_z`) references `mf_claimed_chunk`(`world_id`, `x`, `z`) on delete cascade,
    -- The service fences dissolution of every live party, including the historical host.
    -- Retain the historical identity independently so recovery never cascades on an old host.
    foreign key (`guest_id`) references `mf_faction`(`id`) on delete cascade
);
