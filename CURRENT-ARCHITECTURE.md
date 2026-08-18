# SprintWise current architecture (Stage 2 complete: transit-only exact-stop routing)

(Reminder to hit command shift v to view in markdown view)

This document explains the repository as it exists after Stage 2D2. It is meant
to let my incoming partner @ Zach Rosenberg gain a thorough and deep understanding of the project as it stands.

Last checked against the working tree: 2026-08-18.

## Transit vocabulary: stop, route, trip, and stop time

These words have specific GTFS meanings. Understanding them first makes the
rest of the architecture much easier.

### Stop: a place where transit can be served

A **stop** is a physical transit location identified by `stop_id`. Depending on
the feed, it can represent a station, platform, entrance, or boarding area. In
SprintWise's planned routing graph, a boardable GTFS stop is a node at which a
search label can exist.

For example, the northbound and southbound platforms of one subway station may
have different stop IDs and a shared parent-station ID. The current Stage 1
model preserves both the individual stop and its optional parent relationship;
it does not automatically merge every platform into one node.

### Route: the named transit service or line

A **route** is the public-facing service grouping identified by `route_id`, such
as a subway line. It supplies names and a transit type. A route is **not** one
particular train departure, and it is not necessarily one exact sequence of
stops.

One route can have:

- Thousands of scheduled train runs.
- Both directions of travel.
- Express and local variants.
- Short-turn trips that end before other trips on the same line.
- Different stop sequences at different times.

Therefore, the future routing engine cannot safely say "ride the Route object
down its stops." The GTFS `Route` does not own one authoritative ordered stop
list or one timetable.

### Trip: one scheduled vehicle run

A **trip** is one scheduled-run definition identified by `trip_id`. It belongs
to one route and references one service ID. Its ordered stop times say exactly
where that run goes and when. It is not yet tied to one concrete calendar date:
the service calendar can instantiate the same trip definition on many active
dates.

For example, "the downtown 1 run leaving its first stop at 08:02 on weekday
service" is represented by a trip definition and may occur on every applicable
weekday. Choosing Thursday, August 13 turns it into a concrete dated run.
Another 1 train ten minutes later is another trip, even if it visits the same
stops. An express variant or short-turn train is also a different trip.

A trip does not have to start at the stop where the passenger finds it. A trip
can begin elsewhere, pass through the passenger's current stop, and continue to
later stops. What matters for boarding is that:

- The trip serves the current stop.
- Its service is active on the relevant service date.
- Its departure at that stop is late enough for the passenger to catch.
- Pickup is permitted at that stop.

### Stop time: a trip's visit to one stop

A **stop time** joins a trip to a stop. It contains:

- The stop's position in that trip (`stop_sequence`).
- Arrival and departure seconds.
- Pickup and drop-off rules.

If one train visits five stops, Stage 1 stores one `Trip` and five ordered
`StopTime` records. The third stop time means "this trip's visit to its third
stop," not a separate trip or a generic property of the route.

### Service: the dates on which a trip exists

A trip references a **service ID**. `calendar.txt` and `calendar_dates.txt`
decide which civil/service dates that ID is active. The same trip timetable can
therefore be active on many weekdays and inactive on weekends or exception
dates.

### How the current transit-only routing round uses these concepts

Stage 2C now performs this flow:

```text
Current search label at Stop B
          |
          v
Find trips/patterns that serve Stop B
(including trips that began earlier and merely pass through B)
          |
          v
Choose a trip that is active, catchable, and permits pickup at B
          |
          v
Scan that same trip's ordered StopTimes after B
          |
          +------> Stop C: possible arrival
          +------> Stop D: possible arrival
          `------> Stop E: possible arrival
```

The passenger boards once at B and can remain on that trip across C and D to E.
The algorithm must not treat B-to-C, C-to-D, and D-to-E as three new boardings.
It may create an arrival at a downstream stop only where that stop time permits
drop-off.

The current Stage 1 index already preserves the two key ingredients:

- `tripsByStop`: which complete trips serve or pass through a stop.
- `stopTimesByTrip`: the complete ordered stop sequence for each trip.

Stage 2A now derives a compact **trip-pattern** index from those verified
structures. A trip pattern groups trips that share the same route, direction,
exact ordered `FeedScopedId` stop sequence, and pickup/drop-off sequence. The
individual trips remain separate schedules; the pattern only shares their
common structure. That derived pattern is different from the feed's `Route`:
one route may require several patterns because its trips have different
directions, stop sequences, or access rules.

Routes still matter, but mostly as stable grouping and descriptive information:

- Every trip must reference a valid route.
- Results can say which line/service the passenger rides.
- Route type can distinguish subway, rail, bus, and other modes.
- Future policies may filter or present routes differently.
- Route identity helps describe and reconstruct a journey.

The scheduled movement itself comes from a **Trip plus its ordered StopTimes**,
not from the Route record alone.

## The most important fact

SprintWise can now find a time-only transit path between exact GTFS stops and
reconstruct it as ordered transit legs. `POST /debug/raptor` exposes that path
for inspection. It still does **not** include walking or physical transfers.

The completed Stage 1 and Stages 2A through 2D2 backend can:

- Read independently configured MTA and LIRR static GTFS feeds.
- Convert parser-owned objects into immutable SprintWise-owned records.
- Validate stops, routes, trips, stop times, calendars, references, timezones,
  and pickup/drop-off values.
- Build an immutable in-memory timetable index.
- Derive one immutable composite `RaptorNetwork` containing compact MTA and
  LIRR stop/trip indexes and trip patterns.
- Given one pattern, one boarding stop, and one explicit `Instant`, select
  active catchable trip occurrences and return the earliest legal ride to each
  downstream pattern position.
- Starting from one exact feed-scoped stop, run deterministic marked-stop
  rounds and find the earliest destination arrival using at most a positive
  number of boarded trips.
- Follow the winning predecessor chain and expose one immutable transit leg per
  boarded trip, in chronological order, while retaining the underlying search
  result for diagnostics.
- Accept namespaced exact-stop routing requests at `POST /debug/raptor` with an
  explicit offset timestamp and return a stable, user-readable JSON journey.
- Change trips at the exact same stop with the temporary Stage 2C zero-slack
  policy; a departure equal to the prior arrival is catchable.
- Resolve active services and departures, including `24:xx`, `49:xx`, and DST.
- Expose read-only debug HTTP endpoints for inspecting that data.

It cannot yet:

- Transfer between different platform/parent/nearby stops.
- Connect an MTA stop to an LIRR stop or plan a journey across feeds.
- Use `transfers.txt`, shapes, OSM, GraphHopper, or the OTP graph in its own
  backend search.
- Perform access, transfer, or egress walking.
- Apply walking speed, sprinting, dominance, or recharge rules.
- Consume GTFS-Realtime.
- Persist the timetable in a database or reload it while the process runs.

The root `README.md` describes parts of the intended finished product, including
a custom routing algorithm and live data. Those statements are aspirational;
they do not describe the current Stage 1/Stage 2 implementation. For the planned build
sequence, use `docs/ROUTING-PLAN.md`.

## Architecture at a glance

```text
data/gtfs/mta/                         data/gtfs/lirr/
      |                                      |
      +----------- OneBusAwayGtfsLoader -----+
                          |
                 parse with OneBusAway
                          |
                OneBusAwayGtfsMapper
                 map and namespace IDs
                          |
                  GtfsFeedValidator
               parser-neutral invariants
                          |
              +-----------+-----------+
              |                       |
       GtfsFeed(mta)             GtfsFeed(lirr)
       GtfsIndex(mta)            GtfsIndex(lirr)
              |                       |
              +-----------+-----------+
                          |
               TransitFeedCatalog
          sorted entries keyed by mta/lirr
       each owns its feed/index or load failure
                 /                    \
                v                      v
       Composite RaptorNetwork    Stage 1 debug lookups
       compact stops, trips,      namespace selects one index
       patterns, feed contexts            |
                |                  JSON / Problem Details
                v
       RaptorRoutingService <----- POST /debug/raptor
                |
       RaptorPatternScanner
       one pattern + board stop
                |
       immutable RaptorRide(s)
                |
                v
       RaptorRoundRouter
       marked exact-stop rounds
                |
       RaptorSearchResult
       labels + predecessor chain
                |
                v
       RaptorJourneyReconstructor
       validated chronological backtrace
                |
                v
       RaptorJourney
       immutable transit legs
                |
       stable JSON / Problem Details
