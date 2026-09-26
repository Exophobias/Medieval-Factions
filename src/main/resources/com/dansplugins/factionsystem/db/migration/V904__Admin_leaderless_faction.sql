-- Old factions remain regular factions unless staff explicitly designate them.
alter table `mf_faction`
    add `admin_leaderless` boolean not null default false;
