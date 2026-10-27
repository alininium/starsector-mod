# Tactics research and brainstorm

What experienced players do in Starsector battles, what the vanilla AI does badly, and how a commander can use
each idea through the order API. Sources at the bottom; **(src N)** marks where a claim comes from. Everything
under "Brainstorm" is our own idea and has not been tried in game.

## 1. What the game gives a commander

The player's orders (wiki, 0.98a) and the API enum they most likely map to. The enum names are confirmed in the
API; the mapping from the player-facing name is our reading and gets checked in game (DESIGN.md section 9).

| Player order | What it does (src 1) | API `CombatAssignmentType` |
|---|---|---|
| Eliminate ("just kill it") | Attack the target "regardless of the tactical situation" | probably `INTERCEPT` (**to verify**) |
| Engage | Attack the target and nearby enemies "while avoiding putting itself in serious danger" | `ENGAGE` |
| Harass | One ship harasses the target while staying out of its weapon range; only vs destroyer or bigger | `HARASS` |
| Fighter Strike | Carriers send wings at the target from a safe distance; non-carriers treat it as Eliminate | `STRIKE` |
| Avoid / Ignore | Stay away from / pretend it doesn't exist | `AVOID` / `IGNORE` |
| Capture / Assault | A few ships (matching enemy presence) / a big force regardless of enemies, then Control / Defend | `CAPTURE` / `ASSAULT` |
| Control / Defend | Keep a point (ships as needed) / defend a ship, point or waypoint with a big force | `CONTROL` / `DEFEND` |
| Light / Medium / Heavy Escort | 1 frigate / 2 frigates or a destroyer / 4 frigates, 2 destroyers or a cruiser + light craft | `LIGHT_ESCORT` ... `HEAVY_ESCORT` |
| Rally Task Force / Civilian | A rally point ships move to | `RALLY_TASK_FORCE` / `RALLY_CIVILIAN` |
| Retreat / Direct Retreat | Retreat while defending itself / as fast as possible | `orderRetreat(member, cp, direct)` |
| Search & Destroy | Ship does its own thing and is not used for assignments | `orderSearchAndDestroy(member, cp)` |
| Full Assault / Full Retreat | Everyone attacks; new assignments (except Eliminate/Strike) cancel it / everyone leaves, no more orders | `setFullAssault(true)` / `orderFullRetreat()` |

Command points: 1 CP regenerates every 120 s (faster with Coordinated Maneuvers and the Operations Center hullmod).
For the player, spending a CP opens a short window in which any number of orders can be given (src 1). Whether the
API charges per order or per window for the AI side is unknown; we track it ourselves.

## 2. How good players command (src 4, 5)

A detailed community guide (src 4) describes a routine that maps almost one to one onto the API:

