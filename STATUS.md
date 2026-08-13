# SprintWise status

Last updated: 2026-08-13

## Where the project is now

The project is up to **Stage 1D** of the routing plan. Stages 1A through 1C are
implemented and committed:

- **Stage 1A — GTFS ingestion boundary:** OneBusAway parses GTFS and maps it into
  immutable, feed-namespaced SprintWise models.
- **Stage 1B — service calendars and time resolution:** weekly schedules,
  `calendar_dates.txt` exceptions, explicit agency timezones, `24:xx` trips, and
  daylight-saving transitions are handled.
- **Stage 1C — in-memory timetable index:** stops, routes, trips, ordered stop
  times, trips serving stops, and binary-searched departures are indexed. The
  frozen MTA integration test completed under a 2 GiB heap limit.

Latest Stage 1C commit:

```text
43a74d4 Add immutable GTFS timetable indexes with binary-searched departures,
deterministic schedule queries, synthetic tests, and frozen MTA memory measurements
```

Latest verification when this file was written:

- Normal Java 25 suite: 23 tests passed.
- Frozen-MTA integration profile: 1 integration test passed.
- Frozen MTA snapshot: 1,488 stops, 20,621 trips, and 565,093 stop times.
- Approximate retained timetable-index heap: 102.6 MiB.
- Approximate index construction time: 0.35 seconds.

## Human review still required

Stages **1A through 1C need to be reviewed more closely by a human**. Their tests
pass, but the implementation should still be read carefully before it becomes the
foundation for routing. In particular, review the domain-model boundaries,
feed-scoped ID behavior, GTFS calendar/time semantics, departure-query behavior,
immutability, error handling, and memory duplication. Automated review should not
replace this human review.

## Next implementation step: Stage 1D

Stage 1D adds **real-feed application configuration and debug inspection
endpoints**. It should turn the Stage 1 library code into a runnable Spring Boot
backend that loads the frozen MTA feed once at startup, constructs one immutable
`GtfsIndex`, and exposes read-only HTTP endpoints for inspecting:

- A stop by its feed-namespaced ID.
- The next departures at a stop for an explicit offset-bearing timestamp.
- A trip and its stop times in `stop_sequence` order.
- Active service IDs for an explicit service date.

Stage 1D should also add consistent JSON request errors, a configurable MTA feed
path, a configurable backend port that does not conflict with OTP on port 8080,
synthetic controller tests, and an optional frozen-MTA HTTP integration test. It
must use the same OneBusAway loader and `GtfsIndex` construction path as the
synthetic tests. It must not add routing, RAPTOR, transfers, GraphHopper, sprint,
rechargeability, data downloads, or OTP graph rebuilding.

Before beginning Stage 1D:

1. Read `docs/ROUTING-PLAN.md` and this file.
2. Confirm `git status` is clean.
3. Confirm Maven is running on Java 25.
4. Run `cd backend && mvn test`.
5. Complete or explicitly defer the closer human review of Stages 1A–1C.

## Bonus next task: full computerized Stage 1 audit

Run this after Stage 1D is implemented, because the audit includes the debug
endpoints and checks all of Stage 1 together:

```text
Audit and finish Stage 1 only.

Read docs/ROUTING-PLAN.md and compare every Stage 1 requirement against the implementation and tests. Inspect the full diff and existing golden fixtures.

Check specifically:
- OneBusAway is confined to the ingestion adapter.
- Application IDs are feed-namespaced.
- GTFS 24:xx and previous-service-day behavior is correct.
- calendar_dates overrides calendar.txt correctly.
- Feed timezone handling is explicit.
- Stop-time and departure indexes are deterministic and efficient.
- Synthetic and real data travel through the same production loader/index path.
- Debug endpoints do not contain routing logic.
- No download script, OTP graph script, RAPTOR, GraphHopper, sprint, or rechargeability behavior was accidentally added.
- Errors identify the source file/entity/ID sufficiently for debugging.

Fix any Stage 1 deficiencies you find, but do not begin Stage 2.

Run:
- The entire Java 25 Maven test suite.
- The real-data integration profile if the frozen snapshot exists.
- Any formatting or static-analysis checks already configured in the repository.

Update Stage 1 documentation to show:
- How to run synthetic tests.
- How to run the optional frozen-MTA integration test.
- How to start the backend.
- Example debug requests.
- What remains deliberately deferred to Stage 2.

Finish with a requirements checklist, test results, and any remaining risks.
```

## Important constraints to remember

- Use Java 25 throughout the backend and OTP tooling.
- Keep the current OSM and GTFS data as a frozen snapshot.
- Do not run `scripts/download-data.sh` during ordinary development.
- OneBusAway belongs only in the GTFS ingestion adapter.
- GraphHopper will own pedestrian paths and distances, not walking speed.
- SprintWise will own walking and sprint timing.
- Sprint is journey-wide and non-rechargeable for the MVP.
- OTP is a comparison baseline, not the custom search engine.
