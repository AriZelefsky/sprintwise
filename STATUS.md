# SprintWise status

Last updated: 2026-08-13

## Where the project is now

The project has implemented **Stage 1D** of the routing plan. Stages 1A through
1C are committed; Stage 1D is implemented in the current working tree:

- **Stage 1A — GTFS ingestion boundary:** OneBusAway parses GTFS and maps it into
  immutable, feed-namespaced SprintWise models.
- **Stage 1B — service calendars and time resolution:** weekly schedules,
  `calendar_dates.txt` exceptions, explicit agency timezones, `24:xx` trips, and
  daylight-saving transitions are handled.
- **Stage 1C — in-memory timetable index:** stops, routes, trips, ordered stop
  times, trips serving stops, and binary-searched departures are indexed. The
  frozen MTA integration test completed under a 2 GiB heap limit.
- **Stage 1D — real-feed configuration and debug inspection:** the Spring Boot
  backend loads one configured frozen feed/index and provides read-only stop,
  departure, trip, and active-service inspection endpoints on port 8081 by
  default. Synthetic and optional real-MTA HTTP tests cover this boundary.

Latest Stage 1C commit:

```text
43a74d4 Add immutable GTFS timetable indexes with binary-searched departures,
deterministic schedule queries, synthetic tests, and frozen MTA memory measurements
```

Latest verification when this file was written:

- Normal Java 25 suite: 31 tests passed.
- Frozen-MTA integration profile: 2 integration tests passed.
- Frozen MTA snapshot: 1,488 stops, 20,621 trips, and 565,093 stop times.
- Approximate retained timetable-index heap: 103.2 MiB.
- Approximate index construction time: 0.37 seconds.

## Human review still required

Stages **1A through 1C need to be reviewed more closely by a human**. Their tests
pass, but the implementation should still be read carefully before it becomes the
foundation for routing. In particular, review the domain-model boundaries,
feed-scoped ID behavior, GTFS calendar/time semantics, departure-query behavior,
immutability, error handling, and memory duplication. Automated review should not
replace this human review.

## Next step: review Stage 1

Stage 1D should be committed after reviewing its diff. Then perform the closer
human review of Stages 1A–1C and run the full computerized Stage 1 audit below.
Do not begin Stage 2 until those reviews are complete or their remaining risks
are explicitly accepted.

## Bonus next task: full computerized Stage 1 audit

The debug endpoints are now implemented, so this audit is ready to run:

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
