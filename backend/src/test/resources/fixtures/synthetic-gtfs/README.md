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

Expected journeys are documented in `docs/golden-queries.md`.
