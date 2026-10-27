# Doctrine file format

A **doctrine** is a data file that tells the enemy commander how to fight: which tactics it uses, when, with which
ships, against which targets. The Java code supplies a fixed set of **tactic types** (hunt isolated ships, focus
fire, escort...). Doctrines combine and tune them, so new behaviours for new fleet compositions need no code: copy
a doctrine, change numbers and conditions, done. Other mods can add their own doctrines the same way.

Format version: 1 (`"formatVersion": 1`, optional). All files are JSON. The game's loader accepts `#` comments;
the examples below use `//` comments only for explanation, which the game does **not** accept. Keys starting with
`_` are free-form notes and are never warned about.

## 1. Files

| Path | What |
|---|---|
| `data/config/fleet_commander/settings.json` | Global switches (enable, difficulty, announcements). DESIGN.md section 6. Merged across mods |
| `data/config/fleet_commander/doctrines.csv` | Index of doctrines: `id,file,enabled`. Merged across all mods (standard Starsector CSV merge), so any mod can add a row |
| `data/config/fleet_commander/doctrines/<id>.json` | One doctrine |
| `data/config/fleet_commander/roles.json` | How ships are sorted into roles (anchor, line, skirmisher...). A doctrine can override roles too |

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
        "commandWindow": 8,                   // after spending a CP, more orders are free for this many seconds
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
| `X.avgArmor` | Average hull armour rating, weighted by ship weight (Onslaught 1750, Enforcer 900, Wolf ~150) |
| `X.damage.kinetic` / `.he` / `.energy` / `.frag` | Share of sustained DPS by damage type, from the fleet's weapons |
| `X.centerDistance` | Distance between the two fleets' centres of mass |

### Ships (in target scores and ship filters)

Each fact is 0..1 unless it says otherwise.

| Fact | Meaning |
|---|---|
| `value` | Weight / 8 (a capital = 1) |
| `weight` | Ship weight (raw) |
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
| `distance` | Distance to our fleet's centre of mass / 10000, capped at 1 |
| `nearestDistance` | Distance to the nearest ship of the other side / 10000, capped at 1 (0.15 = 1500 su) |
| `retreating` | 1 if retreating |
| `fluxLocked` | 1 if flux > 0.8 and it is moving away from its target (backed off) |
| `playerFlagship` | 1 for the player's own ship |
| `flagship` | 1 for the fleet's flagship (theirs or ours) |
| `officer` | 1 if it has an officer |
| `damageMatch` | How well the other side's fleet-wide damage types fit this ship **now**: kinetic counts against a raised shield with flux left, HE against no shield / shield down / overloaded, energy against both, frag low against both. 0..1 |
| `attackers` | Raw number of our ships already ordered against it |
| `age` | Seconds it has been this tactic's target (for hysteresis) |

### Objectives (in the `objectives` tactic)

