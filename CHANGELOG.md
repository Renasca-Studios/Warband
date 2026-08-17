# Changelog

## 1.5.0

Requires Minecraft 26.2. Warband 1.4.0 remains the last release for 26.1.2.

- Updated to Minecraft 26.2. Every behaviour, tactic and config key carries over unchanged; existing worlds keep their faction standing, grudges and named survivors.
- Mobs now scatter from a primed sulfur cube, 26.2's new mob. Its explosive form goes off with the same force as a creeper, so it is treated as the same kind of threat. Sulfur cubes are otherwise left alone — they are not hostile, so Warband gives them no tactics.
- Every name, title and message the mod shows can now be translated. Warband ships English and falls back to it, so nothing changes if you have no language file — but a resource pack or a translation can now replace any of it, including the mob rank ladder, the five faction names and the faction messages.
- Illager mods are now recognised through datapack tags instead of a built-in list. `#warband:illager_like` decides what Warband treats as an illager, with `#warband:illager_support`, `#warband:illager_summoner` and `#warband:faction_seat_boss` for roles. Illager Invasion works as before with no setup; any other illager mod can now be added by a datapack, without waiting on a Warband update.

## 1.4.0

Last major release for 26.1.2.

- Added siege mining: zombies, illagers and ravagers break through walls to reach a target they cannot path to. Breaches heal themselves after `siegeMiningRestoreSeconds` (90 by default), so a siege forces a fight without permanently damaging your base; set `siegeMiningPermanent=true` to keep the damage. Only blocks in the `siegeMiningBlockTag` can be broken — obsidian, deepslate, metal blocks, containers and beds are excluded, so reinforcing a wall keeps it standing. Respects `mobGriefing`, and digging shows block cracks and sound before a block gives way. Dig time scales with block hardness, so soft cover gives way quickly and tough cover genuinely costs the attacker time.
- Added creeper breaching: a creeper that cannot reach you closes on the wall in between and detonates against it.
- Added ladder and vine climbing, so mobs no longer stall at the bottom of a shaft.
- Added creeper, TNT and warden avoidance: nearby mobs scatter instead of walking into a blast.
- Added boat and minecart escape, so a hostile parked in a vehicle breaks out.
- Added difficulty-scaled bow cadence and accuracy for skeletons, tuned with `rangedAccuracyBonusMax` and `rangedCadenceBonusMax`.
- Added ghast fireball volleys and varied blaze fireball timing and spread.
- Added door opening for illagers, piglins and drowned: they work the handle instead of being stopped by a closed door, and shut it behind them. Zombies still break doors down rather than opening them.
- Added tactical barks: mobs make a short sound when they commit to a tactic, so you can hear whether a squad is advancing, circling, pulling back, calling for help, about to lunge, or hunting for you. Six cues rather than one per tactic, each spoken in that mob's own voice, quiet and throttled, and silent for tactics that already announce themselves. Disable with `tacticalBarksEnabled`.
- Added Zombie Break & Build compatibility: when that mod is installed, Warband stands its own siege mining down rather than both mods tearing at the same wall. It does mob block manipulation far more thoroughly, so it owns the domain — everything else in Warband is unaffected, and creeper breaching stays Warband's since that is an explosion rather than mining. Set `siegeMiningDeferToOtherMods=false` to run both.
- Added nemesis scars: a named illager that escapes you remembers *how* the fight went and comes back adapted to it. Burn its squad down and it returns immune to fire; shoot it from range and it returns helmeted; cut it down in melee and it no longer staggers; blow it up and it returns armoured. The revenge party announces what changed, and `/warband intel` lists each survivor's scar.
- Added perception cues: mobs now react when they half-notice you — stopping, turning to look and sniffing the air — and give a distinct all-clear sound once they have lost your trail. Crouching and darkness already shortened how far mobs could see you; this makes that audible, so hiding is something you can play around instead of guess at. Disable with `perceptionCuesEnabled`.
- Added `customMobPools`, which opts modded mobs into Warband's behaviour pools so they gain the matching tactics and roles and squad up with their vanilla counterparts. Modded mobs that already extend a vanilla mob are picked up automatically and need no entry.
- Added a one-time message the first time a player angers a faction, explaining the faction system and pointing at `/warband intel`.
- Added `/warband debug stamp <difficulty>` for ops to re-stamp nearby hostiles at a chosen difficulty.
- Changed difficulty progression to ramp instead of stepping. Role gear now fades in piece by piece and climbs leather → chainmail → iron rather than appearing as a full iron kit at one threshold, squad formations fade in over a band instead of switching on, and the spawn-distance and cave-depth curves are smoothed.
- Changed `regionalSpawnRampBlocks` default 32 → 256; at 32 the world went from calm to fully hostile within a short walk. Existing configs keep their own value, so raise it by hand for the smoother curve.
- Changed spider webs into a zoning tool: they lead a moving target, will not stack or re-trap a player already stuck, and spiders bite instead of webbing at point-blank range.
- Changed early-game mob volume down, which also eases the lag reported in crowded areas.
- Fixed undead abandoning a chase to huddle under nearby cover and pacing in and out of it. Pillaring up and breaking the base below no longer leaves zombies pinned under the leftover blocks; undead only seek shade when not chasing, and burn in daylight as in vanilla.
- Fixed squad members being pulled back to the group while chasing, which stopped hordes from following a player any real distance.
- Fixed `config/warband.properties` being read from the wrong folder on servers started outside the game directory, which made config edits look like they were discarded.
- Fixed config edits needing a full game restart; the file is re-read when a world loads and on `/reload`.
- Fixed creepers ignoring cats and ocelots.
- Fixed spiders reaching a player and then never attacking, or stopping dead in front of them. Ladder climbing also no longer applies to natural climbers like spiders, which vanilla already handles — it is now limited to actual ladders and vines, for mobs that cannot climb on their own.
- Fixed spider webs being inescapable once they landed.
- Fixed squad members spawning inside terrain and on top of each other, which made hordes pile into one spot instead of reaching the player.
- Fixed zombie encirclement giving up when its preferred approach was blocked, putting the whole squad back onto one path.
- Removed zombie stacking. It could never fire — a minimum-distance check ruled it out in exactly the situation it was built for, and it tried to pathfind onto another mob's head, which the game cannot do. Leaping and siege mining cover the same ground and both work.

