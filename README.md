# Fleet Commander

A Starsector 0.98a mod that gives enemy fleets a commander. Instead of a crowd of individually smart ships, you
fight an opponent that gives orders the way a player does:

* **Wolfpack:** fly away from your fleet and a pack 2x your weight comes to kill you. It gives up when your
  friends arrive, when it is getting hurt, or when it is being lured away.
* **Pounce:** overload near the enemy and the nearest ships jump on you.
* **Focus fire** on the ship under the most flux pressure, with the damage types that work against it right now.
* **Screens** for carriers and the flagship, **objectives** taken with intent, **battle lines** that hold
  together, badly damaged ships **pulled back** before they die, Full Assault to finish you off.

The enemy's orders show in the combat log ("Enemy commander: hunting the isolated ISS Hammer"), so you can read its
plan and counter it.

**Status: 0.1, not yet tested in game.** See [SMOKE_TEST.md](SMOKE_TEST.md).

## Requirements

Starsector 0.98a. No other mods needed. Combat only: it stores nothing in your save, so you can add or remove it
at any time.

## Install

Put this folder in `<Starsector>/mods/` and enable "Fleet Commander" in the launcher.

## Make your own AI patterns

Everything the commander does comes from **doctrine** files in `data/config/fleet_commander/doctrines/`. A
doctrine says which tactics to use, when (battle phases, conditions on the battle), with which of its ships, and
against which targets (filters and scores on ship facts such as flux, armour, isolation, damage match). The
commander picks a doctrine per battle from the fleet's composition, the faction, or a fleet memory key.

* Format: [docs/DOCTRINE_FORMAT.md](docs/DOCTRINE_FORMAT.md)
* Design: [DESIGN.md](DESIGN.md); the tactics research behind it: [docs/TACTICS_RESEARCH.md](docs/TACTICS_RESEARCH.md)
* Global switches (difficulty, command points, announcements): `data/config/fleet_commander/settings.json`

## Build

Needs a JDK 17+ and the game's jars. From the mod folder inside `<game>/mods/`, run `./build.sh` (Linux/macOS/Git
Bash) or `build.bat` (Windows); elsewhere set `STARSECTOR_CORE` to the folder holding `starfarer.api.jar`. The build
compiles `jars/FleetCommander.jar` and validates every config file. `tools/run_harness.sh` runs the commander in
scripted offline battles.
