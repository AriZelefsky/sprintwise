# Synthetic GTFS fixture

This tiny feed creates exact-answer routing cases that may not occur reliably in the frozen NYC schedule.

Network shape:

```text
START --240m--> A --RED--> B_RED --120m--> B_BLUE --BLUE--> C --180m--> END
                     \----------------DIRECT----------------/
```

`mock-graphhopper-footpaths.csv` is a SprintWise test fixture, not a GTFS file. Tests inject it in place of GraphHopper. With `vWalk=1 m/s` and `vSprint=3 m/s`, its paths have simple exact durations.

The feed also contains:

- `NIGHT`, whose times are greater than `24:00:00`.
- `SPECIAL_ONLY`, enabled only by `calendar_dates.txt` on 2026-08-13.
- `WEEKEND_ONLY`, enabled by the weekend service calendar.
- `WEEKDAY` service removed by `calendar_dates.txt` on Tuesday, 2026-08-18.

The August fixture does not cross a daylight-saving transition. SprintWise follows
GTFS time semantics on transition days: service-day time zero is local noon minus
twelve elapsed hours in the agency timezone. Dedicated unit tests cover both New
York clock changes without making the synthetic feed span additional months.

Expected journeys are documented in `docs/golden-queries.md`.