1. **Opening:** give every officer ship a Light Escort (a frigate or destroyer screen: "they will go together
   everywhere, lean on each other... This heavily reduces random combat losses"). Capture the two nearest objectives
   with frigates; Assault the two nearest the enemy, which pulls the whole fleet forward.
2. **Kill the scouts** with Eliminate as they appear; cancel the order if they run back to their fleet.
3. **Release finished objectives** (cancel Control on safe points) so ships stop guarding what needs no guarding;
   downgrade Assault to Capture once the enemy shows where it is.
4. **Let the line form** and save CP.
5. **Targets of opportunity:** soft but dangerous ships (carriers, missile boats such as Gryphons, Sunders, big
   phase ships), over-aggressive enemy officers, **ships that strayed from the pack**, civilian hulls. Use Eliminate
   on them ("risk your lives trying to kill this"), sparingly, and cancel it if the kill isn't happening.
6. **Engage** (the careful version) for valuable targets inside the enemy line.
7. **Avoid** a monster ship (e.g. a Paragon defended on all sides) until late in the battle.
8. **Wrap the flanks:** keep giving Engage/Eliminate on enemies at the edges, so the fleet slowly surrounds the
   enemy and catches ships that withdraw to vent.
9. **Cut loose stragglers:** a ship that got cut off gets Search & Destroy so it stops trying to punch through the
   enemy to reach a marked target.
10. **Full Assault** once the battle is clearly won.

Things the same guide avoids: Defend on own ships (ships park mindlessly next to it) and moving ships onto other own
ships. Forum regulars (src 5) add: the escort leash is strong, so even Reckless captains make good escorts; Defend is
a weak leash that swarms forward when winning and flees far when losing; to rescue a ship under attack, Full Assault
works better than escorts.

## 3. What the vanilla AI does badly (src 2, 3, 6)

* **Big ships chase frigates** to the edge of the map and leave the battle line (the AI Tweaks mod fixes this for
  the player side only; enemy fleets keep vanilla behaviour, src 6).
* **Weapons spread over many targets**: "a ship fights all nearby enemies, but destroys none" (src 6).
* **Flux lock:** at about 80-90% flux a ship backs off and won't re-engage until it is down to about 10-15%, but
  often keeps its shield up, so it can't vent: it is out of the fight and vulnerable (src 2).
* **Fast missiles make ships afraid to vent**, so they stay flux-locked until far away (src 2).
* **A lone ship is a timid ship:** with no friends to share the enemy's fire it plays very cautiously; two equal
  ships fighting alone drag into a CR war (src 2).
* **Personality thresholds:** Timid/Cautious back off at ~80% flux, Steady ~85%, Aggressive/Reckless ~90%;
  Reckless ignores everything but its target (src 2). AI ships act more aggressively when they outnumber the enemy
  nearby and more cautiously when outnumbered (src 2).
* **Large ships get distracted by harmless flankers** and turn to face them, wasting time (src 3).
* **The enemy fights over objectives**, so the player can predict where it will go and bait it with a small force
  (src 3).
* Escorts get jostled out of position; escorts in front of the escortee are exposed (src 2).

## 4. Fight mechanics a commander should reason about (src 3)

* **Damage vs defence:** kinetic does 200% to shields and 50% to armour; HE does 50% to shields and 200% to armour;
  energy 100% to both; fragmentation 25% to both. So: kinetic-heavy ships should go after shielded ships that still
  have flux to spare; HE-heavy ships should go after ships whose shields are down, overloaded, shieldless or out of
  flux.
* **Hard flux** comes only from damage absorbed by shields (and phase); it can't dissipate while the shield is up.
  Pushing a ship into hard flux, then overload, is the core of combat.
* **Overload** leaves a ship unable to do anything but move for a while: the best moment to strike.
* **Phase ships** can't fire while phased, build hard flux while cloaked and slow down to 1/3 speed at 50% hard
  flux; a phase ship with high hard flux has to come out and is easy to catch.
* **Armour** is per section; a ship stripped on one side turns its good side; even stripped, 5% of armour still
  counts.
* **CR:** above 70% is a bonus, below 50% means malfunctions; peak performance time only runs near enemies.
* **Objectives** give small bonuses and let the holder deploy up to 60% of the battle size (vs 40% minimum), so
  they decide reinforcements in big fights. **Clean disengage** needs about 100 DP of enemy ships destroyed or
  driven off at the default battle size.
* **Fleet roles** (src 3): anchor (tough core), skirmisher (fast, objectives, screening flanks), hunter/striker
  (fast or phase, finishes openings), support (carriers, beam boats, escorts).

## 5. Brainstorm: tactics for an AI commander

Each idea is a **tactic type** in the doctrine format (DOCTRINE_FORMAT.md). "Uses" lists the orders.

### 5.1 Hunt isolated ships ("wolfpack")

The user's idea, and the most direct way to punish a common player habit: flying the flagship (or an
aggressive officer) away from the fleet.

* **Detect:** for each visible enemy ship, `support` = weight of its friends within `isolationRange`
  (default 2000 su). Isolation = `1 / (1 + support / ownWeight)`. Also: distance from its own fleet's centre of
  mass, and whether it is moving away from it.
* **Commit:** if a pack of our free ships near it can reach `packRatio` (default 2.0) times its weight, send
  them with **Eliminate**, nearest and fastest first. Prefer hunters and skirmishers; never pull anchors off the
  line unless the target is the player's flagship and the doctrine says so.
* **Leash:** abort if the target's support rises above `abortSupport` (rescuers arrived), if the pack drifts more
  than `leash` su from our main body (we are being lured), if the pack loses more than `abortLoss` of its hull, or
  after `timeout` seconds with no kill. Aborted hunters fall back to the main body (move order), not to the target.
* **Player flagship bonus:** an isolated player flagship scores much higher. This is the "difficulty" knob players
  will feel most.
* Uses: `INTERCEPT` (Eliminate) or `ENGAGE` for a careful pack; move-to-waypoint for the fall-back.

### 5.2 Flux pressure and focus fire on the line

* Pick the enemy line ship with the highest flux (hard flux especially), overloaded or venting, and give it
  **Engage** with ships that are already in range. Converging fire turns high flux into an overload.
* Score includes `damageMatch`: how well the attackers' damage types fit the target right now (kinetic vs
  shields up with flux left; HE vs shields down, overloaded or shieldless).
* Hysteresis: keep the target unless it dies, escapes, or a much better one appears.

### 5.3 Punish overloads ("pounce")

* React fast (short interval) when an enemy overloads or vents near our ships: give the closest 2-3 ships with
  spare flux a short **Eliminate** on it for the overload duration, then release them back to what they were doing.
* Overloads are short, so this needs a quick reaction time and costs CP. A good place for difficulty scaling.

### 5.4 Hunt flux-locked and retreating ships

* A ship that backed off at high flux (flux locked) or is retreating is out of the fight and alone. Fast ships
  chase it with Eliminate; stop at a leash distance so we don't run into the enemy's line.

