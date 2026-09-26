-- Optional player-facing label; canonical name remains the unique lookup name.
alter table `mf_faction`
    add `display_name` varchar(64) null;
