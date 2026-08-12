# SprintWise routing engine — build plan

Custom **McRAPTOR** search in the Java backend. GraphHopper owns all walk distance and time. Custom GTFS indexing owns all transit rides. OTP is optional baseline/comparison only — not the search core.

Build incrementally. Each phase should compile, run, and pass its own checks before the next phase starts. Do not attempt the full stack in one pass.

---

## Big picture

### What we are building

A multimodal trip planner that finds **Pareto-optimal** journeys under:

- **Elapsed / arrival time** — when you reach the destination
- **Sprint budget** — seconds of sprinting spent so far (capped per trip)
- **Sprint recharge** — progress toward recovering sprint capacity while riding transit
- **Phase flag (`didWeJustWalk`)** — whether the last move was a walk (controls legal next moves)

Walk legs are computed with **GraphHopper** on `data/nyc-metro.osm.pbf`. Ride legs come from **GTFS** schedules (`data/gtfs/mta/`, `data/gtfs/lirr/`). The search graph’s **nodes** are GTFS stops plus synthetic `START` and `END` — not every OSM street corner.

### What we are not building

- A fork of OpenTripPlanner as the search engine
- A fork of otp-react-redux as the long-term UI
- Street-level routing inside McRAPTOR rounds (GraphHopper is called for footpaths; results are cached)

### Reference material (read, do not fork)

Use these for **correctness patterns**, not copy-paste:

