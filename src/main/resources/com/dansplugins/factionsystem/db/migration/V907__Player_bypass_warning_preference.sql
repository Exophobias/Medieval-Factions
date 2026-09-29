-- Existing players keep warnings enabled; only an explicit per-player choice mutes them.
alter table `mf_player`
    add `bypass_warning_muted` boolean not null default false;
