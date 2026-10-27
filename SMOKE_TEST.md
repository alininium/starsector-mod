# In-game smoke test (version 0.1)

Nothing in this mod has run inside Starsector yet. The build checks it against the real API and runs it against
faked game interfaces, but only the game can show whether the orders do what we expect. This test takes about
20 minutes. After it, send `starsector.log` (in `<game>/starsector-core/`); search it for `Fleet Commander`.

## 0. Install

1. Copy (or clone) this repository into `<game>/mods/` so the folder holds `mod_info.json`, e.g.
   `<game>/mods/starsector-mod/mod_info.json`. The built `jars/FleetCommander.jar` is included.
2. Enable **Fleet Commander** in the launcher and start the game.
3. In `starsector.log`, look for a line like
   `Fleet Commander 0.1.0: loaded doctrines [default, wolfpack, battle_line] (0 errors, 0 warnings)`.
   Any `Fleet Commander config:` error lines mean a doctrine didn't load; send them.

## 1. Order mapping (the most important step)

In the **simulator** (refit screen, "Simulate"), with any fleet:

1. Open the command map (Tab). Give your own ships one of each order, in this order, a few seconds apart:
   **Eliminate** (on an enemy ship), **Engage** (on an enemy ship), **Harass** (on an enemy destroyer or bigger),
   **Fighter Strike** (if you have a carrier), **Avoid**, **Escort (light)** on one of your ships.
2. For each, a blue line appears in the combat log: `[Fleet Commander] your order = <TYPE> on <ship>`.
3. Write down which TYPE each order produced. We expect Eliminate = `INTERCEPT`. If it is something else, add it
   to `data/config/fleet_commander/settings.json`: `"orderMapping": { "eliminate": "<TYPE>" }` (no rebuild
   needed) and tell me.

## 2. Takeover

Still in the simulator, against a few enemy ships:

* Expected: a red-ish line `Enemy commander: takes command (Balanced)` (or another doctrine name) right after the
  enemy deploys, and in the log `Fleet Commander: taking command of side 1 with doctrine ...`, followed by the
  enemy's CP.
* If the log says the enemy has **no command points**, set `"useCommandPoints": false` in settings.json for the
  rest of the test and tell me.

## 3. The wolfpack

Fight at least 2-3 enemy frigates or destroyers. After about 40 seconds (the opening phase), fly your ship well
away (3000+ su) from your other ships.

* Expected: `Enemy commander: hunting the isolated <your ship>`, and 2-4 enemy ships come straight for you.
* Then fly back to your fleet: the hunters should give up within ~10 seconds and return to their fleet.

## 4. Pounce and focus fire

* Pounce reacts to **your** side's ships overloading or venting. Let your ship overload (or vent) within about
  1500 su of enemy ships: the nearest 1-3 enemies with spare flux should turn on you at once, then go back to
  what they were doing once the overload ends. There is no announcement for this (it would be too frequent);
  with `"debugLog": true` the log shows `pounce: eliminate on ...`.
* Focus fire: several enemy ships should converge on the same target of yours, usually the one with the highest
  flux or most damage, with `Enemy commander: focus fire on <ship>`.

## 5. Retreat

Damage an enemy cruiser or destroyer below 30% hull.

* Expected: `Enemy commander: <ship> is pulling back`, and the ship heads for its edge of the map.

## 6. Escort

Fight an enemy fleet with a carrier (e.g. a Heron or Condor) in the simulator.

* Expected: one enemy frigate/destroyer stays close to the carrier.

## 7. A campaign battle

Fight a real fleet larger than the deployment limit (a big pirate or Hegemony fleet).

* Expected: the enemy still sends **reinforcements** as its first ships die. If no reinforcements arrive, that is
  the "does silencing the admiral stop deployment" risk from DESIGN.md: tell me, the fix is known.
* Pursuit or escape battles: no "Enemy commander" lines (it stays out).

## 8. Things to note

* Does it feel harder? Fun or annoying (too many retreats, hunters too aggressive)? Everything is tunable in the
  doctrine files.
* Any `Fleet Commander: error, handing command back` line in the log: the commander hit a bug and the vanilla
  admiral took over for that battle. Send the stack trace below it.
* For detailed decisions, set `"debugLog": true` in settings.json; every order and task change is then logged.
