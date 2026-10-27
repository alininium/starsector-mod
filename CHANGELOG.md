# Changelog

## 0.1.0 (not yet tested in game)

* Enemy fleet commander: takes over from the vanilla admiral through the same task manager the player uses (as
  vanilla's Threat and Dweller AIs do); hands control back on any error.
* Doctrine files (format 1): phases, roles, conditions, scores, `extends`, merged CSV index, fleet memory override
  (`$fc_doctrine`, `$fc_disable`), faction mapping.
* Tactics: retreat_damaged, hunt_isolated, pounce, focus_fire, escort, objectives, fleet_stance, avoid,
  battle_line.
* Doctrines: default (Balanced), wolfpack, battle_line.
* Command points: fair mode with command windows, or free orders; bonus CP.
* Order announcements; player-order logging to verify the order mapping; `orderMapping` override.
