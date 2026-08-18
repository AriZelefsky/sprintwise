# SprintWise routing engine — build plan

Custom **McRAPTOR** search in the Java backend. GraphHopper owns pedestrian pathfinding, distance, geometry, and snapping. SprintWise applies the user's configured walk and sprint speeds to those paths. Custom GTFS indexing owns all transit rides. OTP is optional baseline/comparison only — not the search core.

Build incrementally. Each phase should compile, run, and pass its own checks before the next phase starts. Do not attempt the full stack in one pass.

---

## Big picture

### What we are building

A multimodal trip planner that finds **Pareto-optimal** journeys under:

- **Elapsed / arrival time** — when you reach the destination
- **Sprint budget** — seconds of sprinting spent so far (capped per trip)
- **Phase flag (`didWeJustWalk`)** — whether the last move was a walk (controls legal next moves)

Walk legs are computed with **GraphHopper** on `data/nyc-metro.osm.pbf`. Ride legs come from **GTFS** schedules (`data/gtfs/mta/`, `data/gtfs/lirr/`). The search graph’s **nodes** are GTFS stops plus synthetic `START` and `END` — not every OSM street corner.

For the initial implementation, sprint budget is **journey-wide and non-rechargeable**. Sprint seconds spent on an access, transfer, or egress walk remain spent for the rest of the journey; riding and waiting do not restore them. Recharge/recovery may be considered in a later post-MVP phase after the simpler sprint model is correct and performant.

### Data snapshot policy

The current OSM and GTFS files are a **fixed local snapshot** produced by the one-time data download. Do not rerun `scripts/download-data.sh` during normal development or silently replace these files: golden queries and regression results must continue to use this same snapshot. Deliberate static-data refreshes, GTFS-Realtime, and other live-data work are deferred until much later.

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
  Baseline T₀: OTP itinerary with walks re-routed by GraphHopper
               and timed by SprintWise's speed model
```

### Single source of truth

| Concern | Authority |
|---------|-----------|
| Pedestrian path, meters, geometry, and snapping | **GraphHopper** |
| Walk and sprint duration on that path | **SprintWise speed model** (`vWalk`, `vSprint`) |
| Ride times, trips, transfers (schedule) | **GTFS index** |
| “Standard plan” skeleton | **OTP** (walk legs re-routed by GraphHopper and timed by SprintWise → `T₀`) |
| Optimal sprint-aware plan | **McRAPTOR engine** |

Never compare OTP walk meters or durations to SprintWise results directly. Re-route each OTP walk through GraphHopper, then calculate its normal-walk duration using the same `vWalk` used by SprintWise before treating it as a baseline.

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
  didWeJustWalk:    boolean  // phase flag
  // backtrace pointers for journey reconstruction
  parent, tripId, boardStop, ...
}
```

**Dominance:** compare labels only at the same stop and with the same `didWeJustWalk` state, because labels with different legal next moves are not interchangeable. Label A dominates B if A arrives no later and has used no more sprint, with at least one strict improvement. Dominated labels are discarded (McRAPTOR bag).

**`didWeJustWalk` rules (core invariants):**

- After a **ride**, set `didWeJustWalk = false` → next move may be transfer walk or egress walk.
- After a **walk**, set `didWeJustWalk = true` → next move must be a **ride** (no walk→walk without boarding).
- Access walks from `START` seed stops with `didWeJustWalk = true` so the first expansion is transit.
- Transfer walks after alighting set `didWeJustWalk = true` before boarding again.

**Sprint on walks:**

- Each footpath expansion offers two branches where budget allows:
  - **Normal walk:** `time = d / vWalk`, no sprint cost
  - **Sprint:** `time = d / vSprint`, `sprintUsedSec += d / vSprint`, subject to the journey-wide budget cap
- `sprintUsedSec` measures actual seconds spent sprinting, not seconds saved versus walking.
- While **riding** or waiting: leave `sprintUsedSec` unchanged. There is no recharge in the initial model.

**Sprint selection policy (product layer, not search core):**

After search, compare:

- `bestNoSprint` — best label at `END` with `sprintUsedSec == 0`
- `bestAny` — best label at `END` overall