```

There is no database in this flow. The final state of successfully loaded data
is Java objects in the backend JVM's heap. They live until the process exits.
A restart parses and indexes the static files again.

## Startup lifecycle

1. `SprintWiseApplication.main` starts Spring Boot and scans packages below
   `com.sprintwise`.
2. `application.yml` supplies defaults:
   - Backend port: `8081`.
   - Enabled feed `mta` at `../data/gtfs/mta`.
   - Enabled feed `lirr` at `../data/gtfs/lirr`.
3. Environment variables may override those defaults:
   - `SERVER_PORT`
   - `SPRINTWISE_MTA_GTFS_PATH`
   - `SPRINTWISE_MTA_GTFS_ENABLED`
   - `SPRINTWISE_LIRR_GTFS_PATH`
   - `SPRINTWISE_LIRR_GTFS_ENABLED`
4. Spring binds the GTFS values into `GtfsProperties`.
5. `TransitConfiguration` constructs a `OneBusAwayGtfsLoader` behind the
   SprintWise `GtfsLoader` interface.
6. Spring constructs one singleton `TransitFeedCatalog`. Configuration is a
   list so blank and duplicate feed IDs can be rejected before loading.
7. For every enabled feed, the catalog normalizes its path, loads it once, and
   immediately builds that feed's own `GtfsIndex`.
8. A successful entry retains both its immutable `GtfsFeed` and `GtfsIndex`.
   Requests reuse them; no request reparses GTFS.
9. Spring constructs one singleton composite `RaptorNetwork` from every
   available catalog entry. It retains references to the immutable Stage 1
   feed/index contexts and derives compact global stop/trip IDs, patterns, and
   stop-to-pattern arrays. Failed entries are listed as unavailable and remain
   owned by the catalog with their original diagnostic.
10. Spring constructs one `RaptorRoutingService` around that same singleton
    network, plus one router and journey reconstructor. Requests reuse all of
    them; no request rebuilds the network or reparses a feed.
11. A structured `GtfsLoadException` affects only that feed. Its entry retains
   one `FeedUnavailableException`, so requests for it return HTTP 503 while
   other entries remain usable. Unexpected programming/configuration failures
   still abort startup instead of being mislabeled as bad feed data.
12. Disabled feeds do not enter the catalog and have the same 404 contract as
    unknown feed namespaces.

This is eager startup loading, but it is deliberately failure-tolerant at the
web-application boundary.

## GTFS datapoint lifecycle

### The boundary rule

OneBusAway is an input parser, not SprintWise's domain model. Its classes are
used only in `com.sprintwise.gtfs.onebusaway`. `OneBusAwayGtfsLoader`
orchestrates the reader, `OneBusAwayGtfsMapper` copies retained values into
SprintWise records, and `OneBusAwayImportDiagnostics` translates parser and
adapter failures. No parser-owned value leaves that package.

That gives the rest of the application these properties:

- Routing code will not depend on OneBusAway internals.
- Application IDs have explicit feed namespaces.
- Collection ownership and immutability are controlled by SprintWise.
- Parser replacement would affect the adapter rather than the entire system.

### What each GTFS file becomes

| GTFS input | Stage 1 use | SprintWise final representation |
|---|---|---|
| `agency.txt` | Requires exactly one nonblank valid timezone | `GtfsFeed.agencyZoneId`; most other agency fields are not retained |
| `stops.txt` | Stop identity, name, coordinates, location type, parent station | `Stop`, indexed by namespaced ID |
| `routes.txt` | Route identity, names, GTFS route type | `Route`, indexed by namespaced ID |
| `trips.txt` | Trip identity and route/service relationships | `Trip`, indexed by namespaced ID |
| `stop_times.txt` | Ordered stops, service-day times, pickup/drop-off rules | `StopTime`, grouped by trip; timed rows also produce `ScheduledDeparture` records by stop |
| `calendar.txt` | Recurring date range and weekdays | `ServiceCalendar`, then a lookup map in `ServiceCalendarResolver` |
| `calendar_dates.txt` | Per-date service additions/removals | `ServiceCalendarDate`, then date-keyed override maps |
| `feed_info.txt` | Parsed by OneBusAway if present | Not copied into the SprintWise model |
| `shapes.txt` | Present in frozen feeds | Not copied or used by Stage 1 |
| `transfers.txt` | Present in frozen feeds | Not copied or used by Stage 1 |

Other optional GTFS tables that OneBusAway knows about are likewise not part of
the Stage 1 domain unless the adapter explicitly maps them.

### Field-level mapping

#### Stop

```text
GTFS stop_id          -> FeedScopedId(feedId, stop_id)
GTFS stop_name        -> Stop.name
GTFS stop_lat         -> Stop.latitude
GTFS stop_lon         -> Stop.longitude
GTFS location_type    -> Stop.locationType (currently retained as an integer)
GTFS parent_station   -> optional namespaced Stop.parentStationId
```

For example, raw MTA stop `101` becomes `FeedScopedId("mta", "101")`, printed
as `mta:101`. If LIRR also had an ID `101`, `lirr:101` would be a different ID.

#### Route

```text
route_id              -> namespaced Route.id
route_short_name      -> Route.shortName
route_long_name       -> Route.longName
route_type            -> Route.type (currently retained as an integer)
```

#### Trip

```text
trip_id               -> namespaced Trip.id
route_id              -> namespaced Trip.routeId
service_id            -> namespaced Trip.serviceId
trip_headsign          -> Trip.headsign
direction_id           -> Trip.directionId
```

One GTFS `trip_id` remains one complete train run. A train serving five stops is
one `Trip` plus five ordered `StopTime` records. It is not converted into four
separate adjacent-stop trips. This is what will eventually allow Stage 2 to
board once and stay on the vehicle.

#### Stop time

```text
trip_id               -> namespaced StopTime.tripId
stop_id               -> namespaced StopTime.stopId
stop_sequence         -> StopTime.stopSequence
arrival_time          -> nullable integer arrivalSeconds
departure_time        -> nullable integer departureSeconds
pickup_type           -> PickupDropOffType
drop_off_type          -> PickupDropOffType
```

GTFS time is deliberately not converted directly to `LocalTime`. It is stored
as seconds since the service-day midnight, so `24:05:00` remains `86,700` and
`49:05:00` remains `176,700`.

Pickup and drop-off values are explicit:

| GTFS value | Enum | Ordinary boarding/alighting? |
|---:|---|---|
| blank or `0` | `REGULARLY_SCHEDULED` | Yes |
| `1` | `NOT_AVAILABLE` | No |
| `2` | `MUST_PHONE_AGENCY` | No; advance arrangement is distinct and preserved |
| `3` | `MUST_COORDINATE_WITH_DRIVER` | No; driver coordination is distinct and preserved |

The model exposes `allowsOrdinaryBoarding()` and
`allowsOrdinaryAlighting()`. The Stage 1 departure inspection does not make a
routing/boarding decision from these methods. Also note that the current trip
debug JSON does not expose these two enum fields, even though the in-memory
`StopTime` retains them.

#### Service calendar

`calendar.txt` becomes a service ID, an immutable set of weekdays, and inclusive
start/end dates. `calendar_dates.txt` becomes an `ADDED` or `REMOVED` override
for a namespaced service ID and date.

When resolving a date:

1. Add services whose recurring date range and weekday match.
2. Apply `calendar_dates.txt` afterward.
3. An addition inserts the service; a removal removes it.
4. Return a sorted immutable set of IDs.

The exception therefore wins over the weekly calendar.

### Validation before indexing

The adapter fails fast. Invalid feed data does not become a usable `GtfsIndex`.
After mapping, the parser-neutral `GtfsFeedValidator` is the single authority
for SprintWise feed invariants. The loader converts its typed
`GtfsFeedValidationException` into a source-aware `GtfsLoadException`.
`GtfsIndex` invokes the same validator so a programmatically constructed feed
cannot bypass the ingestion contract; it does not maintain a second set of
relationship or timetable rules. `ServiceCalendarResolver` likewise delegates
standalone feed validation to that authority.

The OneBusAway boundary checks source/parser-specific concerns such as path
existence, raw numeric pickup/drop-off values, agency timezone parsing, and
parser read failures. The shared validator checks:

- The source path exists.
- The feed declares exactly one nonblank valid agency timezone.
- Required IDs exist.
- Every ID belongs to the containing feed namespace and entity IDs are unique.
- Parent-station, route, service, trip, and stop references resolve.
- Calendar exception types are supported.
- Every trip has at least two stop times.
- `stop_sequence` is nonnegative, unique, and strictly increasing after sorting.
- First and last stop times contain both arrival and departure.
- An intermediate stop has either both times or neither time.
- A departure is not earlier than its arrival at the same stop.
- Known times do not move backward as the trip proceeds.
- Pickup and drop-off values are in the GTFS range `0..3`.
- Calendar records have valid ranges and unique service/date identities.

Missing both times at an intermediate stop is accepted and kept as two nulls.
Stage 1 does not interpolate it. Times above 24 hours are accepted.

### Structured failure lifecycle

Feed corruption becomes a `GtfsImportDiagnostic` containing stable fields:

- Severity and diagnostic code.
- Feed ID and absolute feed source path.
- GTFS filename when known.
- Entity type and entity identity when known.
- Field name and invalid/referenced ID when known.
- Human-readable detail.

Current diagnostic codes are:

- `source_missing`
- `read_failure`
- `missing_required_id`
- `missing_required_reference`
- `invalid_feed_namespace`
- `duplicate_entity_id`
- `invalid_stop_time`
- `invalid_pickup_drop_off_type`
- `invalid_service_calendar`
- `invalid_agency_timezone`
- `ambiguous_agency_timezone`
- `unsupported_calendar_exception`

All Stage 1 ingestion failures are fatal. A `WARNING` severity exists in the
type system, but there is no warning collector or skip-bad-row policy.

`GtfsLoadException` generates its message from the diagnostic and retains the
original parser cause when one exists. The failed `TransitFeedEntry` wraps and
retains the failure. `DebugApiExceptionHandler` converts it to Problem Details with
status 503 and adds the structured diagnostic fields to the JSON response.

OneBusAway sometimes cannot expose the originating row or referring entity. In
that case SprintWise uses the literal `unspecified`; it does not invent context
or add a second CSV parser just to recover row numbers.

## What remains in memory after loading

`GtfsFeed` is the immutable handoff object from loader to index. Its lists are
defensive copies. Each available `TransitFeedEntry` deliberately retains both
the feed aggregate and the corresponding index so later stages can inspect the
source model without reparsing it.

The long-lived index retains:

| Structure | Contents | Main use |
|---|---|---|
| `stopsById` | Sorted map from `FeedScopedId` to `Stop` | Exact stop lookup and deterministic stop listing |
| `routesById` | Sorted map from ID to `Route` | Exact route lookup and trip validation |
| `tripsById` | Sorted map from ID to `Trip` | Exact trip lookup |
| `stopTimesByTrip` | Immutable ordered `StopTime` lists | Inspect a complete train run and later build RAPTOR data |
| `tripsByStop` | Sorted unique trips serving a stop | Timetable inspection and future scan preparation |
| `departuresByStop` | Sorted `ScheduledDeparture` lists | Efficient departure lookup |
| `ServiceCalendarResolver` | Recurring calendars plus per-date exceptions | Active-service filtering |
| `ServiceTimeResolver` | Feed timezone and maximum scheduled GTFS time | Service-date and `Instant` conversion |
| `GtfsIndexStats` | Entity/reference counts | Logging and memory integration tests |

A timed `StopTime` is therefore represented twice in different forms:

1. The full `StopTime` remains in its trip's ordered list, including arrival,
   departure, stop identity, and pickup/drop-off semantics.
2. A smaller `ScheduledDeparture` is created in the stop's departure list,
   containing the fields needed for fast departure lookup.

This duplication trades memory for query speed. The frozen-MTA integration test
currently reports approximately 565,093 stop-time records, 565,093 scheduled
departure records, and 565,093 grouped stop-time references.

The `GtfsIndex` collections and returned views are immutable. There is no method
that mutates or refreshes the snapshot.

Stage 2A adds one derived `RaptorNetwork` without replacing either `GtfsIndex`.
Its compact integer indexes cover every available feed in one deterministic
global space, while the authoritative identity remains `FeedScopedId`. It
retains:

| Structure | Contents | Purpose |
|---|---|---|
| Stop list and ID map | References to Stage 1 `Stop` records plus compact integer indexes | Constant-time ID/index conversion |
| Trip schedule list and ID map | One `RaptorTripSchedule` for every complete Stage 1 `Trip` | Compact per-trip times and original trip identity |
| Trip patterns | Shared stop/access arrays plus indexes of compatible individual trips | Avoid repeating structural data during later scans |
| Stop-to-pattern arrays | Sorted pattern indexes for every compact stop | Later marked-stop scans avoid visiting every pattern |
| Feed contexts | References to each available `GtfsFeed` and `GtfsIndex` | Preserve timezone, calendars, and Stage 1 ownership |

Each trip schedule deliberately adds three private integer arrays: original
stop sequences, arrivals, and departures. A private `-1` sentinel represents
an intermediate row whose arrival and departure were both absent; public APIs
expose that as `OptionalInt.empty()`. No time is interpolated. Pattern arrays
share compact stop indexes and pickup/drop-off semantics among compatible
trips. None of these arrays are exposed mutably.

The exact pattern key is `(routeId, directionId, ordered FeedScopedId stops,
ordered pickup types, ordered drop-off types)`. Route and direction are
included to preserve branded/directional service boundaries and leave room for
future route policy, not to create agency-specific routing. Because stop and
route IDs were already namespaced by Stage 1, raw MTA/LIRR ID collisions need
no special Stage 2 rule.

Within a structural pattern, trips are ordered by first departure and trip ID.
If a later-departing trip moves ahead at any later known arrival or departure,
the builder deterministically partitions the schedules into multiple
non-overtaking patterns. Missing/missing intermediate positions are ignored for
that comparison because no ordering fact exists there. This favors a simple,
safe later scanner over silently assuming that real timetables never overtake.

ID-to-index lookup is expected `O(1)`; compact index-to-entity lookup is
`O(1)`; and finding the patterns serving a stop is `O(Ps)` to return its `Ps`
precomputed pattern indexes. Construction sorts global stops/trips and each
structural timetable. The deterministic overtaking partition is intentionally
simple and can be quadratic in the number of trips inside one structural
pattern times its stop count; the frozen combined build currently completes in
well under a second, so Stage 2A keeps the clearer policy and records this as a
future scaling consideration.

### Stage 2B single-pattern scanning

`RaptorPatternScanner` is a query-time primitive over the immutable network. Its
contract is:

```java
new RaptorPatternScanner(network).scan(
    patternIndex,
    boardingStopIndex,
    earliestBoardingInstant
)
```

The scanner considers only trips already assigned to that pattern. It finds
every occurrence of the boarding stop in the pattern, requires ordinary pickup
and a known departure at or after the supplied `Instant`, and filters each trip
through its feed's Stage 1 service calendar. It asks the retained `GtfsIndex`
for overlapping service dates and converts GTFS offsets with that feed's
explicit agency timezone. This naturally includes a previous service date for
catchable `24:xx` or later trips and retains the existing noon-minus-twelve DST
policy; the machine timezone is never consulted.

For each downstream pattern position, the scanner returns at most one immutable
`RaptorRide`: the earliest legal arrival obtainable from the boarding state.
The result records the individual trip, route, service date, boarding and
alighting indexes/positions, GTFS seconds, and resolved `Instant` values needed
by later journey backtrace. Each result stays on one trip throughout; grouping
trips in a pattern does not permit switching vehicles.

Boarding requires ordinary pickup and a known departure. Alighting requires
ordinary drop-off and a known arrival. A passenger already aboard may pass
through a missing/missing intermediate position or a stop where drop-off is
prohibited and still reach a later usable stop. No time is interpolated.

Candidate preference and returned ordering are deterministic: arrival
`Instant`, departure `Instant`, trip ID, service date, alighting position, then
boarding position. A valid compact stop absent from the pattern returns an
immutable empty list; invalid compact pattern/stop indexes are argument errors.

For `B` matching boarding positions, `T` trips in the requested pattern, `D`
overlapping service dates, and `L` pattern positions, the current correctness-
first implementation is `O(B*T*D*L)`. Active services and candidate dates are
cached within one call. It never scans trips in another pattern and adds no
large persistent per-position index. Stage 2A's non-overtaking partitions are
preserved, but missing-time correctness does not depend on every trip having a
time at every position.

### Stage 2C marked-stop rounds

`RaptorRoundRouter` composes the Stage 2B primitive into a transit-only
earliest-arrival search:

```java
new RaptorRoundRouter(network).route(
    originStopId,
    destinationStopId,
    departureInstant,
    maxRounds
)
```

All inputs are explicit. Origin and destination are complete `FeedScopedId`
values, departure is an `Instant`, and the maximum number of boardings must be
positive. The current clock and machine timezone are not consulted.

Round 0 contains only the origin at the requested departure. For transit round
N, the router visits the deterministic set of stops strictly improved in round
N-1. For each such stop, it retrieves only that stop's precomputed pattern
indexes and calls `RaptorPatternScanner`; it never performs a global pattern or
trip scan. Every legal downstream `RaptorRide` becomes a candidate label for
the current round. Riding one train across five stops is still one boarding and
one round; boarding another trip is the next round.

`RaptorRound` is an immutable improvement delta: its labels were produced with
exactly that round number of boardings and strictly beat every earlier-round
arrival at the same stop. `RaptorSearchResult.bestLabel(...)` is the combined
view: the best known label using at most the round cap. The result reports every
attempted round, including an empty terminal round when that is how early
termination was discovered.

Dominance is currently arrival-time only. Earlier wins; equal arrival creates
no second state. Equal candidates within one round retain a deterministic
predecessor using arrival, round, incoming departure, trip ID, and previous
stop ID. Strict improvement plus the finite round cap makes cycles terminate.
Each `RaptorLabel` retains its `RaptorRide` and preceding label so the next
layer can backtrace without rerunning the search.

The temporary reboarding rule is exact-stop and zero-slack. A later trip can be
boarded only at the identical compact/feed-scoped stop produced by the prior
ride. It may depart exactly at the prior arrival time. There are no links
between child platforms, parent stations, nearby stops, or corresponding MTA
and LIRR stations. `transfers.txt`, walking, GraphHopper, and station
correspondence are not consulted.

If round N-1 improved `M` stops, and those stops collectively expose `S`
distinct `(pattern, boarding stop, arrival)` scan states, the round performs
`S` Stage 2B scans plus candidate-label comparisons. The expensive work inside
each scan remains Stage 2B's pattern-local `O(B*T*D*L)` implementation. Search
stops immediately after an attempted round produces no strict improvements or
after `maxRounds` transit rounds.

### Stage 2D1 journey reconstruction

`RaptorJourneyReconstructor` is a pure projection over one completed
`RaptorSearchResult`. If the destination is unreachable it returns an empty
`Optional`. Otherwise it starts at the winning destination label, follows one
incoming ride and predecessor per round to round zero, reverses that list, and
returns an immutable chronological `RaptorJourney`.

Every predecessor ride becomes exactly one `RaptorTransitLeg`. The leg carries
the exact trip, route, service, service date, boarding and alighting stop IDs
and pattern positions, GTFS departure/arrival seconds, and resolved departure/
arrival `Instant` values. Riding one train through five stops therefore remains
one leg. Changing to another trip creates another leg even if both trips use
the same `route_id`.

There are no separate waiting or transfer objects in Stage 2D1. The wait at an
exact-stop train change is the duration between the previous leg's arrival and
the next leg's departure. Because Stage 2C added no physical transfer edge,
consecutive legs must connect at the same exact feed-scoped stop.

The reconstructor checks identity-based cycle repetition, consecutive round
numbers, ride-to-label stop indexes, time consistency, and termination at the
original round-zero label. `RaptorJourney` then validates chronological legs,
origin/destination endpoints, the winning-round/leg-count relationship, and
the special reachable origin-equals-destination zero-leg case. It retains the
same immutable `RaptorSearchResult` so round, label, marked-stop, and scan-count
diagnostics are not discarded. Reconstruction is `O(R)` time and space for
`R` boarded trips; it performs no timetable or pattern scan.

### Stage 2D2 application and HTTP boundary

`RaptorRoutingService` owns the application-level operation. It first verifies
that both namespaced stops exist in their catalog indexes, then calls the
`RaptorRoundRouter` and passes its result to `RaptorJourneyReconstructor`. It
returns both the diagnostic search result and the optional reconstructed
journey internally. The service is built once around Spring's singleton
`RaptorNetwork`; it does not rebuild the network for a request.

`DebugController` exposes that operation at:

```text
POST /debug/raptor
Content-Type: application/json
```

Request fields are:

| Field | Contract |
|---|---|
| `fromStopId` | Required complete `feed:stop_id` origin |
| `toStopId` | Required complete `feed:stop_id` destination |
| `departAt` | Required ISO-8601 timestamp containing an explicit UTC offset |
| `maxRounds` | Optional maximum boarded trips; defaults to 4; allowed range 1 through 8 |

The controller validates only HTTP syntax and delegates the search. It does
not scan a timetable, pattern, or label. The stable response projection
includes IDs, requested departure, reachability, optional arrival/winning
round, boardings, attempted rounds, and ordered transit legs. It deliberately
does not serialize `RaptorSearchResult`, label maps, round objects, or the
predecessor graph.

For example, the synthetic production-path test serializes this shape:

```json
{
  "fromStopId": "mta:A",
  "toStopId": "mta:C",
  "departAt": "2026-08-13T11:59:00Z",
  "reachable": true,
  "arrivalAt": "2026-08-13T12:25:00Z",
  "winningRound": 1,
  "numberOfBoardedTrips": 1,
  "roundsAttempted": 2,
  "legs": [
    {
      "tripId": "mta:DIRECT_SLOW",
      "routeId": "mta:DIRECT",
      "serviceId": "mta:DIRECT_CASE",
      "serviceDate": "2026-08-13",
      "boardingStopId": "mta:A",
      "alightingStopId": "mta:C",
      "boardingStopPosition": 0,
      "alightingStopPosition": 1,
      "departureSeconds": 28920,
      "arrivalSeconds": 30300,
      "departureTime": "2026-08-13T12:02:00Z",
      "arrivalTime": "2026-08-13T12:25:00Z"
    }
  ]
}
```

`roundsAttempted` can exceed `winningRound`: after finding the destination,
the router may attempt another round before proving there are no further stop
improvements. A journey's `numberOfBoardedTrips` and leg count equal its
winning round.

The result contracts are:

- Origin equal to destination is reachable in round 0 with no legs.
- A valid disconnected destination is HTTP 200 with `reachable=false`, no
  `arrivalAt`/`winningRound`, and an empty leg list.
- An unknown origin or destination is 404 `not_found`.
- Malformed IDs or timestamps, missing fields, malformed JSON, and an invalid
  round cap are 400 with stable Problem Details codes.
- A stop in a configured feed whose load failed produces the existing 503
  `feed_unavailable` response, including structured import diagnostics.
- Repeated identical requests have deterministic response ordering.

### Running transit-only debug queries

Start the backend on its default port 8081 with both frozen feeds enabled, then
run these fixed-date requests. They never depend on the current clock.

Reachable MTA Penn Station to 181 St:

```bash
curl -sS -X POST http://localhost:8081/debug/raptor \
  -H 'Content-Type: application/json' \
  -d '{"fromStopId":"mta:A28N","toStopId":"mta:A06N","departAt":"2026-08-13T17:00:00-04:00","maxRounds":4}'
