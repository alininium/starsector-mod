# Fleet Commander — design

**One idea:** enemy fleets get a commander who reads the battle and gives orders to their ships the way a good
player does (hunt down stragglers, pounce on overloads, screen carriers, hold the line, pull back wrecks), so
battles feel like fighting a thinking opponent instead of a crowd of individually smart ships.

**How it stays flexible:** the commander's behaviour lives in **doctrine files**, not in code. The code provides a
set of tactic building blocks; a doctrine picks and tunes them, says when to use each one, with which ships,
against which targets. Different fleet compositions (wolfpacks, battle lines, carrier groups, phase raiders), and
different enemies (armoured bricks, shield-tanking high-tech, carrier spam), get different doctrines.

Companion documents:
* [docs/DOCTRINE_FORMAT.md](docs/DOCTRINE_FORMAT.md): the doctrine file format (the spec).
* [docs/TACTICS_RESEARCH.md](docs/TACTICS_RESEARCH.md): how players fight, what the AI does badly, and the
  tactic brainstorm the tactic types come from, with sources.

Basics:
* Game: Starsector 0.98a (Java 17). Compiled against `starfarer.api.jar` from the user's install.
* Mod id `fleet_commander`, id prefix `fc_`, Java package `fleetcommander`, jar `jars/FleetCommander.jar`.
* Dependencies: none. Optional integrations (e.g. LunaLib settings) only later, always guarded.
* Save compatibility: combat-only, stores nothing in the save; can be added to or removed from a running game.

## 1. How it plugs into the game (verified against the API source)

Vanilla already ships two custom fleet brains: the Threat (`ThreatCombatStrategyAI`) and the Dwellers
(`DwellerCombatStrategyAI`). Both work the same way, and so will we:

1. An every-frame combat plugin, registered in `data/config/settings.json` under `"plugins"`, runs in every battle.
2. Once the enemy side (owner 1) has ships deployed, it **silences the built-in admiral**:
   `taskManager.clearTasks()`, then `fleetManager.getAdmiralAI().setNoOrders(true)`.
3. From then on it gives orders through the **same task manager the player uses**,
   `engine.getFleetManager(1).getTaskManager(false)`:
   * `createAssignment(type, target, useCommandPoint)`, with a `BattleObjectiveAPI`, a `DeployedFleetMemberAPI`
     (a ship) or a waypoint from `createWaypoint2(location, false)` as target;
   * `giveAssignment(member, assignment, useCommandPoint)` to put a specific ship on it;
   * `orderRetreat`, `orderSearchAndDestroy`, `removeAssignment`, `setAssignmentWeight`, `reassign`,
     `clearEmptyWaypoints`, `setFullAssault`, `orderFullRetreat`.
4. Ships with no assignment fall back to the default search-and-destroy, so the commander only issues the orders
   that matter. Ship-level AI (aiming, shields, flux, dodging) is untouched.

Vanilla tricks we copy: move a single ship with a waypoint + `RALLY_CIVILIAN` assignment; escorts as
`LIGHT_ESCORT` with assignment weight 0 then `giveAssignment`; tidy up with `reassign()`, removing empty
assignments, `clearEmptyWaypoints()`.

## 2. When the commander takes over, and when it stays out

Takes over the enemy side (owner 1) in campaign battles, and (configurable, on by default for testing) in the
simulator and missions.

Stays out, leaving vanilla in charge, when:
* either side's goal is `ESCAPE` (pursuits and escapes);
* the enemy fleet is Threat or Dweller (`threat_hullmod` / `dweller_hullmod`), or their strategy plugins run;
* no doctrine could be loaded, or the mod is switched off.

Never touches the player's side, including allied fleets fighting with the player. Compatible by design with AI
Tweaks, whose fleet cohesion only touches the player's fleet.

If anything throws, the commander logs it once, hands control back (`setNoOrders(false)`), and stops for the rest
of the battle. A bug must never crash a battle.

## 3. Architecture: the doctrine engine

```
FCCombatPlugin  -- decides takeover, picks the doctrine, owns the commander, catches errors
  FleetCommander  -- every think interval:
    BattlePicture   facts about the battle, both fleets, every visible ship   (DOCTRINE_FORMAT.md section 4)
    phase           first doctrine phase whose condition holds                 (section 5)
    tactics         in priority order: find targets (filter + score),
                    claim free ships, issue / keep / cancel orders             (sections 7-10)
    FCOrders        the only class that talks to the task manager:
                    order-name mapping, CP budget, reaction delay, announcements, tidy-up
```