Recommend sprint plan only if `bestNoSprint.arrival − bestAny.arrival ≥ minSprintPayoff` (configurable, e.g. 5 minutes). Otherwise return the no-sprint plan. Optionally strip per-leg sprints that do not change which train is boarded. Or even strip ones that only save as much time as was saved from moving faster (ie if sprinter took 3 min to traverse distance and walker took 9, and the sprinter only saved those 6 min, then he did not gain much by spinting, and only keep sprints where you A) save the time of traversing the walked distance faster and ALSO get a better or much earlier train (like sprinter took 3 min, then got immediate train, walker took 9 and then had to wait 2 min for train, the spinting was worth 2 min beside the value of the sprint over walk itself)

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
2. For each **walk** leg: route the same endpoints through GraphHopper → replace distance/geometry, then compute duration as `distance / vWalk`.
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

- Use GraphHopper for **all** pedestrian paths, distances, geometry, and snapping in the engine.
- Use SprintWise's speed model for all walk and sprint durations on those paths.
- Log GraphHopper snap distance; flag large values (> ~50 m) as suspicious.
- Prefer explicit `STOP` / `STATION` start when the user is already at a platform.
- Precompute or cache **stop link points** (GraphHopper snap of each stop) for consistent footpaths.

---

## Golden queries

Before and after each phase, maintain the two fixed suites described in `docs/golden-queries.md`:

- **Frozen NYC integration goldens** use the fixed OSM/GTFS snapshot and normalized OTP reference results. They prove realism and catch full-stack regressions without comparing irrelevant raw JSON fields.
- **Synthetic exact-answer goldens** use a tiny GTFS fixture plus mocked footpaths. They force schedule and sprint cases that may not occur reliably in NYC and support exact assertions for trip IDs, arrival seconds, sprint usage, calendar behavior, and label dominance.

Every phase ends by running these queries and recording:

- Does the engine return a plausible itinerary?
- How does it compare to OTP (qualitatively)?
- Any snap-distance warnings?
- Do all synthetic cases enabled by this phase match their exact expected answers?

---

## Build order

Each phase: **goal → build → done when → explicitly defer**.

---

### Phase 0 — Data and comparison baseline

**Goal:** Confirm the fixed map/transit snapshot, align the entire Java toolchain on JDK 25, and get OTP running for manual checks.

**Build:**

- Verify that the existing fixed snapshot contains `data/nyc-metro.osm.pbf` and the MTA/LIRR GTFS feeds; do **not** rerun `scripts/download-data.sh`
- Use JDK 25 for the backend, Maven runtime, and OTP 2.9; `backend/pom.xml` and the repository `.java-version` pin Java 25, and the OTP scripts reject other Java majors
- If `data/graph.obj` already exists, use `./scripts/start-otp.sh` directly; run `./scripts/run-otp.sh` only when the graph is absent or an intentional future data refresh requires rebuilding it
- Start OTP at `http://localhost:8080` for manual baseline checks
- Maintain `docs/golden-queries.md`, the normalized OTP baseline, and the synthetic GTFS/mock-footpath fixtures described there

**Done when:**

- OTP returns sensible plans for all golden queries
- Saved OTP JSON responses exist for regression comparison
- Synthetic fixture integrity tests pass and define exact answers for later routing phases
- The snapshot remains unchanged throughout subsequent phases
- `java --version` and the Java runtime reported by `mvn --version` both report Java 25; the Maven compiler target is 25 and a basic backend Maven test/build succeeds

**Defer:** data refresh/live data, custom router, sprint, frontend

---

### Phase 1 — GTFS index (Java)

**Implementation status:** Stage/Phase 1, including Stage 1E independent MTA
and LIRR loading, is implementation-complete. A closer human review remains
recommended. A computerized Stage 1 review was performed; its remaining
timetable-foundation findings (pickup/drop-off semantics, comprehensive
stop-time validation, and feed-derived maximum-trip-span handling) have been
resolved. Stage 2A was subsequently authorized and implemented without
changing those Stage 1 guarantees.

**Goal:** Load and index transit data; no routing yet.

**Build:**