```

Reachable LIRR Penn Station to Woodmere:

```bash
curl -sS -X POST http://localhost:8081/debug/raptor \
  -H 'Content-Type: application/json' \
  -d '{"fromStopId":"lirr:237","toStopId":"lirr:217","departAt":"2026-08-13T17:00:00-04:00","maxRounds":4}'
```

Valid but unreachable cross-feed request, because Stage 2 has no MTA-LIRR
transfer edge:

```bash
curl -sS -X POST http://localhost:8081/debug/raptor \
  -H 'Content-Type: application/json' \
  -d '{"fromStopId":"mta:A28N","toStopId":"lirr:217","departAt":"2026-08-13T17:00:00-04:00","maxRounds":4}'
```

Malformed request with an offset-free timestamp:

```bash
curl -sS -X POST http://localhost:8081/debug/raptor \
  -H 'Content-Type: application/json' \
  -d '{"fromStopId":"mta:A28N","toStopId":"mta:A06N","departAt":"2026-08-13T17:00:00"}'
```

These are exact-stop, schedule-only queries. No access walk, platform change,
station matching, transfer path, or destination egress is implied.

### Stage 2 completion audit

| Requirement | Verified implementation |
|---|---|
| One immutable composite timetable | Stage 2A builds one singleton `RaptorNetwork` over all available feed contexts |
| Preserve complete GTFS trips | Every `RaptorTripSchedule` remains one original namespaced trip with its full ordered stops |
| Legal, service-aware ride scan | Stage 2B checks calendars, catchability, pickup/drop-off, extended time, and explicit feed timezone |
| Marked-stop earliest-arrival rounds | Stage 2C scans only patterns at improved stops and applies deterministic time-only dominance |
| One ride is one round | Staying aboard through any number of stops consumes one round; boarding a different trip consumes another |
| One boarded trip is one leg | Stage 2D1 validates and reconstructs exactly one chronological leg per incoming ride |
| Thin HTTP exposure | Stage 2D2 delegates to `RaptorRoutingService` and projects stable fields without exposing internal state |
| Namespace and determinism | Feed-scoped IDs survive every layer; immutable sorted structures and tie-breakers remain intact |
| Explicit time semantics | GTFS service-day offsets, including values above 24 hours, resolve only with service date and feed timezone |
| Scope boundary | No walking, physical/cross-feed transfer edge, GraphHopper, sprint state, Pareto bag, OTP routing, or frontend work was added |

This is implementation completion, not a claim of completed human review.

## A concrete row from file to JSON

Consider this synthetic row:

```csv
NIGHT,24:05:00,24:05:00,A,1
```

Its lifetime is:

1. OneBusAway reads it as a parser-owned stop-time object.
2. The adapter resolves its trip and stop references.
3. `NIGHT` becomes `synthetic:NIGHT`; `A` becomes `synthetic:A`.
4. Both times become integer `86,700`, not a `LocalTime`.
5. Blank pickup/drop-off fields become `REGULARLY_SCHEDULED`.
6. Trip-level validation checks ordering and timing.
7. The immutable `StopTime` enters `GtfsFeed.stopTimes`.
8. `GtfsIndex` retains it under `stopTimesByTrip[synthetic:NIGHT]`.
9. The index creates a `ScheduledDeparture` and puts it into the sorted schedule
   for `synthetic:A`.
10. A request just after civil midnight supplies an explicit `Instant`.
11. `ServiceTimeResolver` includes the previous service date because the feed's
    maximum scheduled time overlaps the query.
12. `ServiceCalendarResolver` proves that `synthetic:WEEKDAY` is active on that
    previous service date.
13. Binary search begins at the relevant GTFS second in the stop schedule.
14. The scheduled row becomes a `TimetableDeparture` containing both the service
    date and a concrete departure `Instant`.
15. `DepartureDebugResponse` converts namespaced IDs and time fields to JSON.

The original CSV file is never modified.

## Departure-query lifecycle

The endpoint is:

```text
GET /debug/departures?stopId=mta:101S&at=2026-08-13T08:00:00-04:00&limit=3
```

The steps are:

1. `DebugController` requires `feed:id` syntax.
2. The timestamp must be ISO-8601 with an explicit offset. It is converted to
   an `Instant`; the machine's timezone is not used.
3. The `mta` namespace selects only the MTA catalog entry and index. The stop
   must exist there, and `limit` must be 1 through 100.
4. The index directly obtains that stop's sorted departure list. It never scans
   the entire feed.
5. `ServiceTimeResolver` starts around the query's agency civil date and walks
   backward only while the feed's maximum scheduled time can overlap the query.
   It also checks the next civil date for the noon-minus-twelve DST boundary.
6. For every candidate service date, the calendar resolver computes active
   service IDs.
7. The query instant is converted to seconds from that service date's GTFS time
   zero.
8. Binary search finds the first potentially relevant scheduled departure.
9. The scan skips inactive services and departures that resolve before the
   query instant.
10. Candidate results are converted to `TimetableDeparture`, merged, and sorted
    by concrete departure instant, trip ID, stop sequence, and service date.
11. The endpoint returns at most the requested limit.

If there are `D` candidate service dates and `S` departures at the stop, each
date uses `O(log S)` binary search followed by the entries needed to collect the
result (including skipped inactive entries). The final candidate list is small
and bounded by the maximum scheduled time found in the feed, not an arbitrary
number of historical days.

This Stage 1 method inspects service dates overlapping the query. It is not an
unbounded search for the next departure on some later day. If all relevant
service for the current window has ended, it may return an empty list rather
than searching tomorrow's schedule.

## Other HTTP data journeys

### Stop inspection

```text
GET /debug/stop/{feed:stopId}
```

The controller parses the namespaced ID, performs a sorted-map lookup, and maps
the `Stop` to `StopDebugResponse`. Unknown stops return a structured 404.

### Trip inspection

```text
GET /debug/trip/{feed:tripId}
```

The controller looks up the `Trip`, fetches its complete ordered stop-time list,
and returns `TripDebugResponse`. This proves that a multi-stop train remains one
trip. The current response shows stop ID, sequence, arrival seconds, and
departure seconds; it does not yet show pickup/drop-off enums.

### Active-service inspection

```text
GET /debug/services?feedId=mta&date=2026-08-13
```

The explicit feed ID selects one catalog entry. The explicit date is resolved
through that feed's weekly calendars plus exception overrides. The response
contains a deterministic sorted list of namespaced service IDs.

### Ordinary API errors

Malformed IDs, dates, timestamps, limits, and missing parameters are request
errors, not feed-import diagnostics. `DebugApiExceptionHandler` returns HTTP
Problem Details with stable request-level codes. Unknown resources and
unknown/disabled feeds return 404. A configured feed whose GTFS load failed
returns 503 without affecting successful catalog entries.

## MTA and LIRR today

### MTA subway

The default backend configuration loads `data/gtfs/mta` with namespace `mta`
into its own index.

The frozen integration measurement most recently observed:

- 1,488 stops
- 29 routes
- 20,621 trips
- 565,093 stop times
- About 107.4 MiB retained after index construction
- Successful construction under a 2 GiB maximum heap

### LIRR

The default backend also loads `data/gtfs/lirr` with namespace `lirr` through
the same production path into a separate index. Either feed can be disabled or
have its path changed independently:

```bash
SPRINTWISE_MTA_GTFS_ENABLED=false \
SPRINTWISE_LIRR_GTFS_PATH=../data/gtfs/lirr \
mvn spring-boot:run
```

`TransitFeedCatalog` remains a multi-feed **container**, and there is still no
merged MTA+LIRR `GtfsIndex`. Stage 2A builds one composite derived
`RaptorNetwork` over the available entries so the same future scanner can treat
all SprintWise trips uniformly. It does not infer that equally named or nearby
stops correspond, and pattern grouping does not create transfers. Feed-scoped
IDs let both datasets coexist without collisions; station matching and
cross-feed routing remain later work.

### OTP's separate view

OTP is not called by the Stage 1 backend. Separately, `data/build-config.json`
tells OTP to build `data/graph.obj` from:

- `data/nyc-metro.osm.pbf`
- `data/gtfs/mta`
- `data/gtfs/lirr`

That combined OTP graph is a reference/baseline artifact. It is loaded by the
OTP shell scripts and served on port 8080. SprintWise's debug backend runs on
8081 and does not read `graph.obj`.

## OSM, walking, and journey data

### Current OSM lifecycle

```text
Geofabrik New York PBF
        |
        | scripts/download-data.sh + osmium bounding-box clip
        v
