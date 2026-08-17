# SprintWise current architecture

(Reminder to hit command shift v to view in markdown view)

This document explains the repository as it exists after Stage 1. It is meant
to let my incoming partner @ Zach Rosenberg gain a thorough and deep understanding of the project as it stands.

Last checked against the working tree: 2026-08-16.

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

### How a future routing round will use these concepts

Yes: the future RAPTOR stage is expected to work roughly as follows.

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

Stage 2 is expected to derive a more compact **trip-pattern** or RAPTOR-specific
index from those verified structures. A trip pattern groups trips that share the
same ordered stop sequence, allowing many similar scheduled runs to be scanned
efficiently. That derived pattern is different from the feed's `Route`: one
route may require several patterns because its trips have different directions
or stop sequences.

Routes still matter, but mostly as stable grouping and descriptive information:

- Every trip must reference a valid route.
- Results can say which line/service the passenger rides.
- Route type can distinguish subway, rail, bus, and other modes.
- Future policies may filter or present routes differently.
- Route identity helps describe and reconstruct a journey.

The scheduled movement itself comes from a **Trip plus its ordered StopTimes**,
not from the Route record alone.

## The most important fact

SprintWise does **not** route journeys yet.

The completed Stage 1 backend can:

- Read independently configured MTA and LIRR static GTFS feeds.
- Convert parser-owned objects into immutable SprintWise-owned records.
- Validate stops, routes, trips, stop times, calendars, references, timezones,
  and pickup/drop-off values.
- Build an immutable in-memory timetable index.
- Resolve active services and departures, including `24:xx`, `49:xx`, and DST.
- Expose read-only debug HTTP endpoints for inspecting that data.

It cannot yet:

- Plan an origin-to-destination journey.
- Board a train and scan downstream stops with RAPTOR.
- Plan or connect a journey between the independent MTA and LIRR indexes.
- Use `transfers.txt`, shapes, OSM, GraphHopper, or the OTP graph in its own
  backend search.
- Perform access, transfer, or egress walking.
- Apply walking speed, sprinting, dominance, or recharge rules.
- Consume GTFS-Realtime.
- Persist the timetable in a database or reload it while the process runs.

The root `README.md` describes parts of the intended finished product, including
a custom routing algorithm and live data. Those statements are aspirational;
they do not describe the current Stage 1 implementation. For the planned build
sequence, use `docs/ROUTING-PLAN.md`.

## Architecture at a glance