| Source | Use for |
|--------|---------|
| [pyraptor](https://github.com/OTP/pyraptor) | RAPTOR round loop, marking, bags, journey backtrace |
| [Cata-Dev/RAPTOR](https://github.com/Cata-Dev/RAPTOR) | McRAPTOR criteria / Pareto dominance structure |
| McRAPTOR paper (Delling et al.) | Multi-criteria bags theory |
| GraphHopper docs | Embedded foot routing, distance cache |
| OTP (running locally) | Baseline itinerary structure + manual comparison |

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                         POST /plan                               │
└────────────────────────────┬────────────────────────────────────┘
                             │
         ┌───────────────────┼───────────────────┐
         ▼                   ▼                   ▼
  ┌─────────────┐    ┌──────────────┐    ┌──────────────┐
  │ OtpClient   │    │ McRaptor     │    │ SprintPolicy │
  │ (optional)  │    │ Engine       │    │ (selection)  │
  └──────┬──────┘    └──────┬───────┘    └──────────────┘
         │                  │
         │           ┌──────┴──────┐
         │           ▼             ▼
         │    ┌────────────┐ ┌────────────┐
         │    │ Footpath   │ │ GtfsIndex  │
         │    │ Service    │ │            │
         │    │ (GraphHopper)│ │ (custom)   │
         │    └──────┬─────┘ └──────┬─────┘
         │           ▼              ▼
         │    data/nyc-metro   data/gtfs/
         │         .osm.pbf     mta/ lirr/
         ▼
  Baseline T₀: OTP itinerary with walks re-priced on GraphHopper
```

### Single source of truth

| Concern | Authority |
|---------|-----------|
| Walk meters and walk time | **GraphHopper** |
| Ride times, trips, transfers (schedule) | **GTFS index** |
| “Standard plan” skeleton | **OTP** (walk legs re-priced on GraphHopper → `T₀`) |
| Optimal sprint-aware plan | **McRAPTOR engine** |

Never compare OTP walk meters to GraphHopper walk meters directly. Reprice OTP walks through GraphHopper before using OTP as a baseline.

---

## Search model

### Nodes

- `START` — user origin (coordinates, or resolved stop/station)
- `END` — user destination
- GTFS **stops** — board/alight points; one node per `stop_id` (or merged cluster later)

Street graph vertices live **inside GraphHopper**, not in the McRAPTOR graph.

### Edges (conceptual)

| Edge type | Source | Notes |
|-----------|--------|-------|
| Access walk | GraphHopper | `START` → stop, within `maxAccessMeters` |
| Egress walk | GraphHopper | stop → `END`, within `maxEgressMeters` |
| Transfer walk | GraphHopper and/or `transfers.txt` + hub overrides | After alighting; indoor hubs may need curated stop-pair times |
| Ride | GTFS | Board trip at stop, alight at later stop on same trip |

### Label (state at a stop)

Each label is a non-dominated point in criteria space:

```
Label {
  arrivalTime:      Instant or seconds since midnight
  sprintUsedSec:    int      // cumulative sprint seconds spent
  sprintRecharge:   float    // 0..1 or seconds recovered toward budget
  didWeJustWalk:    boolean  // phase flag
  // backtrace pointers for journey reconstruction
  parent, tripId, boardStop, ...
}
```

**Dominance:** label A dominates B at the same stop if A is ≤ B on every criterion (arrival, sprintUsed, −recharge as appropriate) and strictly better on at least one. Dominated labels are discarded (McRAPTOR bag).

**`didWeJustWalk` rules (core invariants):**

- After a **ride**, set `didWeJustWalk = false` → next move may be transfer walk or egress walk.
- After a **walk**, set `didWeJustWalk = true` → next move must be a **ride** (no walk→walk without boarding).
- Access walks from `START` seed stops with `didWeJustWalk = true` so the first expansion is transit.
- Transfer walks after alighting set `didWeJustWalk = true` before boarding again.

**Sprint on walks:**

- Each footpath expansion offers two branches where budget allows:
  - **Normal walk:** `time = d / vWalk`, no sprint cost
  - **Sprint:** `time = d / vSprint`, `sprintUsedSec += d/vWalk − d/vSprint` (or equivalent), subject to budget cap
- While **riding** (GTFS in-vehicle edge): advance `sprintRecharge` per config; no walking.

**Sprint selection policy (product layer, not search core):**

After search, compare:

- `bestNoSprint` — best label at `END` with `sprintUsedSec == 0`
- `bestAny` — best label at `END` overall

Recommend sprint plan only if `bestNoSprint.arrival − bestAny.arrival ≥ minSprintPayoff` (configurable, e.g. 5 minutes). Otherwise return the no-sprint plan. Optionally strip per-leg sprints that do not change which train is boarded.

### RAPTOR rounds (sketch)

```
Round 0 (access):
  If start.type == COORDS:
    For each stop within maxAccessMeters of START:
      GH route START → stop (normal + optional sprint branches)
      Add labels to stop bags
  If start.type == STOP | STATION:
    Seed label(s) at stop(s) at T₀, sprintUsed=0, didWeJustWalk=true

Round 1..maxRounds (transit + transfers):
  For each marked stop with valid labels:
    Scan trips serving stop (from GtfsIndex)
    For each boardable trip:
      Extend label along trip to each downstream stop (ride edges)
    After ride extensions, for alighted stops:
      Transfer walks to nearby stops (GH or transfer table)
      Mark stops improved this round

Egress (after rounds or integrated in final round):
  From stops near END, GH walk to END (normal + sprint branches)

Extract Pareto bag at END → apply SprintPolicy → return itinerary
```

**Pruning:** use McRAPTOR’s own best complete incumbent at `END`. Do **not** hard-prune against OTP `T₀` — OTP is one itinerary, not a global optimum.

---

## OTP baseline pipeline (`T₀`)

Used for UI comparison and trust, not search pruning.

1. Call OTP plan API → get itinerary (sequence of walk + ride legs).
2. For each **walk** leg: route same endpoints through GraphHopper at normal walk speed → replace duration/distance.
3. For each **ride** leg: prefer times from **our GTFS index** for the same trip; fall back to OTP duration if trip matching fails.
4. Rebuild timeline from departure time → **`T₀`** (standard plan on our walk model).

Return alongside McRAPTOR result: `{ sprintPlan, baseline T₀, savingsMinutes }`.

---

## Start modes

| Mode | Behavior |
|------|----------|
| `COORDS` | GraphHopper access bubble from origin to nearby stops (outdoor walks) |
| `STOP` | Seed McRAPTOR directly at one stop; skip outdoor access |
| `STATION` | Seed at all stops in a station cluster; use transfer table for within-station moves |

GraphHopper does not model indoor Penn Station corridors well. For major hubs, maintain **curated stop-pair transfer times** (from `transfers.txt` + manual overrides) instead of outdoor GraphHopper detours.

---

## Coordinate snapping

Both GraphHopper and OTP snap lat/lon to their street graphs. This is expected.

- **GraphHopper** snaps every routing query (user coords, stop coords).
- **OTP** snaps at query time and links stops to graph vertices at build time.

Implications:

- Use GraphHopper for **all** walk times in the engine.
- Log GraphHopper snap distance; flag large values (> ~50 m) as suspicious.
- Prefer explicit `STOP` / `STATION` start when the user is already at a platform.
- Precompute or cache **stop link points** (GraphHopper snap of each stop) for consistent footpaths.

---

## Golden queries

Before and after each phase, maintain a fixed set of test queries (origin, destination, depart time, notes). Store in `docs/golden-queries.md` (create when starting Phase 0).

Every phase ends by running these queries and recording:

- Does the engine return a plausible itinerary?
- How does it compare to OTP (qualitatively)?
- Any snap-distance warnings?

---

## Build order

Each phase: **goal → build → done when → explicitly defer**.

---

### Phase 0 — Data and comparison baseline

**Goal:** Reproducible map/transit data and OTP running for manual checks.

**Build:**

- `./scripts/download-data.sh` → `data/nyc-metro.osm.pbf`, GTFS feeds
- `./scripts/run-otp.sh` + `./scripts/start-otp.sh` → OTP at `http://localhost:8080`
- Create `docs/golden-queries.md` with ~10 representative trips (subway-only, transfer, LIRR, near-miss train scenarios)

**Done when:**

- OTP returns sensible plans for all golden queries
- Saved OTP JSON responses exist for regression comparison

**Defer:** custom router, sprint, frontend

---

### Phase 1 — GTFS index (Java)

**Goal:** Load and index transit data; no routing yet.

**Build:**

- Package layout under `backend/src/main/java/.../`:
  - `gtfs/` — loader for `data/gtfs/mta/` (add `lirr/` in Phase 4)
  - `model/` — `Stop`, `Route`, `Trip`, `StopTime`, `ServiceCalendar`
  - `index/GtfsIndex` — structures below
- Index:
  - `stopsById`
  - trips/patterns serving each stop at a given time
  - `stopTimesForTrip`
  - active services for `LocalDate` / `LocalDateTime`
- Debug API:
  - `GET /debug/stop/{id}`
  - `GET /debug/departures?stopId=&at=`

**Done when:**

- Next departures from a known stop match OTP or a GTFS viewer
- Weekday vs weekend service filtering works

**Defer:** GraphHopper, RAPTOR, sprint

**Suggested AI prompt:** *Implement GTFS loader + timetable index for MTA subway only. Add debug REST endpoints and tests for three known stop IDs.*

---

### Phase 2 — Toy RAPTOR (ride-only, fixed stops)

**Goal:** Correct round-based transit search on a tiny stop set.

**Build:**

- Config-limited corridor (e.g. one subway line, 20–50 stops)
- Classic RAPTOR: mark stops → scan trips → extend labels → repeat
- Journey backtrace → `(boardStop, tripId, alightStop, times)`
- `POST /debug/raptor` with `{ fromStopId, toStopId, departAt }`

**Done when:**

- Ride sequence matches OTP on the toy corridor (ignoring walks)
- Round loop structure mirrors pyraptor’s pattern (read pyraptor while implementing)

**Defer:** walks, GraphHopper, Pareto bags, sprint

---

### Phase 3 — GraphHopper footpaths (time-only, door-to-door)

**Goal:** Real OSM walks integrated; still no sprint.

**Build:**

- Embed GraphHopper on `data/nyc-metro.osm.pbf` (foot profile)
- `FootpathService`: `(from, to) → meters, seconds, geometry`; in-memory cache
- Wire into RAPTOR:
  - Round 0: access from `START` coords to stops within `maxAccessMeters`
  - Transfer walks after alight (GraphHopper between stop pairs within `maxTransferMeters`)
  - Egress: stop → `END` coords
- Single walk speed only (no sprint branches yet)

**Done when:**

- Door-to-door time-only trips are plausible on golden queries
- Snap distances logged; outliers flagged

**Defer:** sprint, McRAPTOR bags, OTP reprice

---

### Phase 4 — Full-area McRAPTOR (time-only criteria)

**Goal:** Pareto bags and scale to full NYC feeds.

**Build:**

- Replace single best label per stop with **bags**; criteria: `(arrivalTime, numTransfers)` or `(arrivalTime, totalWalkSec)`
- Full MTA + LIRR in `GtfsIndex`
- `maxRounds` / max transfers cap
- Target prune on best complete arrival at `END`

**Done when:**

- Multiple distinct itineraries on golden queries
- Best time-only plan consistently reasonable vs OTP options 1–3

**Defer:** sprint dimension, OTP baseline service

---

### Phase 5 — OTP baseline with GraphHopper reprice (`T₀`)

**Goal:** Trustworthy standard plan on our walk model.

**Build:**

- `OtpClient` — call local OTP plan API
- `BaselineService` — OTP itinerary → reprice walks on GraphHopper → reprice rides from GTFS → `T₀`
- `POST /plan/baseline`

**Done when:**

- Every golden query with an OTP result has a repriced baseline
- No OTP walk meters used in comparisons

**Defer:** using `T₀` as search prune; sprint

---

### Phase 6 — Sprint dimension in search

**Goal:** Full label tuple and sprint branches on footpaths.

**Build:**

- Extend label: `sprintUsedSec`, `sprintRecharge`, `didWeJustWalk`
- Document dominance rules in code comments matching this file
- Footpath expansion: normal + sprint branches subject to budget
- Recharge on ride edges per config
- Enforce walk→ride→walk invariants via `didWeJustWalk`

**Done when:**

- Near-miss golden queries find earlier trains with sprint that walk-only cannot
- Queries where sprint should not matter: best sprint label ≈ best no-sprint label

**Defer:** sprint payoff policy, per-leg UX

**Suggested AI prompt:** *Add sprint criteria to existing McRAPTOR. Spec: [paste label rules from this doc]. Do not change payoff selection yet.*

---

### Phase 7 — Sprint payoff policy

**Goal:** Only recommend sprint when it meaningfully improves total trip time.

**Build:**

- Track `bestNoSprint` and `bestAny` at `END`
- Config: `minSprintPayoffMinutes`, `maxSprintSecondsPerTrip`
- Selection logic (see Search model above)
- Optional: per-leg counterfactual replay to drop sprints that do not change boarded trips
- `POST /plan` returns `{ plan, baseline, savingsMinutes, sprintSegments[] }`

**Done when:**

- Small savings (e.g. 2 min on a long walk, same train) → no sprint advice
- Catch-earlier-train scenarios → sprint advice with annotated segments

**Defer:** start modes, station transfer overrides

---

### Phase 8 — Start modes and hub transfers

**Goal:** Correct behavior when origin is already a station; sane indoor transfers.

**Build:**

- Request model: `start: { type: COORDS | STOP | STATION, ... }`
- Seed logic per mode (see Start modes above)
- Load `transfers.txt`; YAML overrides for Penn, Atlantic Av, Times Sq, etc.
- Within-station legs prefer transfer table; outdoor legs use GraphHopper

**Done when:**

- Start at LIRR Penn → no spurious outdoor walk to Penn
- Cross-line transfer at complex hubs uses realistic times

---

### Phase 9 — API and minimal frontend

**Goal:** End-to-end demo without forking otp-react-redux.

**Build:**

- `POST /plan` — full request: origin, destination, departAt, walkSpeed, sprint prefs
- Minimal frontend: geocode/pin, results list, sprint badges, baseline comparison
- Optional map polyline from GraphHopper geometries

**Done when:**

- Golden queries runnable from browser
- Side-by-side SprintWise vs baseline `T₀`

---

### Phase 10 — Hardening

**Goal:** Performance, regression safety, observability.

**Build:**

- Precompute/cache GH stop-pair footpaths for eligible transfer pairs
- Expand golden query suite; automated regression tests
- Structured logging: rounds, bag sizes, snap distances, phase timings
- Later: GTFS-RT, user prefs sync, auth

**Done when:**

- CI runs routing regression on golden queries
- P95 latency acceptable for interactive use (define target when measured)

---

## Phase dependency graph

```
Phase 0 (data + OTP + golden queries)
    │
    ▼
Phase 1 (GTFS index)
    │
    ▼
Phase 2 (toy RAPTOR, rides only)
    │
    ▼
Phase 3 (GraphHopper walks, time-only)
    │
    ▼
Phase 4 (full McRAPTOR, time-only)
    │
    ├──────────────────┐
    ▼                  ▼
Phase 5 (OTP→GH T₀)   Phase 6 (sprint in search)
                           │
                           ▼
                      Phase 7 (sprint payoff policy)
                           │
                           ▼
                      Phase 8 (start modes + hub transfers)
                           │
                           ▼
                      Phase 9 (API + frontend)
                           │
                           ▼
                      Phase 10 (hardening)
```

Phases 5 and 6 can run in parallel once Phase 4 is done.

---

## How to work with AI on this

**Do:**

- One phase per session; paste the phase section from this doc as the spec
- Require debug endpoints and tests before closing a phase
- Point AI at pyraptor for round-loop structure in Phases 2–4 only
- Keep golden query IDs stable across phases

**Don’t:**

- Ask for the full router, frontend, and OTP integration in one shot
- Use OTP walk times inside McRAPTOR
- Hard-prune search against OTP `T₀`
- Add sprint before time-only routing matches OTP qualitatively on golden queries

**Example session opener (Phase 3):**

> Implement Phase 3 from `docs/ROUTING-PLAN.md`: GraphHopper foot routing on `data/nyc-metro.osm.pbf`, integrate with existing toy RAPTOR for access, transfer, and egress. Single walk speed. Add cache, snap-distance logging, and tests. No sprint.

---

## Configuration knobs (collect over time)

| Key | Purpose |
|-----|---------|
| `maxAccessMeters` / `maxEgressMeters` | Limit access/egress footpath bubble |
| `maxTransferMeters` | Limit transfer walk candidates |
| `maxRounds` / `maxTransfers` | Cap RAPTOR depth |
| `vWalk` / `vSprint` | User walk speeds (m/s) |
| `maxSprintSecondsPerTrip` | Sprint budget cap |
| `sprintRechargeRate` | Recharge while on transit |
| `minSprintPayoffMinutes` | Minimum total trip savings to recommend sprint |
| `maxSnapDistanceMeters` | Warn/reject bad snaps |

---

## Success criteria (project-level)

- User sets walk speed; engine respects it on all walk legs via GraphHopper.
- Engine finds trips where brief sprint catches a materially earlier train.
- Sprint advice is suppressed when total savings are below `minSprintPayoffMinutes`.
- Baseline `T₀` shows what a standard walk-everywhere plan looks like on the same map model.
- Golden queries pass regression after every phase merge.