Key rules:
* **Data, not code:** doctrines are JSON; a CSV index merged across mods lets other mods add doctrines; a fleet
  memory key (`$fc_doctrine`) lets quest fleets use a hand-picked one. Inheritance (`extends`) keeps variants small.
* **Ships are claimed:** a ship belongs to at most one tactic; higher priority claims first.
* **Hysteresis everywhere:** minimum task time, timeouts, switch margins and abort conditions, so the commander
  doesn't flip-flop or waste command points.
* **Fair play:** only enemy ships we can see count; orders arrive after a reaction delay; a little noise in
  target scores; announcements so the player can read the enemy's plan.
* **Extensible:** tactic types are Java classes behind a registry, so new types (ours or other mods') plug in by
  name.

## 4. Tactic types

From the research (docs/TACTICS_RESEARCH.md section 5). Details and fields in DOCTRINE_FORMAT.md section 13.

| Type | In short | Version |
|---|---|---|
| `retreat_damaged` | Pull back badly damaged or low-CR ships before they die | 0.1 |
| `hunt_isolated` | **Wolfpack**: a pack at least 2x the weight of a lone enemy ship hunts it with Eliminate; leash, timeout and "rescuers arrived" abort; extra weight on an isolated player flagship | 0.1 |
| `pounce` | Quick Eliminate on an enemy that just overloaded or started venting, then release | 0.1 |
| `focus_fire` | Converge on the enemy line ship under the most flux pressure, matched to our damage types | 0.1 |
| `escort` | Screens for carriers, officer ships and the flagship | 0.1 |
| `objectives` | Capture near points, assault contested ones when stronger, release safe ones | 0.1 |
| `fleet_stance` | Full Assault to mop up when clearly winning; Full Retreat when hopeless | 0.1 |
| `battle_line` | A moving Defend point that keeps capitals and cruisers from chasing frigates | 0.2 |
| `flank` | Fast ships wrap around the enemy's flanks | 0.2 |
| `harass` | Fast ships tie down enemy capitals | 0.2 |
| `avoid` | Stay away from a juggernaut until the situation changes | 0.2 |
| `carrier_ops` | Carriers rally behind the line; fighter strikes on slow or exposed ships | 0.2 |
| `regroup` | Fall back together when losing, then counterattack | 0.3 |

## 5. Doctrines shipped with the mod

| Doctrine | Picked for | Character |
|---|---|---|
| `default` (Balanced) | Anything that matches nothing else | Every 0.1 tactic, moderate settings |
| `wolfpack` | Mostly fast frigates/destroyers; pirates, Luddic Path | Hunting has top priority; avoid big defended ships; no jousting |
| `battle_line` | Slow, heavily armoured fleets | Hold the line, pressure the highest-flux target in front, skirmishers hunt |
| `carrier_group` (0.2) | 30%+ of weight on carriers | Carriers rally back, escorts on every carrier, fighter strikes |
| `phase_raiders` (0.2) | 30%+ phase ships | Phase ships hunt soft targets behind the line |
| `anti_carrier`, `anti_phase` (0.2) | Selected by the *enemy's* composition | Counter-doctrines from TACTICS_RESEARCH.md 5.14 |

## 6. Global settings: `data/config/fleet_commander/settings.json`

```jsonc
{
    "enabled": true,
    "enableInSimulator": true,
    "enableInMissions": true,
    "difficulty": 1.0,            // scales think interval and reaction time (2.0 = twice as fast), and pack sizes
    "useCommandPoints": null,     // null = use each doctrine's setting; true / false forces it everywhere
    "bonusCommandPoints": 0,      // extra CP for the enemy side
    "announceOrders": true,       // "Enemy commander: hunting the isolated ISS Hammer"
    "factionDoctrines": { },      // e.g. { "hegemony": "battle_line" }
    "debugLog": false,            // log every decision pass (for tuning)
    "logPlayerOrders": true       // log the assignment types the player gives (verifies the order mapping)
}
```

## 7. Command points

Vanilla's Threat and Dweller brains give themselves effectively unlimited CP and pass `useCommandPoint = false`.
We offer both: `useCommandPoints: true` ("fair", default) charges the enemy fleet's own CP pool like the player's,
after we check `getCommandPointsLeft()`; a `cpReserve` keeps points for urgent tactics (retreats, pounces).
`false` ("hard") makes orders free. `bonusCommandPoints` adds CP via
`getCommandPointsStat().modifyFlat("fc_bonus", n)`.

## 8. Code layout

| Class | Job |
|---|---|
| `fleetcommander.FCModPlugin` | Loads settings, roles and doctrines at game start; validates them and logs problems |
| `fleetcommander.FCSettings` | Global settings |
| `fleetcommander.doctrine.Doctrine`, `DoctrineLoader` | Parsed doctrine, CSV index, `extends` merging, validation |
| `fleetcommander.doctrine.Condition`, `Score` | Condition tree and weighted score, evaluated against facts |
| `fleetcommander.combat.FCCombatPlugin` | Registered combat plugin: takeover, doctrine choice, error safety |
| `fleetcommander.combat.FleetCommander` | Think loop: facts, phase, tactics, claims |
| `fleetcommander.combat.BattlePicture`, `ShipFacts` | All facts, computed once per pass |
| `fleetcommander.combat.FCOrders` | Order mapping, CP, reaction delay, announcements, tidy-up |
| `fleetcommander.tactics.*` | One class per tactic type, plus the registry `FCTactics` |

Performance: facts and decisions run every couple of seconds, not every frame; per-frame work is a timer tick and
the pounce watcher (overload/vent flags only). No game objects in static fields.

## 9. Risks and what only an in-game test can answer

| Risk | Why it matters | How we find out / fallback |
|---|---|---|
| Which enum is the player's **Eliminate**? Probably `INTERCEPT`, but nothing in the API says so | Hunting and pounce depend on it | `logPlayerOrders`: the user gives Eliminate, Engage, Harass, Fighter Strike in the simulator, and the log prints the enum type of each. One test settles the mapping table |
| Does `setNoOrders(true)` also stop the admiral **deploying reinforcements**? | Enemy could stop deploying after the first wave | Smoke test with a fleet bigger than the deployment cap. Fallback: keep the admiral, only add orders on top |
| Do ship-targeted assignments (ENGAGE, INTERCEPT) work for the **AI side**? No vanilla code does this | Core of the mod | Smoke test with announcements. Fallback: waypoints that follow the target |
| What does `createAssignment(..., true)` do when the AI side has **no CP**? | Silent failure or free orders | We check `getCommandPointsLeft()` first |
| Doctrines are **too strong or tedious** (constant retreats, packs everywhere) | Fun | Everything is data; tune after playtests; `difficulty` knob |
| **Stations** and modules can't take orders | Errors | Never claim members with `canBeGivenOrders()` false |
| Another mod also replaces the **enemy admiral** | Two brains fighting | Detect and stay out (checked when we meet one) |

## 10. Scope

* **0.1 (MUST):** takeover/hand-back, doctrine loader (format v1, CSV index, `extends`, validation), facts,
  conditions, scores, claims, orders, the seven 0.1 tactic types, doctrines `default`, `wolfpack`, `battle_line`,
  settings, announcements, `logPlayerOrders`.
* **0.2 (SHOULD):** the 0.2 tactic types; doctrines `carrier_group`, `phase_raiders`, counter-doctrines.
* **0.3+ (STRETCH):** `regroup`, bait tactics, LunaLib in-game settings, faction flavour, commander skill
  scaling (officers with Coordinated Maneuvers think faster).

## 11. Testing

No tool can play the game. Every build is checked statically (compiles against the real API; every shipped
doctrine loads and validates; a test harness evaluates conditions and scores on made-up facts). Then the user runs
the smoke test:

1. **Simulator, order mapping:** give your own ships Eliminate, Engage, Harass and Fighter Strike once each; send
   `starsector.log` (the `logPlayerOrders` lines settle the enum mapping).
2. **Simulator, wolfpack:** fly your flagship away from your fleet. The log/announcements should show a hunt, and
   2-4 enemy ships should converge on you; they should give up when you return to your fleet.
3. **Pounce:** overload an enemy ship near others; nearby enemies should jump on it.
4. **Retreat:** damage an enemy cruiser below 30% hull; it should retreat.
5. **Campaign battle** bigger than the deployment cap: reinforcements should still arrive.
6. **Pursuit battle:** the commander should stay out.
7. Send `starsector.log` after each session (search for `fleetcommander`).
