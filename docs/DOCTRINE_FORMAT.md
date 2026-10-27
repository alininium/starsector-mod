# Doctrine file format

A **doctrine** is a data file that tells the enemy commander how to fight: which tactics it uses, when, with which
ships, against which targets. The Java code supplies a fixed set of **tactic types** (hunt isolated ships, focus
fire, escort...). Doctrines combine and tune them, so new behaviours for new fleet compositions need no code: copy
a doctrine, change numbers and conditions, done. Other mods can add their own doctrines the same way.

Format version: 1. All files are JSON (Starsector's loader accepts `#` comments and trailing commas).

## 1. Files

| Path | What |
|---|---|
| `data/config/fleet_commander/settings.json` | Global switches (enable, difficulty, announcements). DESIGN.md section 6 |
| `data/config/fleet_commander/doctrines.csv` | Index of doctrines: `id,file,enabled`. Merged across all mods (standard Starsector CSV merge), so any mod can add a row |
| `data/config/fleet_commander/doctrines/<id>.json` | One doctrine |
| `data/config/fleet_commander/roles.json` | How ships are sorted into roles (anchor, line, skirmisher...). Merged across mods; a doctrine can override roles too |

A doctrine that fails to load is skipped with an error in `starsector.log` naming the file and the field, and the
commander falls back to `default`. Unknown keys are logged as warnings (catches typos) and otherwise ignored.

## 2. Concepts in one picture

```
every think interval:
  facts      <- read the battle (fleets, ships, objectives)          section 4
  phase      <- first doctrine phase whose condition is true          section 5
  for each tactic, highest priority first:                            section 7
      skip unless its `when` holds and the phase is listed
      find targets (filter + score)                                   section 8
      claim free ships that match its `ships` filter                  section 9
      issue / keep / cancel its orders                                section 10
  tidy up assignments
```

A ship belongs to at most one tactic at a time. Higher-priority tactics claim ships first; a ship a tactic no
longer needs goes back to the pool (default search-and-destroy).

## 3. Doctrine file

```jsonc
{
    "id": "wolfpack",
    "name": "Wolfpack",                       // shown in announcements and the log
    "description": "Fast ships hunt down isolated enemies and avoid the enemy line.",
    "extends": "default",                     // optional: start from another doctrine (section 11)

    "select": {                               // when this doctrine is picked (section 12)
        "when": { "all": [ { "fact": "own.share.frigate", ">=": 0.4 },
                           { "fact": "own.avgSpeed", ">=": 110 } ] },
        "factions": ["pirates", "luddic_path"],   // optional; empty or missing = any faction
        "weight": 10                              // higher wins among matching doctrines
    },

    "commander": {
        "thinkInterval": 2.0,                 // seconds between decision passes (randomised +-20%)
        "reactionTime": 1.0,                  // delay before a new order is actually given
        "scoreNoise": 0.1,                    // +-10% random noise on target scores
        "useCommandPoints": true,             // orders cost CP like the player's
        "cpReserve": 1,                       // keep this many CP for tactics with "cpPriority": "high"
        "announce": true
    },

    "phases": [                               // first match wins; the last one should have no `when`
        { "id": "opening", "when": { "fact": "battle.time", "<": 40 } },
        { "id": "winning", "when": { "fact": "battle.strengthRatio", ">=": 1.8 } },
        { "id": "losing",  "when": { "fact": "battle.strengthRatio", "<=": 0.6 } },
        { "id": "main" }
    ],

    "roles": { },                             // optional role overrides (section 6)

    "tactics": [ /* section 7 */ ]
}
```

## 4. Facts

Facts are read-only numbers (booleans are 0/1) computed once per pass. Conditions and scores refer to them by name.
Only enemy ships we can see count (`engine.isAwareOf`): the commander doesn't cheat through the fog of war.
"Weight" means Starsector's own ship weight (`Misc.getShipWeight`): frigate 1, destroyer 2, cruiser 4, capital 8,
phase frigate/destroyer +2, civilian x0.25.

### Battle

| Fact | Meaning |
|---|---|
| `battle.time` | Seconds since the commander took over |
| `battle.strengthRatio` | Our deployed weight / enemy visible deployed weight |
| `battle.totalRatio` | Same, including reserves (our reserves known; enemy reserves from the fleet manager) |
| `battle.objectives` / `battle.objectivesHeld` / `battle.objectivesEnemy` | Count of objectives, held by us, held by the enemy |
| `battle.cp` | Our command points left |
| `battle.isStationBattle` | 1 if either side has a station |
| `battle.playerFlagshipVisible` | 1 if we can see the player's ship |

### Fleets: `own.*` and `enemy.*`

| Fact | Meaning |
|---|---|
| `X.weight` | Deployed weight |
| `X.ships` | Deployed ship count (no fighters) |
| `X.lossFraction` | Weight lost (destroyed, disabled, retreated) / weight ever deployed |
| `X.avgSpeed` | Average max speed, weighted |
| `X.share.frigate` / `.destroyer` / `.cruiser` / `.capital` | Share of weight by size |
| `X.share.carrier` | Share of weight on ships with 2+ fighter bays |
| `X.share.phase` | Share on phase ships |
| `X.share.civilian` | Share on civilian hulls |
| `X.share.shieldless` | Share on ships with no shield (or phase) |
| `X.share.omniShield` | Share on omni shields |
| `X.armorPerWeight` | Average armour rating per weight (high = low-tech brick) |
| `X.damage.kinetic` / `.he` / `.energy` / `.frag` | Share of sustained DPS by damage type, from the fleet's weapons |
| `X.centerDistance` | Distance between the two fleets' centres of mass |

### Ships (in target scores and ship filters)

Each fact is 0..1 unless it says otherwise.

| Fact | Meaning |
|---|---|
| `value` | Weight / 8 (a capital = 1) |
| `dp` | Deployment points (raw number) |
| `size.frigate` ... `size.capital` | 1 if that size |
| `hull` / `hullMissing` | Hull level / 1 - hull level |
| `armor` / `armorMissing` | Average armour left across the grid / lost |
| `flux` / `hardFlux` | Flux level / hard flux level |
| `overloaded` / `venting` | 1 while overloaded / venting |
| `shieldDown` | 1 if it has a shield and it is off right now |
| `noShield` | 1 if the hull has no shield |
| `phase` / `phased` / `phaseFluxed` | Phase ship / cloaked right now / phase ship above 50% hard flux (slowed, must uncloak soon) |
| `carrier` / `civilian` | Has 2+ fighter bays / civilian hull |
| `cr` / `lowCR` | Combat readiness / 1 if below 40% |
| `speed` | Max speed (raw) |
| `isolation` | `1 / (1 + support / ownWeight)`: 1 = completely alone, 0.5 = support equals its own weight |
| `support` | Raw weight of its friends within `isolationRange` (default 2000 su) |
| `threatHere` | Raw weight of *its* enemies within 2000 su (for our ships: enemies near them) |
| `strayDistance` | Distance from its own fleet's centre of mass / 5000, capped at 1 |
| `distance` | Distance to the claiming pack (or our centre of mass) / 10000, capped at 1 |
| `retreating` | 1 if retreating |
| `fluxLocked` | 1 if flux > 0.8 and it is moving away from its target (backed off) |
| `playerFlagship` | 1 for the player's own ship |
| `flagship` | 1 for the fleet's flagship (theirs or ours) |
| `officer` | 1 if it has an officer |
| `damageMatch` | How well the attackers' damage types fit this target **now**: kinetic counts against a raised shield with flux left, HE against no shield / shield down / overloaded, energy against both, frag low against both. 0..1 |
| `attackers` | Raw number of our ships already ordered against it |
| `age` | Seconds it has been this tactic's target (for hysteresis) |

## 5. Conditions

Used in `select.when`, `phases[].when`, `tactics[].when`, target `require` and ship `require`.

```jsonc
{ "fact": "battle.strengthRatio", ">=": 1.5 }       // operators: <  <=  >  >=  ==  !=
{ "fact": "hull", "between": [0.2, 0.6] }
{ "all": [ cond, cond ] }                           // and
{ "any": [ cond, cond ] }                           // or
{ "not": cond }
{ "phase": ["opening", "main"] }                    // current phase is one of these
{ "chance": 0.5 }                                   // true with this probability, rolled once per battle
true                                                // always
```

## 6. Roles

Roles sort our own ships so tactics can ask for "skirmishers" instead of listing hull sizes. A ship gets the
**first** role whose filter matches, in file order. `roles.json` (defaults):

```jsonc
{
    "roles": [
        { "id": "civilian",   "require": { "fact": "civilian", "==": 1 } },
        { "id": "carrier",    "require": { "all": [ { "fact": "carrier", "==": 1 },
                                                    { "not": { "fact": "size.capital", "==": 1 } } ] } },
        { "id": "phase",      "require": { "fact": "phase", "==": 1 } },
        { "id": "anchor",     "require": { "any": [ { "fact": "size.capital", "==": 1 },
                                                    { "fact": "flagship", "==": 1 } ] } },
        { "id": "line",       "require": { "fact": "size.cruiser", "==": 1 } },
        { "id": "hunter",     "require": { "all": [ { "fact": "size.destroyer", "==": 1 },
                                                    { "fact": "speed", ">=": 100 } ] } },
        { "id": "line",       "require": { "fact": "size.destroyer", "==": 1 } },
        { "id": "skirmisher", "require": true }
    ]
}
```

A doctrine's `roles` block replaces this list for battles using that doctrine.

## 7. Tactics

Every tactic entry has common fields plus fields of its `type`:

```jsonc
{
    "id": "hunt_loners",          // unique inside the doctrine; used by `extends` overrides
    "type": "hunt_isolated",      // one of the types in section 13
    "enabled": true,
    "priority": 80,               // higher claims ships first
    "phases": ["main", "winning"],// optional; missing = all phases
    "when": { },                  // optional extra condition
    "cpPriority": "normal",       // "high" may spend the cpReserve, "low" only spends when CP > reserve + 2
    "maxTasks": 2,                // how many targets this tactic runs at once
    "targets": { },               // section 8 (types that pick enemy ships)
    "ships": { },                 // section 9
    "order": "eliminate",         // section 10
    "keep": { },                  // hysteresis and abort rules, section 10
    "announce": "Wolves on {target}!"   // optional; {target}, {count}, {doctrine}
}
```

## 8. Targets: filter, then score

```jsonc
"targets": {
    "require": { "all": [ { "fact": "isolation", ">=": 0.6 },
                          { "fact": "phased", "==": 0 } ] },
    "score": {                         // score = sum of weight x fact
        "value": 1.0,
        "isolation": 3.0,
        "hullMissing": 1.5,
        "flux": 1.0,
        "playerFlagship": 2.0,
        "distance": -2.0
    },
    "minScore": 1.5,
    "isolationRange": 2000
}
```

Score weights can be negative (penalties). Noise from `commander.scoreNoise` is applied after summing.

## 9. Ships: who carries the tactic out

```jsonc
"ships": {
    "roles": ["hunter", "skirmisher", "phase"],    // optional; missing = any role
    "require": { "all": [ { "fact": "flux", "<": 0.5 }, { "fact": "hull", ">": 0.4 } ] },
    "pick": "nearest",                 // nearest | fastest | strongest | weakest
    "strength": 2.0,                   // claim until combined weight >= strength x target weight
    "min": 2,                          // don't start the task with fewer ships
    "max": 4
}
```

Ships that can't take orders (`canBeGivenOrders()` false: stations, modules, some special ships) are never
claimed.

## 10. Orders and keeping them

`order` uses our own names, mapped to the game's assignments in one place in the code:

| `order` | Game call | Target |
|---|---|---|
| `eliminate` | `INTERCEPT` assignment (**mapping to verify in game**, see DESIGN.md) | enemy ship |
| `engage` | `ENGAGE` | enemy ship |
| `harass` | `HARASS` (destroyer or bigger only) | enemy ship |
| `fighter_strike` | `STRIKE` | enemy ship |
| `avoid` / `ignore` | `AVOID` / `IGNORE` | enemy ship |
| `capture` / `assault` / `control` | `CAPTURE` / `ASSAULT` / `CONTROL` | objective |
| `defend` | `DEFEND` | objective, own ship or point |
| `escort_light` / `escort_medium` / `escort_heavy` | `LIGHT_/MEDIUM_/HEAVY_ESCORT` (weight 0, ships given directly) | own ship |
| `rally` | `RALLY_TASK_FORCE` on a waypoint | point |
| `rally_carriers` | `RALLY_CARRIER` on a waypoint | point |
| `move` | waypoint + `RALLY_CIVILIAN` given to one ship (vanilla's own trick) | point |
| `retreat` / `retreat_direct` | `orderRetreat(member, cp, false / true)` | own ship |
| `free` | `orderSearchAndDestroy(member)` | own ship |
| `full_assault` / `full_retreat` | `setFullAssault(true)` / `orderFullRetreat()` | whole fleet |

```jsonc
"keep": {
    "minTime": 8,             // don't drop a task sooner than this (seconds)
    "timeout": 45,            // give up after this long without success
    "switchMargin": 1.3,      // a new target must score 1.3x the current one to replace it
    "abort": { "any": [ { "fact": "target.support", ">": 6 },          // rescuers arrived
                        { "fact": "pack.hullLost", ">": 0.35 },        // we are losing the pack
                        { "fact": "pack.leash", ">": 4000 } ] },       // lured away from our fleet
    "onEnd": "rejoin"         // rejoin (move back to our centre) | free | nothing
}
```

Inside `keep.abort`, `target.*` is the target's ship facts and `pack.*` is: `pack.weight`, `pack.hullLost`
(share of the pack's hull lost since the task started), `pack.leash` (pack centre's distance from our fleet's
centre).

## 11. Inheritance

`"extends": "default"` copies the parent doctrine, then applies this file on top:
* plain fields (`commander`, `select`, `phases`, `roles`) replace the parent's when present;
* `tactics` merge **by `id`**: an entry with a parent's id replaces it field by field (so
  `{ "id": "hunt_loners", "priority": 95 }` changes only the priority), `"enabled": false` switches one off, and
  new ids are added.

## 12. Choosing a doctrine for a battle

At takeover, in this order:
1. **Fleet memory override:** if the enemy campaign fleet (`BattleCreationContext.getOtherFleet()`) has
   `$fc_doctrine` set in its memory, use that doctrine. Lets quest and bounty fleets have a hand-picked brain, from
   rules.csv or code, in this mod or any other.
2. **Settings override:** `settings.json` may map a faction id to a doctrine id (`"factionDoctrines"`).
3. **Best match:** every enabled doctrine whose `select.when` and `select.factions` match; the highest
   `select.weight` wins, ties broken at random.
4. **Fallback:** `default`.

The chosen doctrine is written to the log and, with announcements on, to the combat message log
("Enemy commander (Wolfpack) takes command").

## 13. Tactic types (implemented in code)

Version 0.1 implements the first block; the rest follow. Each type lists its own extra fields; all common fields
from section 7 apply.

| Type | What it does | Default order | Extra fields |
|---|---|---|---|
| `focus_fire` | Converge on the best-scoring enemy ship | `engage` | - |
| `hunt_isolated` | Pack hunts a lone enemy; leash and abort rules matter most | `eliminate` | `isolationRange` |
| `pounce` | Quick strike on an enemy that just overloaded or started venting; short task | `eliminate` | `window` (max seconds) |
| `escort` | Give screens to own ships that match `escortees` | `escort_light` | `escortees` (ship filter like section 9) |
| `retreat_damaged` | Retreat own ships matching `ships.require` | `retreat` | `keepLast` (never retreat the last N combat ships), `keepFlagship` |
| `objectives` | Capture / assault / release objectives by score | `capture` | `assaultWhen` (condition), `release` (condition for dropping Control) |
| `fleet_stance` | Whole-fleet orders when `when` holds | `full_assault` | - |
| `battle_line` | A Defend waypoint ahead of our centre that holds the line together; pulls back wanderers | `defend` | `offset`, `pullBackBeyond` |
| `flank` | Send ships to waypoints off the enemy's flanks, then engage edge targets | `move` then `engage` | `side` (left / right / both), `distance` |
| `harass` | Tie down big enemy ships with fast frigates | `harass` | - |
| `avoid` | Mark enemy ships too strong to fight right now | `avoid` | `release` (condition) |
| `carrier_ops` | Rally carriers behind the line; fighter strikes by score | `fighter_strike` | `rallyOffset` |
| `regroup` | Rally everyone to a point behind the line, then release | `rally` | `fallback` (distance), `holdTime` |

Other mods can register more tactic types from Java (`FCTactics.register("my_type", MyTactic.class)`) and use them
in their doctrines.

## 14. Examples

### 14.1 `default` (balanced; every other doctrine extends it)

```jsonc
{
    "id": "default",
    "name": "Balanced",
    "select": { "when": true, "weight": 0 },
    "commander": { "thinkInterval": 2.0, "reactionTime": 1.0, "scoreNoise": 0.1,
                   "useCommandPoints": true, "cpReserve": 1, "announce": true },
    "phases": [
        { "id": "opening", "when": { "fact": "battle.time", "<": 40 } },
        { "id": "winning", "when": { "fact": "battle.strengthRatio", ">=": 1.8 } },
        { "id": "losing",  "when": { "fact": "battle.strengthRatio", "<=": 0.6 } },
        { "id": "main" }
    ],
    "tactics": [
        { "id": "save_wrecks", "type": "retreat_damaged", "priority": 100, "cpPriority": "high",
          "ships": { "require": { "any": [ { "fact": "hull", "<": 0.3 }, { "fact": "cr", "<": 0.2 } ] } },
          "keepLast": 1, "keepFlagship": true },

        { "id": "pounce", "type": "pounce", "priority": 90, "maxTasks": 1, "window": 6,
          "targets": { "require": { "any": [ { "fact": "overloaded", "==": 1 }, { "fact": "venting", "==": 1 } ] },
                       "score": { "value": 1, "damageMatch": 1, "distance": -3 } },
          "ships": { "require": { "fact": "flux", "<": 0.6 }, "pick": "nearest", "max": 3 } },

        { "id": "hunt_loners", "type": "hunt_isolated", "priority": 80, "phases": ["main", "winning"],
          "maxTasks": 1,
          "targets": { "require": { "fact": "isolation", ">=": 0.6 },
                       "score": { "value": 1, "isolation": 3, "hullMissing": 1, "playerFlagship": 2,
                                  "distance": -2 },
                       "minScore": 2 },
          "ships": { "roles": ["hunter", "skirmisher", "phase"], "pick": "nearest",
                     "strength": 2.0, "min": 2, "max": 4,
                     "require": { "fact": "hull", ">": 0.5 } },
          "order": "eliminate",
          "keep": { "minTime": 8, "timeout": 45, "switchMargin": 1.3, "onEnd": "rejoin",
                    "abort": { "any": [ { "fact": "target.isolation", "<": 0.35 },
                                        { "fact": "pack.hullLost", ">": 0.35 },
                                        { "fact": "pack.leash", ">": 4000 } ] } },
          "announce": "hunting the isolated {target}" },

        { "id": "focus", "type": "focus_fire", "priority": 60, "maxTasks": 2,
          "targets": { "score": { "value": 1, "flux": 2, "hardFlux": 1, "hullMissing": 2, "damageMatch": 1,
                                  "playerFlagship": 0.5, "distance": -2 } },
          "ships": { "roles": ["line", "anchor", "hunter"], "strength": 1.5, "max": 4 },
          "order": "engage",
          "keep": { "minTime": 10, "switchMargin": 1.3 } },

        { "id": "screens", "type": "escort", "priority": 50,
          "escortees": { "any": [ { "fact": "carrier", "==": 1 }, { "fact": "flagship", "==": 1 } ] },
          "ships": { "roles": ["skirmisher", "hunter"], "pick": "nearest", "max": 1 },
          "order": "escort_light" },

        { "id": "points", "type": "objectives", "priority": 40,
          "phases": ["opening", "main", "winning"],
          "ships": { "roles": ["skirmisher"] },
          "assaultWhen": { "fact": "battle.strengthRatio", ">=": 1.3 },
          "targets": { "score": { "distance": -3 } } },

        { "id": "mop_up", "type": "fleet_stance", "priority": 10,
          "when": { "all": [ { "fact": "battle.totalRatio", ">=": 3 }, { "fact": "battle.time", ">": 60 } ] },
          "order": "full_assault" }
    ]
}
```

### 14.2 `wolfpack` (fast frigate/destroyer fleets, pirates)

Only what differs from `default`:

```jsonc
{
    "id": "wolfpack",
    "name": "Wolfpack",
    "extends": "default",
    "select": { "when": { "all": [ { "fact": "own.share.frigate", ">=": 0.4 },
                                   { "fact": "own.avgSpeed", ">=": 110 } ] },
                "factions": ["pirates", "luddic_path"], "weight": 10 },
    "tactics": [
        { "id": "hunt_loners", "priority": 95, "maxTasks": 2, "phases": ["opening", "main", "winning"],
          "targets": { "require": { "fact": "isolation", ">=": 0.5 } } },
        { "id": "dont_joust", "type": "avoid", "priority": 85,
          "targets": { "require": { "all": [ { "fact": "size.capital", "==": 1 },
                                             { "fact": "support", ">=": 4 } ] } },
          "release": { "phase": ["winning"] } },
        { "id": "focus", "enabled": false }
    ]
}
```

### 14.3 `battle_line` (slow, heavily armoured fleets)

```jsonc
{
    "id": "battle_line",
    "name": "Battle line",
    "extends": "default",
    "select": { "when": { "all": [ { "fact": "own.avgSpeed", "<": 70 },
                                   { "fact": "own.armorPerWeight", ">=": 250 } ] }, "weight": 10 },
    "tactics": [
        { "id": "hold_line", "type": "battle_line", "priority": 70, "offset": 600, "pullBackBeyond": 2500,
          "ships": { "roles": ["anchor", "line"] } },
        { "id": "focus", "priority": 75,
          "targets": { "score": { "flux": 3, "hardFlux": 2, "shieldDown": 2, "distance": -4 } } },
        { "id": "hunt_loners", "ships": { "roles": ["skirmisher"] } }
    ]
}
```

### 14.4 A quest fleet with a hand-picked doctrine

```java
fleet.getMemoryWithoutUpdate().set("$fc_doctrine", "wolfpack");
```