- Package layout under `backend/src/main/java/.../`:
  - `gtfs/` — shared loader for `data/gtfs/mta/` and `data/gtfs/lirr/`
  - `model/` — `Stop`, `Route`, `Trip`, `StopTime`, `ServiceCalendar`
  - `index/GtfsIndex` — structures below
- Index:
  - `stopsById`
  - trips and departures serving each stop at a given time
  - `stopTimesForTrip`
  - active services for `LocalDate` / `LocalDateTime`
- Stage 1E multi-feed boundary:
  - Load MTA as namespace `mta` and LIRR as namespace `lirr` through the same
    production loader.
  - Keep one complete `GtfsFeed` and one independent `GtfsIndex` per feed in a
    sorted, immutable `TransitFeedCatalog`; never merge the two feeds.
  - Dispatch debug stop, trip, departure, and service inspection to the index
    selected by the explicit feed namespace.
  - Treat GTFS corruption independently: one unavailable feed retains its
    structured diagnostic without preventing another feed from being queried.
    Unknown and disabled feeds are 404; malformed requests are 400.
  - Keep station correspondence, `transfers.txt`, cross-feed transfer edges,
    and combined MTA-LIRR journey planning deferred to later routing phases.
- Preserve each `trip_id` as one complete ordered run. For example, a train
  serving five stops remains one `Trip` with five `StopTime` records; it is not
  split into four adjacent-stop trips.
- Preserve all four GTFS `pickup_type` and `drop_off_type` values in explicit
  SprintWise enums. Blank values mean regular service. Only regular service
  permits ordinary boarding/alighting; phone and driver-coordination values
  remain distinct for future policy decisions.
- Validate stop times before exposing a loaded timetable: every trip has at
  least two stops; sequences are non-negative, unique, and strictly increasing;
  both times are required at the first and last stops; an intermediate stop may
  have both times or neither (no interpolation in Stage 1), but not just one;
  departure cannot precede arrival; and known times cannot move backward.
  GTFS times above `24:00` remain valid integer service-day offsets.
- Derive the service-date search window from the largest arrival/departure
  offset in the feed. For a query, inspect deterministic overlapping service
  dates until an older date's maximum possible departure is before the query,
  calendar-filter every date, and retain the existing noon-minus-twelve DST
  policy. With `D` overlapping candidate service dates and `S` departures at a
  stop, each candidate begins with `O(log S)` binary search followed by only the
  schedule entries needed to collect results (plus skipped inactive entries).
- Debug API:
  - `GET /debug/stop/{id}`
  - `GET /debug/departures?stopId=&at=`
  - `GET /debug/trip/{id}`
  - `GET /debug/services?feedId=&date=`

**Done when:**

- Next departures from a known stop match OTP or a GTFS viewer
- Weekday vs weekend service filtering works

**Stage 1 handoff:** Stage 2A now consumes the full ordered trips and access
semantics to build trip patterns and compact integer/array indexes. RAPTOR
labels, ride scanning, backtrace, GraphHopper, and sprint behavior remain
deferred.

**Suggested AI prompt:** *Implement GTFS loader + timetable index for MTA subway only. Add debug REST endpoints and tests for three known stop IDs.*

---

### Phase 2 — Toy RAPTOR (ride-only, fixed stops)

**Goal:** Correct round-based transit search on a tiny stop set.

**Stage 2A implementation status:** The immutable timetable foundation is
implemented. One composite `RaptorNetwork` is derived from every available
Stage 1 catalog entry; it uses one global compact index space while preserving
authoritative `FeedScopedId` identities and references to each feed's Stage 1
calendar/time context. It does not merge or mutate the source `GtfsFeed`
objects and contains no routing labels, rounds, transfers, or endpoints.

Stage 2A inherits rather than reimplements Stage 1 namespacing, complete-trip
preservation, stop-time validation, pickup/drop-off values, extended times,
calendars, timezones, and failure isolation.

Trip-pattern key:

- `routeId`
- `directionId`
- complete ordered `FeedScopedId` stop sequence
- complete ordered pickup-type sequence
- complete ordered drop-off-type sequence