## 1.3.2

- Added `/warband intel <player>` for ops to inspect another player's faction state.
- Added `/warband clear <player> [faction]` for ops to wipe grudges and heat from a player, optionally scoped to one faction.
- Added spider rain shelter: out-of-combat spiders path to the nearest covered tile when caught in the rain.

## 1.3.1

- Fixed a server crash during raid scans caused by an overly large entity-lookup box.

## 1.3.0

- Added proactive AI: skeletons perch on high ground at dusk, spiders pre-web approach paths, idle zombies cluster into hordes.
- Added spider leap-strike and ceiling-crawl drop attack.
- Added bounty hunter ambush hold when closing without line of sight.
- Added natural jockey acquisition: smart skeletons mount nearby spiders, smart baby zombies mount nearby chickens.
- Added vex bond: vexes die when their summoning evoker dies.
- Added zombie stacking to reach perched players.
- Added raid predation: raiders pillage every animal in range once no village defenders remain.
- Added raid finale bounty hunter on high-ominous raids.
- Added rival faction interception during raids.
- Added faction-colored trophy banner drops on Warmarshal and patrol-captain kills.
- Added passive faction heat decay over time.
- Added rival-kill heat reduction: killing a faction helps their rival forgive you.
- Added faction territories: kills inside a faction's territory hit harder; kills in their rival's territory hit softer.
- Added difficulty floor for spawner, trial spawner, and summoned mobs.
- Added Stormie's Spiders compatibility.
- Changed sun-shelter: undead seek shade at dawn predictively instead of waiting to burn.
- Changed witches to throw real splash potions for buffs.
- Changed zombie encirclement to surround from all angles instead of stacking on one flank.
- Changed retreat behavior to apply only to illagers, piglins, and drowned. Other monsters fight to the death.
- Changed retreat trigger: smart mobs pull back earlier when no allies are nearby.
- Changed backup calls to alert nearby idle squads.
- Fixed jockeys steering their mounts away from the player instead of engaging.
- Fixed spider webs not firing.
- Fixed sun-shelter not triggering.

