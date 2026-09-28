# Medieval Factions - Commands Reference

This document provides a comprehensive list of all commands available in the Medieval Factions plugin.

## Table of Contents
- [Command Aliases](#command-aliases)
- [General Commands](#general-commands)
- [Faction Management](#faction-management)
- [Territory & Claims](#territory--claims)
- [Diplomacy & Warfare](#diplomacy--warfare)
- [Vassalage System](#vassalage-system)
- [Roles & Permissions](#roles--permissions)
- [Laws](#laws)
- [Gates](#gates)
- [Locks & Access Control](#locks--access-control)
- [Duels](#duels)
- [Power System](#power-system)
- [Chat System](#chat-system)
- [Applications](#applications)
- [Moderator Approval](#moderator-approval)
- [Admin Commands](#admin-commands)
- [DPC Community API](#dpc-community-api)
- [Permission Groups](#permission-groups)

## Command Aliases

The main faction command can be accessed using any of the following aliases:
- `/faction`
- `/medievalfactions`
- `/mf`
- `/f`
- `/fraktion` (German)

## General Commands

### `/faction help` or `/f help`
**Permission:** `mf.help` (default: true)  
**Description:** Displays a list of helpful commands and usage information.  
**Usage:** `/f help`

### `/faction info [player|faction]` or `/f info [player|faction]`
**Permission:** `mf.info` (default: true)  
**Description:** Displays information about your faction.  
**Usage:** `/f info`  
**Notes:** With `mf.info.other` (default: true), an argument can be given to view another player's faction or a faction by name instead of your own; this also allows console usage.

### `/faction who [player]` or `/f who [player]`
**Permission:** `mf.who` (default: true)  
**Description:** Displays which faction a player belongs to. If no player is specified, shows your own faction.  
**Usage:** 
- `/f who` - View your own faction
- `/f who PlayerName` - View another player's faction

### `/faction list` or `/f list`
**Permission:** `mf.list` (default: true)  
**Description:** Lists all factions on the server.  
**Usage:** `/f list`

### `/faction members` or `/f members`
**Permission:** `mf.members` (default: true)  
**Description:** Displays all members of a faction.  
**Usage:** `/f members`

## Faction Management

### `/faction create [name]` or `/f create [name]`
**Permission:** `mf.create` (default: true)  
**Description:** Creates a new faction with the specified name.  
**Usage:** `/f create MyFaction`  
**Notes:** Faction names are limited to 20 characters by default (configurable).

### `/faction disband [faction]` or `/f disband [faction]`
**Permission:** `mf.disband` (default: true)  
**Description:** Disbands your faction. This action is irreversible.  
**Usage:** `/f disband`  
**Notes:** You must be the faction leader to disband the faction, and it must be down to a single member. All claimed land will be unclaimed. With `mf.disband.others` (default: op), a faction name can be given to disband any faction regardless of its member count (admin command).

### `/faction join [faction]` or `/f join [faction]`
**Permission:** `mf.join` (default: true)  
**Description:** Joins a faction that you have been invited to.  
**Usage:** `/f join FactionName`  
**Notes:** With `mf.force.join` (default: op), you can join a faction you were never invited to. Running `/f join FactionName` without an invite shows a clickable confirmation prompt instead of the "not invited" error; appending `-f` (`/f join FactionName -f`) skips the prompt and joins immediately.

### `/faction leave` or `/f leave`
**Permission:** `mf.leave` (default: true)  
**Description:** Leaves your current faction.  
**Usage:** `/f leave`

### `/faction invite [player]` or `/f invite [player]`
**Permission:** `mf.invite` (default: true)  
**Description:** Invites a player to join your faction.  
**Usage:** `/f invite PlayerName`

### `/faction kick [player]` or `/f kick [player]`
**Permission:** `mf.kick` (default: true)  
**Description:** Kicks a player from your faction.  
**Usage:** `/f kick PlayerName`  
**Notes:** With `mf.force.kick` (default: op), a faction name can be given before the player (`/f kick FactionName PlayerName`) to target a faction other than the one you are in. The faction-role check is still applied against the target faction, so you must also hold that faction's kick role permission — the permission selects which faction is acted on, it does not by itself override the faction's own permissions.

### `/faction transfer [player]` or `/f transfer [player]`
**Permission:** `mf.transfer` (default: true)  
**Description:** Hands your faction to another member, making them its primary owner. Only the current primary owner may do this, and the target must already be a member. Running it without confirming prints a Confirm button; nothing changes until you confirm.  
**Usage:** `/f transfer PlayerName`

**Notes:** The new primary owner is also given the faction's top role. You keep whatever role you had, and any nominated heir is cleared.

### `/faction heir [player]` or `/f heir [player]`
**Permission:** `mf.heir` (default: true)  
**Description:** Nominates who inherits your faction if you leave it or are kicked. The nominee may be one of your members, or the leader of a faction that has sworn fealty to yours. Only the current primary owner may do this. Use `/f heir none` to withdraw the nomination.  
**Usage:** `/f heir PlayerName`

**Notes:** A nomination changes nothing until you actually depart, and is forgotten if it stops being true - the nominee leaving, or the vassal declaring independence or replacing its own leader. Without one, the faction passes to its longest-standing member holding the right to disband, and failing that to its longest-standing member with the most authoritative role.

Naming a vassal's leader is the only way an heir can be someone who is not already a member. A player belongs to exactly one faction, so such an heir takes yours by leaving their own, and their departure fires that faction's succession in turn - which may pass it to a vassal's leader in the same way, and so on down the chain. If the vassal cannot spare its leader, the nomination is passed over and the ordinary order applies.

### `/faction set [name|displayname|description|prefix] [value]` or `/f set [name|displayname|description|prefix] [value]`
**Permissions:** 
- `mf.rename` (default: true) - for name
- `mf.displayname` (default: true) plus the faction change-name role permission - for your faction's display name
- `mf.admin` and `mf.admin.displayname` (default: op) - for `--faction` staff targeting
- `mf.desc` (default: true) - for description
- `mf.prefix` (default: true) - for prefix

**Description:** Sets various faction properties.  
**Usage:**
- `/f set name NewName` - Changes faction name
- `/f set displayname People of Olzhar` - Shows `People of Olzhar` while keeping `PeopleOfOlzhar` as the faction key
- `/f set displayname reset` - Restores the faction key as the displayed name
- `/f set displayname --faction PeopleOfOlzhar People of Olzhar` - Staff form, including leaderless admin factions; use a faction ID or quote a key containing spaces
- `/f set description "Our faction description"` - Sets faction description
- `/f set prefix [TAG]` - Sets faction prefix

**Notes:** With `mf.force.rename` (default: op), a faction name or ID can be given before the new name (`/f set name FactionName NewName`) to rename a faction other than the one you are in. As with `mf.force.kick`, the faction-role check still runs against the target faction, so you must also hold that faction's change-name role permission.

Display names are optional, plain text, and limited to 64 characters. They accept letters, digits, spaces, apostrophes, hyphens, underscores, periods, commas, and parentheses. Faction names and IDs remain the keys for commands, uniqueness, and integrations. `%medievalfactions_faction_name%` shows the display name; `%medievalfactions_faction_canonical_name%` exposes the key for integrations.

### `/faction flag [list|set]` or `/f flag [list|set]`
**Permissions:** 
- `mf.flag.list` (default: true)
- `mf.flag.set` (default: true)

**Description:** Manages faction flags (settings).  
**Usage:**
- `/f flag list` - Lists all available flags and their current values
- `/f flag set [flag] [value]` - Sets a flag value

**Example:** `/f flag set color #FF0000`

**Notes:** With `mf.force.flag` (default: op), a faction name or ID can be given as the first argument to list or set flags on a faction other than your own (`/f flag list FactionName [page]`, `/f flag set FactionName [flag] [value]`). Unlike `mf.force.kick` and `mf.force.rename`, this genuinely bypasses the target faction's role permissions; the base `mf.flag.list` / `mf.flag.set` node is still required. The permission also enables faction-name tab completion for both subcommands.

See [FACTION_FLAGS.md](FACTION_FLAGS.md) for a complete list of available flags.

## Territory & Claims

### `/faction embassy` or `/f embassy`
**Permission:** `mf.embassy` (default: true), plus the faction role's **unclaim** capability for host/conqueror actions or **claim** capability for guest actions.
**Description:** Manage a mutually agreed embassy on connected host-owned chunks. The host keeps the claims; the guest receives exclusive peaceful entry and parcel use. Stand in the plot to offer or accept; remote withdrawal and conquest commands accept the addresses shown by `list`.

- `/f embassy offer <realm> [chunks]` — offer one chunk by default, or request a chunk count such as `/f embassy offer Russia 4`. Starting from the current chunk, the selector chooses exactly that many loaded, free host claims connected by shared edges, preferring compact nearby chunks. All selected chunks must be clear of physical inventories/gates. If too few eligible connected chunks are available, no offer is created. Further offers to the same guest must adjoin its active area.
- `/f embassy accept` or `/f embassy decline` — answer the whole pending offer as the guest, while standing in one offered chunk. Declining an expansion preserves the existing active embassy.
- `/f embassy revoke [worldUUID:x,z]` — cancel the pending offer when targeting an offered chunk, or withdraw the active agreement as host when targeting an active/clearing chunk. Remote addresses work from outside the plot.
- `/f embassy release [worldUUID:x,z]` — withdraw the whole ordinary agreement as guest, cancelling any pending expansion too.
- `/f embassy finish` — confirm that the guest has cleared a withdrawn plot early.
- `/f embassy seize [worldUUID:x,z]` or `/f embassy passage [worldUUID:x,z]` — after conquest, the new landholder seizes residual property or grants 14 days of retrieval; unanswered decisions default to passage after seven days.
- `/f embassy info [worldUUID:x,z]` and `/f embassy list` — inspect a plot or all agreements for your realm.

The default faction allowance is **four hosted chunks and four held chunks**, counting offers, active plots and recovery. Configure `factions.defaults.flags.maxEmbassyChunks`; staff with `mf.force.flag` can upgrade an individual realm using `/f flag set <realm> maxEmbassyChunks 8`. Zero blocks new offers. Lowering the allowance preserves existing agreements and retrieval periods.

An ended active embassy enters a 14-day clearing period. Guests may break blocks, use doors and withdraw storage, but cannot construct, deposit, restock or use general block interactions. Host and outsiders remain barred. Actual war suspends peaceful entry and use rules and pauses clearing/passage time; offer and conquest-decision deadlines continue. Peaceful conquest decisions close the affected chunk to everyone until seizure or passage. Redstone-powered pistons, dispensers, droppers and hoppers cannot operate inside a peaceful embassy. Neither realm gains combat protection or a second claim. Fiefs and hosted-faith sites cannot overlap an embassy. Normal stall tax belongs to the MF landholder. Cancel outstanding offers and finish retrieval periods before unclaiming embassy chunks or disbanding either participating realm.

### `/faction claim [radius]` or `/f claim [radius]`
**Permission:** `mf.claim` or `mf.claim.circle` (default: true)  
**Description:** Claims the chunk you are standing in, or claims in a circular radius if specified.  
**Usage:**
- `/f claim` - Claims the current chunk
- `/f claim 2` - Claims chunks in a circular 2-chunk radius
- `/f claim circle 2` - Same as above; `circle` is an explicit alias for the default radius-claiming behavior

**Notes:** 
- Each chunk claimed requires power. Your faction must have enough power to claim land.
- Maximum claim radius is configurable (default: 3).
- The claimed area is a circle (chunks within the given radius of your current chunk), not a square.

### `/faction claim auto` or `/f claim auto`
**Permission:** `mf.claim.auto` or `mf.autoclaim` (default: true)  
**Description:** Toggles automatic claiming. When enabled, chunks are automatically claimed as you walk through them.  
**Usage:** `/f claim auto`

### `/faction claim fill` or `/f claim fill`
**Permission:** `mf.claim.fill` or `mf.claimfill` (default: true)  
**Description:** Fills in unclaimed chunks that are surrounded by your faction's claims.  
**Usage:** `/f claim fill`  
**Notes:** 
- Maximum chunks that can be filled: 100 (default, configurable)
- Maximum depth of recursive filling: 50 (default, configurable)

### `/faction unclaim [radius]` or `/f unclaim [radius]`
**Permission:** `mf.unclaim` (default: true)  
**Description:** Unclaims the chunk you are standing in, or unclaims in a radius if specified.  
**Usage:**
- `/f unclaim` - Unclaims the current chunk
- `/f unclaim 2` - Unclaims chunks in a 2-chunk radius

### `/faction unclaimall` or `/f unclaimall`
**Permission:** `mf.unclaimall` (default: true)  
**Description:** Unclaims all land owned by your faction.  
**Usage:** `/f unclaimall`  
**Notes:** This action requires confirmation and cannot be undone.

### `/faction checkclaim` or `/f checkclaim`
**Permission:** `mf.checkclaim` or `mf.claim.check` (default: true)  
**Description:** Displays information about who owns the chunk you are standing in.  
**Usage:** `/f checkclaim`

### `/faction map [normal|diplomatic]` or `/f map [normal|diplomatic]`
**Permission:** `mf.map` (default: true)  
**Description:** Displays a text-based map of nearby claims.  
**Usage:**
- `/f map` - Shows normal map view
- `/f map normal` - Shows normal map view
- `/f map diplomatic` - Shows diplomatic map view with relationships

### `/faction sethome` or `/f sethome`
**Permission:** `mf.sethome` (default: true)  
**Description:** Sets your faction's home location to your current position.  
**Usage:** `/f sethome`  
**Notes:** The home must be set in your faction's claimed territory.

### `/faction home` or `/f home`
**Permission:** `mf.home` (default: true)  
**Description:** Teleports you to your faction's home location.  
**Usage:** `/f home`  
**Notes:** There is a configurable teleport delay (default: 5 seconds). A successful teleport starts a 30-minute cooldown for that player. The cooldown survives reconnects and normal restarts through player data. A cancelled or failed teleport does not start it. Set `factions.factionHomeCooldownMinutes` to `0` to disable the cooldown.

## Diplomacy & Warfare

### `/faction ally [faction]` or `/f ally [faction]`
**Permission:** `mf.ally` (default: true)  
**Description:** Sends an alliance request to another faction or accepts an incoming alliance request. If `factions.allyDeclarationRequiresApproval` is enabled in config, the request will be held for moderator approval. When approval is required, you can optionally include a reason: `/f ally OtherFaction -- reason for alliance`.  
**Usage:** `/f ally OtherFaction`

### `/faction breakalliance [faction]` or `/f breakalliance [faction]`
**Permission:** `mf.breakalliance` (default: true)  
**Description:** Breaks an existing alliance with another faction.  
**Usage:** `/f breakalliance OtherFaction`

### `/faction declarewar [faction]` or `/f declarewar [faction]`
**Permission:** `mf.declarewar` (default: true)  
**Description:** Declares war on another faction. If `factions.warDeclarationRequiresApproval` is enabled in config, the declaration will be held for moderator approval. When approval is required, you can optionally include a reason: `/f declarewar EnemyFaction -- reason for war`.  
**Usage:** `/f declarewar EnemyFaction`

### `/faction makepeace [faction]` or `/f makepeace [faction]`
**Permission:** `mf.makepeace` (default: true)  
**Description:** Sends a peace request to a faction you are at war with.  
**Usage:** `/f makepeace EnemyFaction`

### `/faction invoke [ally] [enemy]` or `/f invoke [ally] [enemy]`
**Permission:** `mf.invoke` (default: true)  
**Description:** Invokes an allied faction to join you in war against an enemy.  
**Usage:** `/f invoke AlliedFaction EnemyFaction`

### `/faction relationship view [faction1] [faction2]` or `/f relationship view [faction1] [faction2]`
**Permission:** `mf.relationship.view` (default: op)  
**Description:** Views the relationship between two factions.  
**Usage:** `/f relationship view Faction1 Faction2`

### `/faction relationship add [faction1] [faction2] [type]` or `/f relationship add [faction1] [faction2] [type]`
**Permission:** `mf.relationship.add` (default: op)  
**Description:** Forcefully adds a relationship between two factions (admin command).  
**Usage:** `/f relationship add Faction1 Faction2 ally`  
**Relationship Types:** `ally`, `war`, etc.

### `/faction relationship remove [faction1] [faction2] [type]` or `/f relationship remove [faction1] [faction2] [type]`
**Permission:** `mf.relationship.remove` (default: op)  
**Description:** Forcefully removes a relationship between two factions (admin command).  
**Usage:** `/f relationship remove Faction1 Faction2 ally`

## Vassalage System

### `/faction vassalize [faction]` or `/f vassalize [faction]`
**Permission:** `mf.vassalize` (default: true)  
**Description:** Sends a vassalization request to another faction, asking them to become your vassal. If `factions.vassalizeDeclarationRequiresApproval` is enabled in config, the request will be held for moderator approval. When approval is required, you can optionally include a reason: `/f vassalize OtherFaction -- reason for vassalization`.  
**Usage:** `/f vassalize OtherFaction`

### `/faction swearfealty [faction]` or `/f swearfealty [faction]`
**Permission:** `mf.swearfealty` (default: true)  
**Description:** Accepts a vassalization request from another faction, making them your liege.  
**Usage:** `/f swearfealty LiegeFaction`

### `/faction declinevassalization [faction]` or `/f declinevassalization [faction]`
**Permission:** `mf.declinevassalization` (default: true)  
**Description:** Declines a pending vassalization request sent to your faction by another faction.  
**Usage:** `/f declinevassalization OtherFaction`

### `/faction grantindependence [vassal]` or `/f grantindependence [vassal]`
**Permission:** `mf.grantindependence` (default: true)  
**Description:** Grants independence to one of your vassal factions.  
**Usage:** `/f grantindependence VassalFaction`

### `/faction declareindependence` or `/f declareindependence`
**Permission:** `mf.declareindependence` (default: true)  
**Description:** Declares independence from your liege faction, automatically triggering a war.  
**Usage:** `/f declareindependence`

## Roles & Permissions

### `/faction role list` or `/f role list`
**Permission:** `mf.role.list` (default: true)  
**Description:** Lists all roles in your faction.  
**Usage:** `/f role list`

### `/faction role view [role]` or `/f role view [role]`
**Permission:** `mf.role.view` (default: true)  
**Description:** Displays information about a specific role.  
**Usage:** `/f role view Officer`

### `/faction role set [player] [role]` or `/f role set [player] [role]`
**Permission:** `mf.role.set` (default: true)  
**Description:** Sets a player's role in the faction.  
**Usage:** `/f role set PlayerName Officer`

### `/faction role create [name]` or `/f role create [name]`
**Permission:** `mf.role.create` (default: true)  
**Description:** Creates a new role in your faction.  
**Usage:** `/f role create Guard`

### `/faction role delete [name]` or `/f role delete [name]`
**Permission:** `mf.role.delete` (default: true)  
**Description:** Deletes a role from your faction.  
**Usage:** `/f role delete Guard`

### `/faction role rename [name] [new name]` or `/f role rename [name] [new name]`
**Permission:** `mf.role.rename` (default: true)  
**Description:** Renames a role in your faction.  
**Usage:** `/f role rename Guard Protector`

### `/faction role setdefault [name]` or `/f role setdefault [name]`
**Permission:** `mf.role.setdefault` (default: true)  
**Description:** Sets a role as the default role for new members.  
**Usage:** `/f role setdefault Member`

### `/faction role setpermission [role] [permission] [value]` or `/f role setpermission [role] [permission] [value]`
**Permission:** `mf.role.setpermission` (default: true)  
**Description:** Sets a permission for a role.  
**Usage:** `/f role setpermission Officer claim true`

## Laws

### `/faction law add [law]` or `/f law add [law]`
**Permission:** `mf.addlaw` (default: true)  
**Description:** Adds a new law to your faction.  
**Usage:** `/f law add "No griefing within faction territory"`

### `/faction law list` or `/f law list`
**Permission:** `mf.laws` (default: true)  
**Description:** Lists all laws of your faction.  
**Usage:** `/f law list`

### `/faction law remove [id]` or `/f law remove [id]`
**Permission:** `mf.removelaw` (default: true)  
**Description:** Removes a law from your faction using its ID.  
**Usage:** `/f law remove 1`

## Gates

### `/gate create` or `/tor create`
**Permission:** `mf.gate` (default: true)  
**Description:** Initiates the gate creation process. Select blocks to form a gate structure.  
**Usage:** `/gate create`  
**Notes:**
- Minimum height: 3 blocks (default, configurable)
- Maximum blocks: 64 (default, configurable)
- Maximum gates per faction: 5 (default, configurable)

### `/gate remove` or `/tor remove`
**Permission:** `mf.gate` (default: true)  
**Description:** Removes your faction's gate nearest to you, in its entirety. There is no click step and no confirmation prompt.  
**Usage:** `/gate remove`  
**Notes:** Maximum removal distance: 12 blocks (default, configurable). Gates in other worlds are never candidates, however close their coordinates are.

### `/gate cancel` or `/tor cancel`
**Permission:** `mf.gate` (default: true)  
**Description:** Cancels the current gate creation process.  
**Usage:** `/gate cancel`

**Gate Interactions:**
- Right-click on a gate block to toggle it open/closed
- Gates can be controlled with redstone (if enabled)
- Certain blocks are restricted from gates (see config for list)

## Locks & Access Control

### `/lock [cancel]`
**Permission:** `mf.lock` (default: true)  
**Aliases:** `/verschlieBen`, `/verschliessen`, `/verrouiller`  
**Description:** Enables lock mode. Right-click on a block to lock it.  
**Usage:**
- `/lock` - Enables lock mode
- `/lock cancel` - Cancels lock mode

### `/unlock [cancel]`
**Permission:** `mf.unlock` (default: true)  
**Aliases:** `/aufschlieBen`, `/aufschliessen`, `/deverrouiller`  
**Description:** Enables unlock mode. Right-click on a locked block to unlock it.  
**Usage:**
- `/unlock` - Enables unlock mode
- `/unlock cancel` - Cancels unlock mode

**Notes:** Normally you can only unlock blocks you locked yourself. With `mf.force.unlock` (default: op), unlock mode also works on blocks locked by another player — a message naming the lock owner whose protection was bypassed is shown — and breaking another player's locked block unlocks it instead of being refused. (A faction role can also grant a lock bypass for unlock mode, independently of this permission.)

### `/accessors list`
**Permission:** `mf.accessors.list` (default: true)  
**Aliases:** `/accessoren list`, `/accesseurs list`  
**Description:** Right-click on a locked block to view who can access it.  
**Usage:** `/accessors list`

### `/accessors add`
**Permission:** `mf.accessors.add` or `mf.grantaccess` (default: true)  
**Aliases:** `/accessoren add`, `/accesseurs add`  
**Description:** Right-click on a locked block, then click on a player to grant them access.  
**Usage:** `/accessors add`

### `/accessors remove`
**Permission:** `mf.accessors.remove` or `mf.revokeaccess` (default: true)  
**Aliases:** `/accessoren remove`, `/accesseurs remove`  
**Description:** Right-click on a locked block, then click on a player to revoke their access.  
**Usage:** `/accessors remove`

## Duels

### `/duel challenge [player]` or `/duell challenge [player]`
**Permission:** `mf.duel` (default: true)  
**Description:** Challenges another player to a duel.  
**Usage:** `/duel challenge PlayerName`  
**Notes:** Duel duration is configurable (default: 2 minutes)

### `/duel accept [player]` or `/duell accept [player]`
**Permission:** `mf.duel` (default: true)  
**Description:** Accepts a duel challenge from another player.  
**Usage:** `/duel accept PlayerName`

### `/duel cancel [player]` or `/duell cancel [player]`
**Permission:** `mf.duel` (default: true)  
**Description:** Cancels or declines a duel challenge.  
**Usage:** `/duel cancel PlayerName`

## Power System

### `/faction power [player|faction]` or `/f power [player|faction]`
**Permission:** `mf.power` (default: true)  
**Description:** Displays power statistics for yourself or your faction. When viewing a faction, also shows the number of chunks it has claimed; if `factions.limitLand` is enabled, this is shown as `claimed/capacity` where the capacity equals the faction's current power.  
**Usage:** `/f power`  
**Notes:** With `mf.power.view.other` (default: true), an argument can be given to view another player's or faction's power instead of your own; this also allows console usage.

### `/power set [player] [amount]`
**Permission:** `mf.power.set` or `mf.force.power` (default: op)  
**Aliases:** `/macht set`, `/pouvoir set`  
**Description:** Sets a player's power level (admin command).  
**Usage:** `/power set PlayerName 15`

### `/faction bonuspower [faction] [amount]` or `/f bonuspower [faction] [amount]`
**Permission:** `mf.force.bonuspower` (default: op)  
**Description:** Sets bonus power for a faction (admin command).  
**Usage:** `/f bonuspower FactionName 10`

## Chat System

### `/faction chat [faction|vassals|allies]` or `/f chat [faction|vassals|allies]`
**Permission:** `mf.chat` (default: true)  
**Description:** Toggles faction chat modes.  
**Usage:**
- `/f chat faction` - Toggles faction-only chat
- `/f chat vassals` - Toggles chat with vassals
- `/f chat allies` - Toggles chat with allies

### `/faction chat history` or `/f chat history`
**Permission:** `mf.chat.history` (default: true)  
**Description:** Views chat history.  
**Usage:** `/f chat history`

## Applications

### `/apply [faction] [cancel]`
**Permission:** `mf.apply` (default: true)  
**Description:** Sends an application to join a faction or cancels a pending application.  
**Usage:**
- `/apply FactionName` - Sends an application to join the faction
- `/apply FactionName cancel` - Cancels your pending application to that faction

### `/showapps`
**Permission:** `mf.showapps` (default: true)  
**Description:** Shows pending applications to your faction.  
**Usage:** `/showapps`

### `/approveapp [player]`
**Permission:** `mf.approveapp` (default: true)  
**Description:** Approves a player's application to join your faction.  
**Usage:** `/approveapp PlayerName`

### `/denyapp [player]`
**Permission:** `mf.denyapp` (default: true)  
**Description:** Denies a player's application to join your faction.  
**Usage:** `/denyapp PlayerName`

## Moderator Approval

### `/faction approve [id]` or `/f approve [id]`
**Permission:** `mf.approve` (default: op)  
**Description:** Approves a pending faction action (war declaration, alliance, or vassalization) that requires moderator approval. The request ID is provided in the notification sent to moderators when a pending action is created.  
**Usage:** `/f approve <request-id>`

### `/faction deny [id]` or `/f deny [id]`
**Permission:** `mf.approve` (default: op)  
**Description:** Denies a pending faction action. The requesting faction will be notified that their action was denied.  
**Usage:** `/f deny <request-id>`

### `/faction pendingactions` or `/f pendingactions`
**Permission:** `mf.approve` (default: op)  
**Description:** Lists all pending faction actions that are awaiting moderator approval, including the request ID, type, involved factions, and any provided reason.  
**Usage:** `/f pendingactions`

## Admin Commands

### `/faction bypass` or `/f bypass`
**Permission:** `mf.bypass` (default: op)  
**Description:** Toggles bypass mode, allowing you to bypass faction protections.  
**Usage:** `/f bypass`

### `/faction admin create [name]` or `/f admin create [name]`
**Permission:** `mf.admin.create` (default: op)  
**Description:** Creates a leaderless faction that can exist without members.  
**Usage:** `/f admin create FactionName`  
**Notes:** Requires `allowLeaderlessFactions` to be enabled in config.

### `/faction admin protection <faction> [pvp|playerdamage|mobspawning|explosions|firespread] [on|off|reset]` or `/f admin protection ...`
**Permission:** `mf.admin.protection` (default: op)
**Description:** Shows or changes protection within an admin-managed faction's claimed chunks.
**Usage:** `/f admin protection Safe Haven` shows all settings. `/f admin protection Safe Haven pvp off` blocks player attacks; replace `pvp` with `playerdamage` to block all player damage, or `mobspawning` to block all creature spawns. `explosions off` protects blocks and entities from explosions inside the claim; `firespread off` stops natural fire ignition, fire spread, and burning. Use `on` to follow normal server rules and `reset` to remove the override.
**Notes:** Only factions created with `/f admin create` or designated with `/f admin makeleaderless` qualify. The settings default to `on`, so existing factions retain their behavior. `on` does not override global PvP or spawn rules. Player damage and PvP use the victim's claimed location; mob spawning uses the spawn location. Explosion block filtering uses each affected block's claim, and blast damage uses each entity's location. Fire protection uses the block receiving fire or burn. Existing mobs are not removed.

### `/faction admin setleader [faction] [player]` or `/f admin setleader [faction] [player]`
**Permission:** `mf.admin.setleader` (default: op)  
**Description:** Sets a player as the leader of a faction.  
**Usage:** `/f admin setleader FactionName PlayerName`

### `/faction addmember [faction] [player]` or `/f addmember [faction] [player]`
**Permission:** `mf.force.addmember` or `mf.force.join` (default: op)  
**Description:** Forcefully adds a player to a faction (admin command). If the target player is already in another faction, you are prompted to confirm moving them (which removes them from their current faction first). Append the `-f` flag to skip the confirmation and move them immediately.  
**Usage:** `/f addmember FactionName PlayerName` or `/f addmember FactionName PlayerName -f`

---

## DPC Community API

### `/faction dpc optin` or `/mf dpc optin`
**Permission:** `mf.dpc` (default: op)  
**Description:** Enables DPC API sharing. Sets `dpc-api.enabled` to `true` and saves the config.

### `/faction dpc optout` or `/mf dpc optout`
**Permission:** `mf.dpc` (default: op)  
**Description:** Disables DPC API sharing. Sets `dpc-api.enabled` to `false` and saves the config.

### `/faction dpc reminder on` or `/mf dpc reminder on`
**Permission:** `mf.dpc` (default: op)  
**Description:** Enables the DPC opt-in login reminder for operators.

### `/faction dpc reminder off` or `/mf dpc reminder off`
**Permission:** `mf.dpc` (default: op)  
**Description:** Disables the DPC opt-in login reminder for operators.

### `/faction dpc shareip on` or `/mf dpc shareip on`
**Permission:** `mf.dpc` (default: op)  
**Description:** Enables sharing the server IP with the DPC API for server advertising.

### `/faction dpc shareip off` or `/mf dpc shareip off`
**Permission:** `mf.dpc` (default: op)  
**Description:** Disables sharing the server IP with the DPC API.

### `/faction dpc discord <link>` or `/mf dpc discord <link>`
**Permission:** `mf.dpc` (default: op)  
**Description:** Sets the Discord invite link to share on the DPC website alongside faction data. The link must start with `https://discord.gg/` or `https://discord.com/`; other formats are rejected.  
**Usage:** `/mf dpc discord https://discord.gg/yourserver`

### `/faction dpc discord clear` or `/mf dpc discord clear`
**Permission:** `mf.dpc` (default: op)  
**Description:** Clears the Discord invite link from DPC API data.

### `/faction migrate [type]` or `/f migrate [type]`
**Permission:** `mf.migrate` (default: op)  
**Description:** Migrates data between storage backends (database ↔ JSON).  
**Usage:** 
- `/f migrate toJson` - Migrate from database to JSON storage
- `/f migrate toDatabase` - Migrate from JSON to database storage

**Notes:** 
- **Always backup your data before migrating!**
- Migration runs asynchronously and may take several minutes for large datasets
- The target backend must be empty; the command refuses to migrate into one that already holds data
- Run it with no players online — changes made during the migration may not be carried over
- After successful migration, you must:
  1. Stop the server
  2. Update `storage.type` in config.yml to match the new backend
  3. Restart the server
- See [Migration Guide](docs/MIGRATION_GUIDE.md) for detailed instructions

---

## Permission Groups

### Player Permissions
All permissions with `default: true` are available to regular players.

### Admin Permissions
The `mf.admin` permission grants access to all admin commands including:
- `mf.force.*` - All force commands
- `mf.bypass` - Bypass protections
- `mf.relationship.*` - Manage relationships
- `mf.power.set` - Set player power
- `mf.admin.create` - Create leaderless factions
- `mf.admin.protection` - Set protection in admin-managed faction claims
- `mf.admin.setleader` - Set faction leaders
- `mf.migrate` - Migrate between storage backends
- `mf.approve` - Approve/deny pending faction actions
- `mf.dpc` - Manage DPC community API settings

### Force Permissions
The `mf.force.*` nodes (all `default: op`) do not add new commands — each one changes the behavior of an existing command or listener. They are documented with the command they affect; this table is an index.

| Permission | Affects | Effect |
|------------|---------|--------|
| `mf.force.addmember` | [`/f addmember`](#faction-addmember-faction-player-or-f-addmember-faction-player) | Forcefully adds a player to a faction. |
| `mf.force.bonuspower` | [`/f bonuspower`](#faction-bonuspower-faction-amount-or-f-bonuspower-faction-amount) | Sets a faction's bonus power. |
| `mf.force.flag` | [`/f flag list`, `/f flag set`](#faction-flag-listset-or-f-flag-listset) | Lists/sets flags on another faction, bypassing its role permissions. |
| `mf.force.join` | [`/f join`](#faction-join-faction-or-f-join-faction), [`/f addmember`](#faction-addmember-faction-player-or-f-addmember-faction-player) | Joins a faction without an invite (confirmation prompt, or `-f` to skip it). Also grants `/f addmember`. |
| `mf.force.kick` | [`/f kick`](#faction-kick-player-or-f-kick-player) | Targets a faction other than your own; the target faction's role check still applies. |
| `mf.force.power` | [`/power set`](#power-set-player-amount) | Sets a player's power (alternative to `mf.power.set`). |
| `mf.force.rename` | [`/f set name`](#faction-set-namedescriptionprefix-value-or-f-set-namedescriptionprefix-value) | Targets a faction other than your own; the target faction's role check still applies. |
| `mf.force.unlock` | [`/unlock`](#unlock-cancel), block breaking | Unlocks blocks locked by another player. |

`mf.force.claim` and `mf.force.unclaim` are declared in `plugin.yml` and granted by `mf.force.*`, but nothing in the plugin currently reads them — granting them has no effect. See issue [#1987](https://github.com/Dans-Plugins/Medieval-Factions/issues/1987).

`mf.force.kick` and `mf.force.rename` only widen which faction the command targets; the target faction's own role check still applies. `mf.force.flag` behaves differently and does bypass it. See issue [#1988](https://github.com/Dans-Plugins/Medieval-Factions/issues/1988).

## Notes

- Commands in `[brackets]` are required parameters
- Commands in `(parentheses)` are optional parameters
- Many commands have shortened aliases (e.g., `/f` instead of `/faction`)
- Admin commands require operator status by default
- Power requirements apply to land claims and faction size
- Some features may be disabled in the configuration