Route and direction remain in the key to preserve service/directional
boundaries for future policy. Feed separation needs no extra rule: Stage 1
already namespaced every route and stop ID. Pattern grouping shares structure
only; each `trip_id` remains an individually indexed complete schedule.

Trips in a structural pattern are sorted by first departure and stable trip ID.
If a later trip overtakes an earlier one at any comparable known arrival or
departure, deterministic greedy chain partitioning creates multiple
non-overtaking timetable patterns. Both-missing intermediate times remain a
private missing sentinel exposed as empty optional values; Stage 2A performs no
interpolation.

**Build:**

- Stage 2B onward: exercise a small fixed-stop network with at least two useful
  patterns; do not embed a one-line corridor restriction into the reusable core
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
- `FootpathService`: `(from, to) → meters, geometry, snap metadata`; in-memory path cache independent of user speed
- `WalkTimeModel`: `(meters, vWalk) → seconds`
- Wire into RAPTOR:
  - Round 0: access from `START` coords to stops within `maxAccessMeters`
  - Transfer walks after alight (GraphHopper between stop pairs within `maxTransferMeters`)
  - Egress: stop → `END` coords
- Single walk speed only (no sprint branches yet)

**Done when:**

- Door-to-door time-only trips are plausible on golden queries
- Changing `vWalk` changes every access, transfer, and egress duration without changing GraphHopper's ownership of the pedestrian path
- Snap distances logged; outliers flagged

**Defer:** sprint, McRAPTOR bags, OTP reprice

---

### Phase 4 — Full-area McRAPTOR (time-only criteria)

**Goal:** Pareto bags and scale to full NYC feeds.

**Build:**

- Replace single best label per stop with **bags**; criteria: `(arrivalTime, numTransfers)` or `(arrivalTime, totalWalkSec)`
- Consume the composite `RaptorNetwork` derived from the independent MTA and
  LIRR catalog entries; cross-feed station correspondence and transfer edges
  must be designed explicitly rather than merging the source feeds
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
- `BaselineService` — OTP itinerary → re-route walks on GraphHopper and time them with `vWalk` → reprice rides from GTFS → `T₀`
- `POST /plan/baseline`

**Done when:**

- Every golden query with an OTP result has a repriced baseline
- No OTP walk meters or durations are used in comparisons

**Defer:** using `T₀` as search prune; sprint

---

### Phase 6 — Sprint dimension in search

**Goal:** Full label tuple and sprint branches on footpaths.

**Build:**

- Extend label: `sprintUsedSec`, `didWeJustWalk`
- Document dominance rules in code comments matching this file
- Footpath expansion: normal + sprint branches subject to budget
- Preserve `sprintUsedSec` unchanged across ride and wait edges; no recharge
- Enforce walk→ride→walk invariants via `didWeJustWalk`

**Done when:**

- Near-miss golden queries find earlier trains with sprint that walk-only cannot
- Queries where sprint should not matter: best sprint label ≈ best no-sprint label
- A test that rides transit between two walks confirms that previously spent sprint budget is not restored

**Defer:** sprint recharge/recovery, sprint payoff policy, per-leg UX

**Suggested AI prompt:** *Add the non-rechargeable sprint criterion to existing McRAPTOR. Spec: [paste label rules from this doc]. Sprint usage is actual seconds spent sprinting and remains spent across rides and waits. Do not add recovery/recharge or change payoff selection yet.*

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
- Much later: deliberate static-data refreshes, GTFS-RT, sprint recharge/recovery, user prefs sync, auth

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
| `minSprintPayoffMinutes` | Minimum total trip savings to recommend sprint |
| `maxSnapDistanceMeters` | Warn/reject bad snaps |

---

## Success criteria (project-level)

- User sets walk speed; GraphHopper supplies every pedestrian path and SprintWise applies that speed to every walk leg.
- Engine finds trips where brief sprint catches a materially earlier train.
- Sprint budget is measured in actual sprint seconds and never recharges during a journey.
- Sprint advice is suppressed when total savings are below `minSprintPayoffMinutes`.
- Baseline `T₀` shows what a standard walk-everywhere plan looks like on the same map model.
- Golden queries pass regression after every phase merge.