## 1.2.1

- Tuned bounty hunters down: less bonus health, less speed, no enchanted armor, and no long Speed effect.
- Improved bounty hunter pursuit: smaller hitbox scale for two-block gaps and wind-charge jumps for vertical targets.
- Improved bounty hunter rewards with stronger vanilla loot: more emeralds, XP bottles, and occasional supplies.
- Fixed faction-seat state: Warmarshal crowns/broken seats persist, broken seats stop creating new heat/grudges, and Illager Invasion Invokers are prioritized as Warmarshals when present.
- Removed Warband's vanilla-difficulty scaling hooks; Easy/Normal/Hard no longer scale Warband difficulty or boss ability damage.

## 1.2.0

- Added Illager War advancements, including mansion entry, faction milestones, bounty, crusade, and Warmarshal kill progress.
- Added modded stronghold support: Illager Invasion forts/labyrinths are faction camps, and The Lost Castle is a faction seat.
- Added new hostile tactics: spider ceiling crawl, elevated-target hops, ranged repositioning, stealth/status-aware detection, bogged poison back-dash, stray jump shots, and more reliable Enderman disrupt/provoke behavior.
- Added optional Overworld depth difficulty for harder deepslate caves, with `/warband difficulty` showing raw/applied depth bonus.
- Added the bounty hunter overhaul: diamond gear, melee/ranged weapon swapping, parkour pursuit, taunts, glowing mark, stalk teleport, one revive, ominous summon cue, and `/warband debug bounty`.
- Added faction-war escalation and feedback: heat states, war/crusade patrols, in-world event cues, better witness rules, and clearer `/warband intel`.
- Changed config presets to `CUSTOM`, `VANILLA_PLUS` (`vanilla+` accepted), and `FANTASY`; Vanilla+ disables the more RPG-facing systems by default.
- Changed REGIONAL spawn protection: `safeRadius` now applies in REGIONAL mode, with a separate `regionalSpawnRampBlocks` ramp and tighter defaults.
- Fixed Warmarshal Illusioner conversion reliability in worlds without Illager Invasion.
- Fixed revenge and bounty patrols not immediately pathing toward the target player.
- Fixed revenge and bounty leader nametags rendering through walls.
- Fixed rare false-death states where normal Warband-stamped mobs could play death animation/audio but keep fighting.
- Fixed faction heat not tracking when bounty hunters were disabled.
- Fixed revenge grudges expiring from missed random spawn rolls instead of real failed spawn attempts.

## 1.1.0

- Split `bossAbilitiesEnabled` into `witherAbilitiesEnabled` and `enderDragonAbilitiesEnabled` so each boss can be toggled independently. Existing `bossAbilitiesEnabled` values are read once as the default for both new keys.
- Auto-disable Warband Ender Dragon abilities when [True Ending](https://modrinth.com/mod/true-ending) is loaded — detects both the Fabric mod (`mr_true_ending`) and the datapack distribution (via the `true_ending` data namespace), and re-checks on `/reload`.
- Added `/warband reload` (op-only) to re-read `config/warband.properties` without restarting the world.
- Reduced vanilla difficulty double-dipping by disabling the vanilla regional floor by default.
- Regional difficulty ramps faster by default:
  - `regionalIncreaseDelaySeconds` 10 → 0
  - `regionalBlendRate` 0.08 → 0.20
  - `regionalAccelerationPerSample` 0.01 → 0.03