```text
data/gtfs/mta/                         data/gtfs/lirr/
      |                                      |
      +----------- OneBusAwayGtfsLoader -----+
                          |
              parse, map, namespace, validate
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
                          |
                   DebugController
             namespace selects exactly one index
                          |
          JSON response / Problem Details error
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
9. A structured `GtfsLoadException` affects only that feed. Its entry retains
   one `FeedUnavailableException`, so requests for it return HTTP 503 while
   other entries remain usable. Unexpected programming/configuration failures
   still abort startup instead of being mislabeled as bad feed data.
10. Disabled feeds do not enter the catalog and have the same 404 contract as
    unknown feed namespaces.

This is eager startup loading, but it is deliberately failure-tolerant at the
web-application boundary.

## GTFS datapoint lifecycle

### The boundary rule

OneBusAway is an input parser, not SprintWise's domain model. Its classes are
used only in `com.sprintwise.gtfs.onebusaway`. Before a feed leaves that
adapter, each retained value is copied into a SprintWise record.

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

It checks:

- The source path exists.
- The feed declares exactly one nonblank valid agency timezone.
- Required IDs exist.
- Parent-station, route, service, trip, and stop references resolve.
- Calendar exception types are supported.
- Every trip has at least two stop times.
- `stop_sequence` is nonnegative, unique, and strictly increasing after sorting.
- First and last stop times contain both arrival and departure.
- An intermediate stop has either both times or neither time.
- A departure is not earlier than its arrival at the same stop.
- Known times do not move backward as the trip proceeds.
- Pickup and drop-off values are in the GTFS range `0..3`.

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
- `invalid_stop_time`
- `invalid_pickup_drop_off_type`
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

`TransitFeedCatalog` is a multi-feed **container**, but there is deliberately no
combined MTA+LIRR `GtfsIndex`. It does not infer that equally named or nearby
stops correspond, and it does not create transfers. Feed-namespaced IDs let
both datasets coexist without collisions; station matching and cross-feed
routing remain later work.

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

### Journey lifecycle

There is no actual journey object or journey lifecycle yet. There are no
`Journey`, `Leg`, `Itinerary`, `Label`, `Raptor`, `FootpathService`, or `/plan`
production types. The only resolved transit event is a single departure from a
single stop. Stage 2 is expected to introduce ride scanning and trip-pattern or
compact RAPTOR-specific structures after human review of Stage 1.

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
| `backend/src/main/java/com/sprintwise/debug/` | Read-only Stage 1 HTTP endpoints, DTOs, and HTTP error translation |
| `backend/src/main/java/com/sprintwise/gtfs/` | Parser-neutral loading interface, diagnostics, and load exception |
| `backend/src/main/java/com/sprintwise/gtfs/onebusaway/` | The only production boundary allowed to depend on OneBusAway |
| `backend/src/main/java/com/sprintwise/gtfs/calendar/` | Active-service calculation |
| `backend/src/main/java/com/sprintwise/gtfs/time/` | GTFS service time/date/instant conversion |
| `backend/src/main/java/com/sprintwise/model/` | Immutable SprintWise transit-domain records |
| `backend/src/main/java/com/sprintwise/index/` | Immutable lookup structures and resolved departure types |
| `backend/src/main/java/com/sprintwise/service/` | Application-lifetime catalog of independent feed/index snapshots or failures |
| `backend/src/main/resources/` | Runtime Spring configuration |
| `backend/src/test/` | Backend test code and test-only data |
| `backend/src/test/java/` | JUnit Java source root |
| `backend/src/test/java/com/sprintwise/` | Test package root mirroring production packages |
| `backend/src/test/java/com/sprintwise/debug/` | Synthetic controller tests and frozen-MTA API integration test |
| `backend/src/test/java/com/sprintwise/golden/` | Integrity checks for synthetic and normalized golden artifacts |
| `backend/src/test/java/com/sprintwise/gtfs/` | GTFS test hierarchy |
| `backend/src/test/java/com/sprintwise/gtfs/onebusaway/` | Loader, mapping, validation, and diagnostic tests |
| `backend/src/test/java/com/sprintwise/gtfs/calendar/` | Weekly and exception-calendar tests |
| `backend/src/test/java/com/sprintwise/gtfs/time/` | Midnight, maximum-span, timezone, and DST tests |
| `backend/src/test/java/com/sprintwise/index/` | Timetable index unit tests and real-feed memory integration test |
| `backend/src/test/java/com/sprintwise/service/` | Multi-feed catalog, failure-isolation, and combined-real-feed tests |
| `backend/src/test/resources/` | Test inputs copied onto the Maven test classpath |
| `backend/src/test/resources/fixtures/` | Parent for owned synthetic fixtures |
| `backend/src/test/resources/fixtures/synthetic-gtfs/` | Tiny valid GTFS feed plus future mock walking data |
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
| `backend/pom.xml` | Defines Java/Spring/OneBusAway plus isolated MTA, LIRR, and combined-catalog real-data profiles |
| `backend/src/main/resources/application.yml` | Sets port 8081 and independent MTA/LIRR paths and enabled flags |
| `backend/src/main/java/com/sprintwise/SprintWiseApplication.java` | Spring Boot entry point and component-scan root |

### Configuration and service ownership

| File | What it does |
|---|---|
| `config/GtfsProperties.java` | Spring binding object for the list of feed IDs, paths, and enabled flags |
| `config/TransitConfiguration.java` | Creates the parser-neutral loader and singleton `TransitFeedCatalog` |
| `service/TransitFeedCatalog.java` | Validates configuration, loads enabled feeds once, sorts entries, and dispatches feed/index access |
| `service/TransitFeedEntry.java` | Immutable available feed/index pair or retained unavailable-feed state, including construction timing |
| `service/UnknownFeedException.java` | Signals the documented unknown-or-disabled feed contract |
| `service/FeedUnavailableException.java` | Carries feed ID, source path, cause, and optional structured diagnostic after startup loading fails |

### GTFS boundary and diagnostics

| File | What it does |
|---|---|
| `gtfs/GtfsLoader.java` | Parser-neutral `load(Path, feedId)` interface |
| `gtfs/onebusaway/OneBusAwayGtfsLoader.java` | Reads the directory, maps every retained entity, namespaces IDs, validates relationships/timetables, and creates structured failures |
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

### Debug HTTP layer

| File | What it does |
|---|---|
| `debug/DebugController.java` | Implements stop, departure, trip, and active-service inspection endpoints and validates request syntax |
| `debug/DebugApiExceptionHandler.java` | Converts request, not-found, and feed failures into HTTP Problem Details JSON |
| `debug/DebugBadRequestException.java` | Internal request-validation exception with a stable code |
| `debug/DebugNotFoundException.java` | Internal unknown-resource exception |
| `debug/StopDebugResponse.java` | JSON projection of a `Stop` |
| `debug/DepartureDebugResponse.java` | JSON projection of a concrete `TimetableDeparture` |
| `debug/TripDebugResponse.java` | JSON projection of a `Trip` plus ordered stop times |
| `debug/StopTimeDebugResponse.java` | Current limited JSON projection of stop-time identity/sequence/times |
| `debug/ActiveServicesDebugResponse.java` | JSON projection of sorted services active on one date |

### Test classes

| File | What it proves |
|---|---|
| `golden/GoldenFixtureIntegrityTest.java` | Synthetic network shape, after-midnight data, mock footpath math, and real golden case IDs remain intact |
| `gtfs/onebusaway/OneBusAwayGtfsLoaderTest.java` | Production-path loading, relationships, namespacing, ordering, >24-hour values, pickup/drop-off values, five-stop trips, validation, and structured diagnostics |
| `gtfs/calendar/ServiceCalendarResolverTest.java` | Weekday/weekend rules and exception additions/removals |
| `gtfs/time/ServiceTimeResolverTest.java` | Agency timezone, `24:xx`, feed-derived `49:xx` window, spring/fall DST, and bounded date candidates |
| `index/GtfsIndexTest.java` | Deterministic entity order, daytime/after-midnight/49-hour lookup, calendar filtering, tie-breaking, empty contracts, and immutability |
| `service/TransitFeedCatalogTest.java` | Two-feed collisions, ordering, immutability, one-time loading, disabled feeds, and failure isolation |
| `service/CombinedFrozenFeedsCatalogIT.java` | Simultaneous frozen MTA/LIRR indexes fit under 2 GiB and preserve namespace isolation |
| `debug/DebugControllerTest.java` | All debug endpoints and their 400/404/503 JSON contracts using synthetic production loading |
| `index/RealMtaIndexSizeIT.java` | Frozen MTA fits under `-Xmx2G` and reports entity, time, heap, and duplication measurements |
| `index/RealLirrStage1CompatibilityIT.java` | Frozen LIRR loader/index compatibility, extended-time behavior, references, and memory measurements |
| `debug/RealMtaDebugApiIT.java` | Known real stop, departure, trip, and service lookups work through Spring HTTP components |

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
- Debug controllers inspect data but contain no routing algorithm.
- The frozen source files are not mutated by normal startup or tests.

## Known limitations and honest next boundaries

- Human review of Stage 1 is still pending.
- MTA and LIRR coexist, but there is no station-correspondence model or
  cross-feed transfer/routing logic.
- No trip-pattern or compact integer/array RAPTOR index exists yet.
- Pickup/drop-off semantics are retained but not applied to a routing decision.
- Pickup/drop-off fields are not present in current trip debug JSON.
- Missing intermediate times are not interpolated.
- Transfers and shape geometry are ignored.
- The in-memory representation intentionally duplicates scheduled departures.
- There is no hot reload, database, cache file, or live update stream.
- The debug departure query is not a full next-day journey search.
- OTP is available as a separate baseline server but is not called by Stage 1.
- GraphHopper and all walking/sprinting logic are still absent.
- The frontend is only a package placeholder.

Stage 2 should begin only after the pending human review is completed or its
remaining risks are consciously accepted.