| Fact | Meaning |
|---|---|
| `distance` | Distance to our fleet's centre / 10000, capped at 1 |
| `nearestDistance` | Distance to our nearest ship / 10000, capped at 1 |
| `threatHere` / `supportHere` | Raw weight of enemy / our ships within 2000 su |
| `held` / `enemyHeld` / `neutral` | Who holds it |

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
    "priority": 80,               // higher claims ships first, and may take ships from lower priorities
    "phases": ["main", "winning"],// optional; missing = all phases
    "when": { },                  // optional extra condition (battle.*, own.*, enemy.* facts)
    "cpPriority": "normal",       // "high" may spend the cpReserve, "low" only spends when CP > reserve + 2
    "maxTasks": 2,                // how many targets this tactic runs at once
    "interval": 0,                // seconds between updates; 0 = commander.thinkInterval (pounce defaults to 0.5)
    "targets": { },               // section 8 (types that pick enemy ships)
    "ships": { },                 // section 9
    "order": "eliminate",         // section 10
    "keep": { },                  // hysteresis and abort rules, section 10
    "announce": "Wolves on {target}!"   // optional; {target}, {count}, {doctrine}; "" = say nothing
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
        "nearestDistance": -3.0
    },
    "minScore": 1.5,
    "isolationRange": 2000             // range for "isolation" and "support" in this tactic
}
```

Score weights can be negative (penalties). The doctrine's `scoreNoise` multiplies each score by a random factor
around 1. For the `objectives` tactic, the subject is an objective, with the objective facts from section 4.

## 9. Ships: who carries the tactic out

```jsonc
"ships": {
    "roles": ["hunter", "skirmisher", "phase"],    // optional; missing = any role
    "require": { "all": [ { "fact": "flux", "<": 0.5 }, { "fact": "hull", ">": 0.4 } ] },
    "pick": "nearest",                 // nearest (to the target) | fastest | strongest | weakest
    "strength": 2.0,                   // claim until combined weight >= strength x target weight
    "min": 2,                          // don't start the task with fewer ships
    "max": 4
}
```

In `ships.require`, bare names are the candidate ship's facts and `target.*` is the target's. Ships that can't
take orders (`canBeGivenOrders()` false: stations, modules, retreating ships) are never claimed. `hunt_isolated`
also refuses to start when the pack can't reach `strength` (`"requireStrength": false` turns that off; the other
types start with whatever they get, down to `min`).

## 10. Orders and keeping them

`order` uses our own names, mapped to the game's assignments in one place in the code (`FCOrders`). The
`orderMapping` setting can change a mapping without a rebuild, e.g. `"orderMapping": { "eliminate": "ENGAGE" }`.

| `order` | Game call | Target |
|---|---|---|
| `eliminate` | `INTERCEPT` assignment (**mapping to verify in game**: see SMOKE_TEST.md step 1) | enemy ship |
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

Ship-targeted assignments are created with weight 0 and given to the claimed ships directly, so the engine doesn't
add other ships to them; objective, Avoid and Ignore assignments keep the engine's own allocation.

**Command points** (when `useCommandPoints` is on): the first order spends one CP and opens a command window of
`commandWindow` seconds in which further orders are free, the way the player's command channel works. Retreats
from a fight-to-the-last battle (`BattleCreationContext.fightToTheLast` or `!aiRetreatAllowed`) are never given.

```jsonc
"keep": {
    "minTime": 8,             // don't abort or replace a task sooner than this (seconds)
    "timeout": 45,            // give up after this long (0 = never)
    "switchMargin": 1.3,      // a new target must score 1.3x the weakest current one to replace it
    "abort": { "any": [ { "fact": "target.support", ">": 6 },          // rescuers arrived
                        { "fact": "pack.hullLost", ">": 0.35 },        // we are losing the pack
                        { "fact": "pack.leash", ">": 4000 } ] },       // lured away from our fleet
    "onEnd": "rejoin"         // rejoin (move back to our fleet) | free (Search & Destroy) | nothing
}
```

Inside `keep.abort`, `target.*` is the target's ship facts and `pack.*` is: `pack.weight`, `pack.ships`,
`pack.hullLost` (share of the pack's hull lost since the task started), `pack.leash` (distance in su from the
pack's centre to our fleet's centre without the pack). A target that is destroyed, or out of sight for 6 seconds,
ends the task too.

## 11. Inheritance

`"extends": "default"` copies the parent doctrine, then applies this file on top:
* `select` is **never** inherited: a doctrine without its own `select` is only used when picked explicitly
  (fleet memory or `factionDoctrines`);
* `commander` merges key by key; `phases` and `roles` are replaced when present;
* `tactics` merge **by `id`**: an entry with a parent's id merges into it field by field (so
  `{ "id": "hunt_loners", "priority": 95 }` changes only the priority), objects such as `targets`, `ships`,
  `keep` and `score` merge key by key, but **conditions** (`when`, `require`, `abort`, `release`, `escortees`,
  `assaultWhen`) and lists are replaced whole; `"enabled": false` switches an entry off; new ids are added.

Extends chains work (`a` extends `b` extends `default`); circular ones are reported as errors.

## 12. Choosing a doctrine for a battle

At takeover, in this order:
1. **Fleet memory override:** if the enemy campaign fleet (`BattleCreationContext.getOtherFleet()`) has
   `$fc_doctrine` set in its memory, use that doctrine. Lets quest and bounty fleets have a hand-picked brain, from
   rules.csv or code, in this mod or any other. `$fc_disable` = true keeps the vanilla admiral for that fleet.
2. **Settings override:** `settings.json` may map a faction id to a doctrine id (`"factionDoctrines"`).
3. **Best match:** every doctrine with a `select` block whose `when` and `factions` match (facts at takeover;
   phase "main"); the highest `weight` wins, ties broken at random.
4. **Fallback:** `default`.

The chosen doctrine is written to the log and, with announcements on, to the combat message log
("Enemy commander: takes command (Wolfpack)").

## 13. Tactic types (implemented in code)

Each type's extra fields are listed; all common fields from section 7 apply.

**In 0.1:**

| Type | What it does | Default order | Extra fields |
|---|---|---|---|
| `retreat_damaged` | Retreat own ships matching `ships.require`; the retreat is final | `retreat` (or `retreat_direct`) | `keepLast` (default 1: never retreat the last N combat ships), `keepFlagship` (default true) |
| `hunt_isolated` | Pack hunts a lone enemy; strength, leash and abort rules matter most | `eliminate` | `isolationRange`, `requireStrength` (default true); defaults strength 2, min 2, max 4, timeout 45, onEnd rejoin |
| `pounce` | Quick strike on an enemy that just overloaded or started venting; ends when that's over | `eliminate` | `window` (max seconds, default 6); checks every 0.5 s |
| `focus_fire` | Converge on the best-scoring enemy ship | `engage` | defaults strength 1.5, max 4, maxTasks 2 |
| `escort` | Screens for own ships matching `escortees` | `escort_light` | `escortees` (condition on our ships); `ships.max` = escorts per escortee (default 1) |
| `objectives` | Capture (or assault, when `assaultWhen` holds) objectives that pass `targets`; release held ones | `capture` | `assaultWhen`, `release` (condition on an objective we hold; default `threatHere == 0`), `ships.max` direct picks per objective (default 0) |
| `fleet_stance` | One fleet-wide order when `when` holds; Full Assault suspends the other tactics | `full_assault` | `order`: `full_assault` or `full_retreat` |
| `avoid` | Mark enemy ships too strong to fight right now | `avoid` (or `ignore`) | `release` (condition on the marked ship) |
| `battle_line` | A Defend point ahead of the line's centre, given to the line ships so they stay together | `defend` | `offset` (default 600 su toward the enemy), `moveThreshold` (default 1200: move the point only when it is this far off) |

**Planned:** `flank`, `harass`, `carrier_ops` (0.2), `regroup` (0.3).

**Other mods** can add tactic types from Java: a class extending `fleetcommander.tactics.Tactic`, registered with
`FCTactics.register("my_type", MyTactic::new)` in the mod's `onApplicationLoad`. Registering reloads the config, so
doctrines using the new type validate even though Fleet Commander read its files first.

## 14. Examples

The shipped doctrines are the reference examples, and every one is validated by the build:

* [`default.json`](../data/config/fleet_commander/doctrines/default.json): Balanced. Every 0.1 tactic: retreat
  wrecks, pounce, hunt loners after the opening, focus fire, escorts for carriers and the flagship, objectives,
  Full Assault to mop up, Full Retreat when hopeless.
* [`wolfpack.json`](../data/config/fleet_commander/doctrines/wolfpack.json): fast frigate fleets. Hunting first
  (two packs, from the opening), Avoid big defended capitals until winning, no focus fire.
* [`battle_line.json`](../data/config/fleet_commander/doctrines/battle_line.json): slow, armoured fleets. A held
  battle line, focus fire on high-flux targets in front, skirmishers hunt.

A quest fleet with a hand-picked doctrine:

```java
fleet.getMemoryWithoutUpdate().set("$fc_doctrine", "wolfpack");
```

A doctrine of your own: copy `wolfpack.json`, change `id`, add a row to `doctrines.csv` (in your own mod, with the
same path `data/config/fleet_commander/doctrines.csv`, it merges with ours), and run `./build.sh` to validate it.
