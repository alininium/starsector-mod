# Fleet Commander: notes for working on this repo

Starsector 0.98a mod (Java 17). Read DESIGN.md first, then docs/DOCTRINE_FORMAT.md (the doctrine spec; keep it in
sync with the code).

## Build and checks (run after every change)

The game's jars are proprietary: never commit them. Point `STARSECTOR_CORE` at a folder holding
`starfarer.api.jar`, `json.jar`, `log4j-1.2.9.jar`, `lwjgl.jar`, `lwjgl_util.jar` (the user uploads them; the API
source zip `starfarer.api.zip` is the best reference for API questions).

```
STARSECTOR_CORE=<dir> ./build.sh          # compile jars/FleetCommander.jar + validate all config (must say "config OK")
STARSECTOR_CORE=<dir> tools/run_harness.sh # scripted offline battles with faked game interfaces (must say "harness OK")
```

Commit the rebuilt `jars/FleetCommander.jar` with source changes (users install straight from the repo).

## Layout

* `src/fleetcommander/doctrine/`: game-independent config engine (conditions, scores, loader). No `Global` here:
  the offline validator runs it.
* `src/fleetcommander/combat/`: battle facts, the commander loop, `FCOrders` (the only class that talks to the
  task manager), the registered combat plugin.
* `src/fleetcommander/tactics/`: one class per tactic type, registry `FCTactics`.
* `data/config/fleet_commander/`: settings, roles, doctrine index and doctrines.
* `tools/`: validator and harness (not shipped in the jar).

## Rules

* Look up every API call in the API source before using it; no vanilla code gives ship-targeted assignments to the
  AI side, so treat those as unverified until the smoke test (SMOKE_TEST.md) confirms them.
* Never crash a battle: errors are caught in `FCCombatPlugin`, which hands control back to the vanilla admiral.
* No game objects in static fields. Nothing is saved to the save file.
* A starsector-modding skill is useful here: https://github.com/tracesfx/starsector-modding-skill (no licence:
  install it separately, don't copy it into this repo).