### 5.5 Screen and escort

* Light Escort on every carrier and every officer ship, Medium on capitals and on our flagship (copying the
  community routine). Pick escorts that fit: a frigate with PD for a carrier; a destroyer for a capital.
* Release escorts when the escortee dies or retreats.

### 5.6 Battle line cohesion

* Fixes "cruisers chase frigates to the map edge" for the enemy side: a **Defend** waypoint slightly ahead of our
  centre of mass, moving with it, holds the anchors and line ships together. Frigates and skirmishers are left out
  so they can still chase.
* Ships that get too far from the line receive a move order back.

### 5.7 Flank and wrap

* Fast ships take waypoints off the enemy's flanks (perpendicular to the line between the fleets, as the Threat AI
  computes its axis), then Engage/Eliminate targets on the enemy's edge. Over time the fleet wraps around the enemy
  and catches ships that pull back to vent.

### 5.8 Harass and tie down

* A fast frigate or destroyer gets **Harass** on an enemy capital or slow cruiser: keeps it busy, makes it turn
  (big ships turn to face flankers, src 3) while the line hits its other side.

### 5.9 Avoid the juggernaut

* An enemy ship whose local threat outweighs what we can bring to it (a Paragon, an Onslaught with a full screen)
  gets **Avoid** until the battle phase changes (we are winning elsewhere, or it is isolated, low on flux or damaged).

### 5.10 Objectives with intent

* Early: Capture the near objectives with skirmishers; Assault the contested middle only when we are stronger.
* Release (cancel) Control on objectives that are safe, so ships go back to fighting.
* Bait (stretch): defend an objective with a small force while the line waits behind it, punishing an enemy that
  commits to it.

### 5.11 Carriers

* Rally carriers behind the line (`RALLY_CARRIER` waypoint). **Fighter Strike** with bombers on slow capitals,
  high-flux targets or ships whose shields are down; fighters on enemy carriers' fighters when we are losing the
  air war.

### 5.12 Phase raiders

* Phase ships go for soft targets behind the enemy line (carriers, missile boats, civilian ships, damaged ships):
  Eliminate with a leash. Hunt enemy phase ships the moment they come out with high hard flux.

### 5.13 Damage control and preservation

* Retreat ships that are badly damaged, or have low CR, before they die (a campaign advantage: they survive).
* **Regroup** when losing locally: rally everyone to a waypoint behind the line, then counterattack as a block.
* **Full Assault** when clearly winning, to mop up; **Full Retreat** when the battle is hopeless (saves ships for
  the campaign).

### 5.14 Counter-doctrines by enemy composition

| Enemy fleet | Counter |
|---|---|
| Carrier-heavy | Wolfpack and phase raiders on carriers; line ships keep PD near each other; avoid chasing fighters |
| Phase-heavy | Tight line (phase ships hunt loners); pounce on phase ships with high hard flux |
| Low-tech brick (slow, heavy armour, weak shields) | HE ships take the lead; harass from range; flank, don't joust head-on |
| High-tech (strong shields, light armour) | Kinetic pressure first, then HE on overloads; pounce has high priority |
| Swarm of frigates | Escorts on everything big; line cohesion so capitals don't chase |
| Player flagship hunter (a strong player ship flying alone) | Wolfpack with a big player-flagship bonus; avoid it when it is supported |

### 5.15 Make it feel fair, not robotic

* **Reaction time** and **think interval** per doctrine, so orders arrive with a human-like delay. The vanilla AI
  has intentional delays to look human (src 6); without them, AI behaviour looks uncanny.
* **Randomness:** a little noise in target scores, and a random pick between doctrines that match equally well,
  so not every fleet behaves the same way.
* **Announcements** of the commander's orders in the combat log, so the player can read and counter the enemy plan.

## Sources

1. Starsector Wiki, "Combat screen" (orders, CP, deployment points, clean disengage), 0.98a:
   https://starsector.wiki.gg/wiki/Combat_screen
2. Starsector Wiki, "AI Behaviour" (personalities, flux thresholds, flux lock, lone ships, escorts):
   https://starsector.wiki.gg/wiki/AI_Behaviour
3. Starsector Wiki, "Combat guide" (damage types, flux, phase, armour, CR, objectives, fleet roles):
   https://starsector.wiki.gg/wiki/Combat_guide
4. r/starsector, "[GUIDE] Commanding fleets in battle":
   https://www.reddit.com/r/starsector/comments/sw5yvo/guide_commanding_fleets_in_battle
5. Fractal Softworks forum, "Let's talk about Escort orders" (0.98a):
   https://fractalsoftworks.com/forum/index.php?topic=31380.0
6. AI Tweaks mod by Halke1986 (fleet cohesion, focus fire, note on intentional AI delays):
   https://github.com/Halke1986/starsector-ai-tweaks