data/nyc-metro.osm.pbf
        |
        | scripts/run-otp.sh
        v
data/graph.obj
        |
        | scripts/start-otp.sh
        v
OTP server on port 8080
```

This path is separate from the Java Stage 1 timetable index.

### GraphHopper

There is no GraphHopper dependency, configuration, graph, service, cache, or
production code in the current repository. The synthetic
`mock-graphhopper-footpaths.csv` is only future test input; no current routing
test consumes it beyond fixture-integrity checks.

### Search-result lifecycle

Stage 2D1 added `RaptorJourney`, `RaptorTransitLeg`, and
`RaptorJourneyReconstructor`. The reconstructor consumes the immutable
`RaptorSearchResult`, follows its winning labels to round zero, and produces an
ordered transit-only journey. The journey deliberately retains that exact
search result, so reconstruction does not throw away its rounds, alternate
best-stop labels, or scan statistics.

An unreachable search reconstructs to `Optional.empty()`. A reachable
origin-equals-destination search reconstructs to a zero-leg journey whose
arrival equals the requested departure. Otherwise, the number of transit legs
equals the winning round. Waiting is visible as a timestamp gap between legs,
not represented as a new edge. Stage 2D2's application service and debug DTOs
then select only the user-readable fields for JSON. The internal result remains
available to Java callers but is not exposed as an HTTP object graph. There is
still no general multimodal `Itinerary`, walking leg, `FootpathService`, or
`/plan` production type.

## Test-data lifecycles

### Synthetic GTFS

The files under `backend/src/test/resources/fixtures/synthetic-gtfs` have the
same GTFS table shape consumed in production. Tests pass that directory to the
same `OneBusAwayGtfsLoader`; there is no separate mock parser.

Malformed-feed tests copy this fixture into a JUnit temporary directory, alter
the copy, and assert structured loader failures. The checked-in fixture and
frozen real feeds remain unchanged.

Additional owned test feeds/records prove behaviors that the tiny checked-in
fixture does not naturally contain, including a five-stop run and `49:xx`
service. Those cases still use SprintWise domain/index code.

`backend/src/test/resources/fixtures/synthetic-raptor-gtfs` is a second tiny,
valid GTFS directory dedicated to full production-path endpoint tests. It is
loaded through the same `OneBusAwayGtfsLoader`, catalog, index, and network
builder as real data. Its schedules distinguish one five-stop train from two-
and three-train same-route journeys, and include a previous-service-date
`24:xx` train plus a disconnected stop.

### Frozen MTA integration

The `real-mta-index` Maven profile runs tests ending in `IT` in a separate JVM
with `-Xmx2G`. It loads the same `data/gtfs/mta` directory through the same
adapter and index constructor. It measures file size, entity counts, load/index
time, and approximate retained heap, and it performs known real debug lookups.

Normal unit tests do not require frozen real data. Integration tests use JUnit
assumptions to skip when the configured directory is absent.

### Frozen LIRR and combined-catalog integration

The `real-lirr-stage1` profile proves the complete LIRR snapshot travels through
the ordinary loader/index path. The `real-mta-lirr-catalog` profile loads both
snapshots into one `TransitFeedCatalog`, while retaining two feeds and two
indexes. It verifies simultaneous known lookups, namespace isolation, combined
memory use, and construction under `-Xmx2G`. Neither profile creates a transfer
or combined route.

### Composite RAPTOR-network integration

The `real-mta-lirr-raptor-network` profile builds the derived Stage 2A index in
its own `-Xmx2G` JVM. On the frozen snapshots it retains all 1,615 stops and
22,764 individual trips, derives 737 structural patterns, and partitions 88
overtaking structures into 860 final safe patterns. The most recent run took
about 0.11 seconds and added approximately 10 MiB beyond the retained Stage 1
catalog. The profile skips when either frozen directory is absent.

### Real single-pattern scanner integration

The `real-mta-lirr-pattern-scan` profile loads both frozen feeds through the
production adapter/catalog/network path, then proves the same Stage 2B scanner
can resolve one real MTA ride and one real LIRR ride under `-Xmx2G`. It derives
explicit timestamps from dates inside the frozen snapshots and never uses the
current clock for routing assertions. The profile skips when either directory
is absent.

### Real marked-round search integration

The `real-mta-lirr-round-search` profile loads both frozen feeds through the
same production adapter, catalog, compact network, and Stage 2D2 routing
service in a separate `-Xmx2G` JVM. It proves a one-round MTA ride from northbound 34
St-Penn Station (`mta:A28N`) to 181 St (`mta:A06N`) and a one-round LIRR ride
from Penn Station (`lirr:237`) to Woodmere (`lirr:217`) at an explicit time on
2026-08-13. The service reconstructs each result as one immutable real transit
leg and verifies every ID, service date, GTFS offset, and `Instant` against its
selected ride. The real-MTA HTTP integration test also invokes
`POST /debug/raptor` for the same subway case. The combined profile proves the
same application-service contract for LIRR without adding a second router or
feed-specific branch. It prints the legs, completed rounds, marked labels,
pattern-scan counts, and search time. It skips if either frozen feed directory
is absent.

### Golden data

`otp-real-baseline.json` stores normalized expected facts for real reference
cases. It is not loaded into production. `docs/golden-queries.md` describes
future exact journeys, many of which cannot run until later routing stages.

## Complete folder guide

This guide covers every meaningful repository folder. Git's hash-sharded object
directories are grouped under `.git/objects`; they are storage internals, not
application architecture.

| Folder | Purpose |
|---|---|
| `/` | Repository root: overview, architecture/status documents, and top-level project folders |
| `.cursor/` | Currently empty Cursor-specific workspace folder |
| `.vscode/` | Shared VS Code workspace settings |
| `.git/` | Local Git database; not application source |
| `.git/hooks/` | Local Git hook samples/configuration |
| `.git/info/` | Local Git metadata |
| `.git/logs/` | Reflogs recording local reference movement |
| `.git/objects/` | Git's content-addressed objects and hash-prefix storage folders |
| `.git/refs/` | Local branches, remotes, tags, and Codex-created refs |
| `.git/filter-repo/` | Metadata from prior `git filter-repo` operations |
| `.git/cursor/` | Cursor-maintained Git metadata; not production code |
| `backend/` | Java 25 Spring Boot module and Maven build |
| `backend/src/` | Maintained backend source tree |
| `backend/src/main/` | Production Java and runtime resources |
| `backend/src/main/java/` | Java package source root |
| `backend/src/main/java/com/` | Reverse-domain package hierarchy root |
| `backend/src/main/java/com/sprintwise/` | SprintWise application package and Spring component-scan root |
| `backend/src/main/java/com/sprintwise/config/` | Spring properties and bean wiring |
| `backend/src/main/java/com/sprintwise/debug/` | Read-only Stage 1 inspection plus the Stage 2 transit-routing debug endpoint, DTOs, and HTTP error translation |
| `backend/src/main/java/com/sprintwise/gtfs/` | Parser-neutral loading interface, diagnostics, and load exception |
| `backend/src/main/java/com/sprintwise/gtfs/onebusaway/` | The only production boundary allowed to depend on OneBusAway |
| `backend/src/main/java/com/sprintwise/gtfs/validation/` | Parser-neutral feed invariants and typed validation failure context |
| `backend/src/main/java/com/sprintwise/gtfs/calendar/` | Active-service calculation |
| `backend/src/main/java/com/sprintwise/gtfs/time/` | GTFS service time/date/instant conversion |
| `backend/src/main/java/com/sprintwise/model/` | Immutable SprintWise transit-domain records |
| `backend/src/main/java/com/sprintwise/index/` | Immutable lookup structures and resolved departure types |
| `backend/src/main/java/com/sprintwise/raptor/` | Compact RAPTOR timetable, exact-stop round engine, and immutable transit-journey reconstruction |
| `backend/src/main/java/com/sprintwise/service/` | Application-lifetime catalog of independent feed/index snapshots or failures |
| `backend/src/main/resources/` | Runtime Spring configuration |
| `backend/src/test/` | Backend test code and test-only data |
| `backend/src/test/java/` | JUnit Java source root |
| `backend/src/test/java/com/sprintwise/` | Test package root mirroring production packages |
| `backend/src/test/java/com/sprintwise/debug/` | Synthetic controller tests and frozen-MTA API integration test |
| `backend/src/test/java/com/sprintwise/golden/` | Integrity checks for synthetic and normalized golden artifacts |
| `backend/src/test/java/com/sprintwise/gtfs/` | GTFS test hierarchy |
| `backend/src/test/java/com/sprintwise/gtfs/onebusaway/` | Loader, mapping, validation, and diagnostic tests |
| `backend/src/test/java/com/sprintwise/gtfs/validation/` | Programmatic-feed validation and index-boundary tests |
| `backend/src/test/java/com/sprintwise/gtfs/calendar/` | Weekly and exception-calendar tests |
| `backend/src/test/java/com/sprintwise/gtfs/time/` | Midnight, maximum-span, timezone, and DST tests |
| `backend/src/test/java/com/sprintwise/index/` | Timetable index unit tests and real-feed memory integration test |
| `backend/src/test/java/com/sprintwise/raptor/` | Pattern/index/scanner/round unit tests and isolated real-feed proofs |
| `backend/src/test/java/com/sprintwise/service/` | Multi-feed catalog, failure-isolation, and combined-real-feed tests |
| `backend/src/test/resources/` | Test inputs copied onto the Maven test classpath |
| `backend/src/test/resources/fixtures/` | Parent for owned synthetic fixtures |
| `backend/src/test/resources/fixtures/synthetic-gtfs/` | Tiny valid GTFS feed plus future mock walking data |
| `backend/src/test/resources/fixtures/synthetic-raptor-gtfs/` | Tiny valid GTFS feed for one-, two-, and three-trip endpoint journeys |
| `backend/src/test/resources/golden/` | Normalized real OTP baseline JSON |
| `backend/target/` | Generated Maven output; safe to recreate, not maintained source |
| `backend/target/classes/` | Compiled production classes and copied runtime resources |
| `backend/target/test-classes/` | Compiled tests and copied fixtures/golden data |
| `backend/target/generated-sources/` | Maven/compiler generated production source area |
| `backend/target/generated-test-sources/` | Maven/compiler generated test source area |
| `backend/target/maven-archiver/` | Generated JAR build metadata |
| `backend/target/maven-status/` | Compiler/build bookkeeping |
| `backend/target/surefire-reports/` | Normal unit-test reports |
| `backend/target/failsafe-reports/` | Integration-test reports |
| `data/` | Frozen local OSM, GTFS, OTP configuration, and built graph |
| `data/gtfs/` | Parent directory for static transit feeds |
| `data/gtfs/mta/` | Frozen MTA subway GTFS snapshot; default backend feed |
| `data/gtfs/lirr/` | Frozen LIRR GTFS snapshot; independently co-loaded by default |
| `docs/` | Routing plan and golden-query specification |
| `frontend/` | Placeholder frontend package; no committed application source yet |
| `otp/` | Downloaded OTP executable JAR |
| `scripts/` | Data setup, Java-version enforcement, and OTP build/start scripts |

## Important and semi-important file guide

### Root files

| File | What it does |
|---|---|
| `.gitignore` | Ignores frozen/downloaded data contents, OTP JARs, Maven output, Node modules, environment files, and editor junk; preserves `data/.gitkeep` |
| `.java-version` | Pins Java 25 for version-manager-aware tools |
| `.vscode/settings.json` | Enables automatic Java null analysis and Maven/build-configuration updates in VS Code |
| `README.md` | Product vision and setup instructions; parts describe future rather than current implementation |
| `STATUS.md` | Intentionally empty after completion of the Stage 1 findings task |
| `CURRENT-ARCHITECTURE.md` | This human-readable description of the current implementation |

### Backend build and runtime configuration

| File | What it does |
|---|---|
| `backend/pom.xml` | Defines Java/Spring/OneBusAway plus isolated Stage 1, compact-RAPTOR, scanner, and round-search/journey real-data profiles |
| `backend/src/main/resources/application.yml` | Sets port 8081 and independent MTA/LIRR paths and enabled flags |
| `backend/src/main/java/com/sprintwise/SprintWiseApplication.java` | Spring Boot entry point and component-scan root |

### Configuration and service ownership

| File | What it does |
|---|---|
| `config/GtfsProperties.java` | Spring binding object for the list of feed IDs, paths, and enabled flags |
| `config/TransitConfiguration.java` | Creates the parser-neutral loader, singleton `TransitFeedCatalog`, derived composite `RaptorNetwork`, and routing service |
| `service/TransitFeedCatalog.java` | Validates configuration, loads enabled feeds once, sorts entries, and dispatches feed/index access |
| `service/TransitFeedEntry.java` | Immutable available feed/index pair or retained unavailable-feed state, including construction timing |
| `service/UnknownFeedException.java` | Signals the documented unknown-or-disabled feed contract |
| `service/FeedUnavailableException.java` | Carries feed ID, source path, cause, and optional structured diagnostic after startup loading fails |
| `service/RaptorRoutingService.java` | Reuses the singleton network to validate stops, run RAPTOR, and reconstruct the optional journey outside HTTP code |
| `service/RaptorRoutingOutcome.java` | Immutable pairing of the retained search result and its reachable optional journey |
| `service/UnknownTransitStopException.java` | Identifies an unknown namespaced routing origin or destination for HTTP 404 translation |

### GTFS boundary and diagnostics

| File | What it does |
|---|---|
| `gtfs/GtfsLoader.java` | Parser-neutral `load(Path, feedId)` interface |
| `gtfs/onebusaway/OneBusAwayGtfsLoader.java` | Small orchestration entry point: configure/read, map, validate, and return or translate failure |
| `gtfs/onebusaway/OneBusAwayGtfsMapper.java` | Copies OneBusAway entities into sorted, feed-namespaced SprintWise records |
| `gtfs/onebusaway/OneBusAwayImportDiagnostics.java` | Translates parser, mapping, and shared-validation failures into structured load diagnostics |
| `gtfs/validation/GtfsFeedValidator.java` | Single parser-neutral authority for namespaces, references, calendars, and complete-trip timetable invariants |
| `gtfs/validation/GtfsFeedValidationException.java` | Typed source-file/entity/field failure returned by shared validation before adapter translation |
| `gtfs/GtfsImportDiagnostic.java` | Immutable machine-readable feed-error context and readable message formatter |
| `gtfs/GtfsDiagnosticCode.java` | Stable feed-error categories used in tests and HTTP JSON |
| `gtfs/GtfsDiagnosticSeverity.java` | `FATAL`/`WARNING` vocabulary; Stage 1 currently emits fatal failures only |
| `gtfs/GtfsLoadException.java` | Exception carrying a diagnostic and optional original parser cause |

### Domain models

| File | What it does |
|---|---|
| `model/FeedScopedId.java` | Validates, orders, and formats IDs as `feed:id` to prevent future feed collisions |
| `model/GtfsFeed.java` | Immutable aggregate passed from loader to index |
| `model/Stop.java` | Stop/station identity, descriptive fields, coordinates, type, and parent relationship |
| `model/Route.java` | Route identity, names, and GTFS route type |
| `model/Trip.java` | One complete scheduled run and its route/service references |
| `model/StopTime.java` | One ordered trip call with nullable GTFS times and pickup/drop-off semantics |
| `model/PickupDropOffType.java` | Named mapping for all four GTFS access values and ordinary-use semantics |
| `model/ServiceCalendar.java` | Recurring weekday/date-range service rule |
| `model/ServiceCalendarDate.java` | Added/removed service override on a specific date |

### Calendar and time

| File | What it does |
|---|---|
| `gtfs/calendar/ServiceCalendarResolver.java` | Builds immutable calendar maps and applies date exceptions over recurring service |
| `gtfs/time/ServiceTime.java` | Pair of service date and nonnegative GTFS seconds since service-day midnight |
| `gtfs/time/ServiceTimeResolver.java` | Owns agency timezone, noon-minus-twelve conversion, maximum trip span, and candidate service dates |

### Timetable index

| File | What it does |
|---|---|
| `index/GtfsIndex.java` | Builds and exposes all immutable Stage 1 lookup maps, binary-searches departures, and resolves active concrete results |
| `index/ScheduledDeparture.java` | Compact feed-schedule departure before choosing a service date |
| `index/TimetableDeparture.java` | Departure resolved to a concrete service date and `Instant` |
| `index/GtfsIndexStats.java` | Counts major index structures for logging and measurement |

### RAPTOR timetable index

| File | What it does |
|---|---|
| `raptor/RaptorNetworkBuilder.java` | Deterministically assigns compact IDs, derives structural patterns, and partitions overtaking timetables |
| `raptor/RaptorNetwork.java` | Immutable composite lookup surface for available feeds, stops, trips, patterns, and stop-to-pattern mappings |
| `raptor/RaptorFeedContext.java` | Retains references to one feed's immutable Stage 1 feed/index and explicit timezone context |
| `raptor/RaptorTripPattern.java` | Shared route/direction/stop/access structure and one non-overtaking group of trip indexes |
| `raptor/RaptorTripSchedule.java` | One complete individual trip with private compact sequence/arrival/departure arrays |
| `raptor/RaptorNetworkStats.java` | Counts feeds, compact entities, patterns, overtaking partitions, and stored positions |
| `raptor/RaptorPatternScanner.java` | Applies calendars, catchability, access rules, and per-position earliest-arrival selection within one requested pattern |
| `raptor/RaptorRide.java` | Immutable one-trip boarding/alighting result with service date, GTFS offsets, and concrete instants |
| `raptor/RaptorRoundRouter.java` | Runs marked-stop earliest-arrival rounds, scans only stop-indexed patterns, applies time-only dominance, and stops early |
| `raptor/RaptorLabel.java` | Immutable stop arrival state with round, incoming ride, and predecessor retained for later backtrace |
| `raptor/RaptorRound.java` | Immutable exactly-N-boardings improvement delta, marked-stop set, and pattern-scan count |
| `raptor/RaptorSearchResult.java` | Immutable query metadata, attempted rounds, global best labels, reachability, and winning round |
| `raptor/RaptorTransitLeg.java` | Immutable user-readable projection of one exact scheduled ride, including namespaced IDs, stops, GTFS offsets, and Instants |
| `raptor/RaptorJourney.java` | Immutable reachable journey with chronological transit legs and the original search result retained for diagnostics |
| `raptor/RaptorJourneyReconstructor.java` | Validates and reverses the winning predecessor chain into transit legs without rerunning the search |

### Debug HTTP layer

| File | What it does |
|---|---|
| `debug/DebugController.java` | Implements Stage 1 inspection plus `POST /debug/raptor`; validates HTTP syntax and delegates routing |
| `debug/DebugApiExceptionHandler.java` | Converts request, malformed-JSON, unknown-stop, and feed failures into HTTP Problem Details JSON |
| `debug/DebugBadRequestException.java` | Internal request-validation exception with a stable code |
| `debug/DebugNotFoundException.java` | Internal unknown-resource exception |
| `debug/StopDebugResponse.java` | JSON projection of a `Stop` |
| `debug/DepartureDebugResponse.java` | JSON projection of a concrete `TimetableDeparture` |
| `debug/TripDebugResponse.java` | JSON projection of a `Trip` plus ordered stop times |
| `debug/StopTimeDebugResponse.java` | Current limited JSON projection of stop-time identity/sequence/times |
| `debug/ActiveServicesDebugResponse.java` | JSON projection of sorted services active on one date |
| `debug/RaptorRouteDebugRequest.java` | JSON input contract for namespaced stops, explicit timestamp, and optional round cap |
| `debug/RaptorRouteDebugResponse.java` | Stable top-level journey JSON that omits internal labels and predecessor state |
| `debug/RaptorTransitLegDebugResponse.java` | JSON projection of one reconstructed scheduled transit leg |

### Test classes

| File | What it proves |
|---|---|
| `golden/GoldenFixtureIntegrityTest.java` | Synthetic network shape, after-midnight data, mock footpath math, and real golden case IDs remain intact |
| `gtfs/onebusaway/OneBusAwayGtfsLoaderTest.java` | Production-path loading, relationships, namespacing, ordering, >24-hour values, pickup/drop-off values, five-stop trips, validation, and structured diagnostics |
| `gtfs/validation/GtfsFeedValidatorTest.java` | Valid and invalid programmatic feeds use the same validation authority as adapter-loaded feeds and indexes |
| `gtfs/calendar/ServiceCalendarResolverTest.java` | Weekday/weekend rules and exception additions/removals |
| `gtfs/time/ServiceTimeResolverTest.java` | Agency timezone, `24:xx`, feed-derived `49:xx` window, spring/fall DST, and bounded date candidates |
| `index/GtfsIndexTest.java` | Deterministic entity order, daytime/after-midnight/49-hour lookup, calendar filtering, tie-breaking, empty contracts, and immutability |
| `service/TransitFeedCatalogTest.java` | Two-feed collisions, ordering, immutability, one-time loading, disabled feeds, and failure isolation |
| `service/CombinedFrozenFeedsCatalogIT.java` | Simultaneous frozen MTA/LIRR indexes fit under 2 GiB and preserve namespace isolation |
| `raptor/RaptorNetworkTest.java` | Composite namespacing, grouping, complete trips, missing times, access differences, immutability, and overtaking partitions |
| `raptor/CompositeRaptorNetworkIT.java` | Frozen MTA/LIRR composite pattern index fits under `-Xmx2G` and retains every Stage 1 trip |
| `raptor/RaptorPatternScannerTest.java` | Catchability, calendars, extended times, access restrictions, missing times, repeated stops, ties, namespaces, and overtaking scans |
| `raptor/RealPatternScannerIT.java` | Same production scanner resolves explicit real MTA and LIRR rides under `-Xmx2G` |
| `raptor/RaptorRoundRouterTest.java` | One-/two-/three-trip rounds and journeys, same-route train changes, waits, exact transfers, service rules, 24:xx, chain validation, namespaces, and immutability |
| `raptor/RealRaptorRoundSearchIT.java` | Stage 2D2 application service produces fixed-date Penn-to-181 St MTA and Penn-to-Woodmere LIRR journeys under `-Xmx2G` |
| `debug/DebugControllerTest.java` | All debug endpoints and their 200/400/404/503 JSON contracts using synthetic production loading |
| `debug/RaptorDebugControllerTest.java` | Full production-path HTTP proof of one five-stop leg, two/three trip changes, extended times, zero-leg, and unreachable results |
| `index/RealMtaIndexSizeIT.java` | Frozen MTA fits under `-Xmx2G` and reports entity, time, heap, and duplication measurements |
| `index/RealLirrStage1CompatibilityIT.java` | Frozen LIRR loader/index compatibility, extended-time behavior, references, and memory measurements |
| `debug/RealMtaDebugApiIT.java` | Known real inspection lookups and the Penn-to-181 St RAPTOR request work through Spring HTTP components |

### Synthetic and golden resources

| File | What it contains |
|---|---|
| `fixtures/synthetic-gtfs/README.md` | Diagram, special services, speed assumptions, and DST policy for the owned fixture |
| `agency.txt` | Synthetic agency and `America/New_York` timezone |
| `stops.txt` | Five synthetic stop/station/platform records |
| `routes.txt` | RED, BLUE, and DIRECT routes |
| `trips.txt` | Eight scheduled trips for normal, direct, night, exception, and weekend cases |
| `stop_times.txt` | Sixteen ordered calls, including `NIGHT` at `24:05` and `24:15` |
| `calendar.txt` | Recurring weekday/weekend service |
| `calendar_dates.txt` | Addition/removal and direct-case exceptions |
| `feed_info.txt` | Minimal valid feed metadata for the GTFS fixture |
| `mock-graphhopper-footpaths.csv` | Future deterministic walking edges; not GTFS and not current production GraphHopper data |
| `fixtures/synthetic-raptor-gtfs/README.md` | Diagram and exact journey cases for the dedicated endpoint fixture |
| `fixtures/synthetic-raptor-gtfs/*.txt` | Valid agency, stops, routes, trips, stop times, calendar, and metadata used by endpoint tests |
| `golden/otp-real-baseline.json` | Normalized OTP facts for frozen real-world golden cases |

### Frozen data and OTP files

| File | What it does |
|---|---|
| `data/.gitkeep` | Keeps the otherwise ignored data directory in Git |
| `data/README.md` | Explains data setup and major artifacts |
| `data/manifest.yml` | Records download URLs, clipping bounds, and destinations |
| `data/build-config.json` | Tells OTP about timezone plus both MTA and LIRR feeds |
| `data/nyc-metro.osm.pbf` | Frozen clipped OSM streets for NYC and Long Island; not read by Stage 1 backend |
| `data/graph.obj` | Generated OTP graph built from OSM and both GTFS feeds; not read by Stage 1 backend |
| `otp/otp-shaded-2.9.0.jar` | Downloaded OTP executable used only by shell scripts/manual baseline work |

Both `data/gtfs/mta` and `data/gtfs/lirr` contain standard GTFS tables:

| Feed file | Meaning | Stage 1 adapter use |
|---|---|---|
| `agency.txt` | Operator and timezone | Timezone only |
| `stops.txt` | Stops/stations/platforms | Loaded |
| `routes.txt` | Transit routes | Loaded |
| `trips.txt` | Scheduled runs | Loaded |
| `stop_times.txt` | Ordered calls and times | Loaded |
| `calendar.txt` | Recurring service, when present | Loaded; MTA has it, frozen LIRR relies on its available calendar data |
| `calendar_dates.txt` | Date-specific service exceptions | Loaded |
| `transfers.txt` | Transfer rules | Ignored by current SprintWise backend |
| `shapes.txt` | Vehicle path geometry | Ignored by current SprintWise backend |
| `feed_info.txt` | Feed metadata/version | Ignored by current SprintWise domain |

### Scripts

| File | What it does |
|---|---|
| `scripts/download-data.sh` | One-time setup: downloads/clips OSM, downloads MTA/LIRR GTFS, and downloads OTP; current snapshot policy says not to run it during ordinary development |
| `scripts/require-java-25.sh` | Reusable shell function that rejects any Java specification version other than 25 |
| `scripts/run-otp.sh` | Validates inputs and uses OTP with 4 GiB heap to build/save `data/graph.obj` |
| `scripts/start-otp.sh` | Validates Java/JAR/graph and starts the already-built OTP graph with 4 GiB heap |

### Documentation and frontend

| File | What it does |
|---|---|
| `docs/ROUTING-PLAN.md` | Authoritative staged design, current Stage 1 completion note, and explicit Stage 2 deferrals |
| `docs/golden-queries.md` | Frozen real cases and synthetic expected future routing outcomes |
| `frontend/package.json` | Placeholder package metadata/scripts; there are no committed frontend source files or dependencies yet |

### Generated build files

`backend/target/backend-0.0.1-SNAPSHOT.jar` is the generated backend artifact.
Everything below `backend/target` is reproducible Maven output and should not be
hand-edited. Test report XML/text files there describe the latest local run, not
permanent architecture.

## Current invariants worth protecting

- OneBusAway types never escape the adapter package.
- Every application ID is feed-namespaced.
- One GTFS trip remains one complete ordered run.
- Times stay as service-day seconds until a service date is chosen.
- The agency timezone is explicit; machine default timezone is irrelevant.
- `calendar_dates.txt` overrides recurring calendar service.
- Every candidate service date is calendar-filtered.
- Per-stop departures remain sorted and binary-searched.
- Equal-time results have stable tie-breaking.
- Index collections are immutable after one-time construction.
- Fatal feed corruption never silently produces a partial timetable.
- Synthetic and frozen-real tests use the same production loader/index path.
- The RAPTOR index derives from Stage 1 objects; it does not reinterpret GTFS.
- Compact indexes retain exact `FeedScopedId` identity across all feeds.
- Every RAPTOR trip schedule still represents one complete Stage 1 trip.
- Every exposed RAPTOR collection is immutable and internal arrays stay private.
- Round N scans only patterns serving stops strictly improved in round N-1.
- One uninterrupted ride consumes one round regardless of its stop count.
- A new trip consumes another round and currently requires the exact same
  feed-scoped stop, with zero slack.
- Equal stop arrivals do not create duplicate search states; ties are stable.
- Journey reconstruction creates exactly one immutable leg per predecessor
  ride, preserves trip identity and feed namespaces, and retains the original
  search result.
- Unreachable searches have no journey; origin-equals-destination has a
  reachable zero-leg journey.
- The singleton `RaptorNetwork` is reused by `RaptorRoutingService`; routing
  requests never rebuild it.
- The RAPTOR HTTP response is a stable projection and never serializes labels,
  rounds, best-label maps, or predecessor pointers.
- Debug controllers validate and project requests but contain no timetable
  scanning, RAPTOR round, or reconstruction algorithm.
- The frozen source files are not mutated by normal startup or tests.

## Known limitations and honest next boundaries

- Human review of Stage 1 is still pending.
- Stage 2 is implementation-complete but still pending human review.
- MTA and LIRR coexist, but there is no station-correspondence model or
  cross-feed transfer/routing logic.
- `POST /debug/raptor` exposes exact-stop, transit-only journeys; it is a debug
  boundary, not the final multimodal `/plan` product API.
- Reboarding currently has zero transfer slack and cannot cross between parent,
  child, nearby, or corresponding station stops.
- Pickup/drop-off semantics are enforced by the pattern scanner but remain
  absent from current trip debug JSON.
- Pickup/drop-off fields are not present in current trip debug JSON.
- Missing intermediate times are not interpolated.
- Transfers and shape geometry are ignored.
- The in-memory representation intentionally duplicates scheduled departures.
- There is no hot reload, database, cache file, or live update stream.
- The debug departure query is not a full next-day journey search.
- OTP is available as a separate baseline server but is not called by Stage 1.
- GraphHopper and all walking/sprinting logic are still absent.
- The frontend is only a package placeholder.

Stage 2A through Stage 2D2 are implementation-complete, pending human review.
Walking, physical transfer edges, station correspondence, GraphHopper, and the
multicriteria/sprint search remain deliberately deferred to later phases.
