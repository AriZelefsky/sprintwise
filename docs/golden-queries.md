# SprintWise golden tests

Golden tests run a fixed request and compare the result with a reviewed expected result. They are regression tests: when routing code changes, an unexpected journey change is surfaced for inspection instead of silently becoming the new behavior.

SprintWise uses two complementary golden suites.

## 1. Frozen NYC snapshot

These cases exercise real MTA subway, LIRR, station, and street data. OTP 2.9 is the initial comparison oracle. We store a small normalized result rather than comparing raw OTP JSON byte-for-byte, because debug metadata and irrelevant response fields may change without changing the journey.

Hard assertions for real-data cases:

- A journey either exists or intentionally does not exist.
- Every boarded trip is active on the requested service date.
- Boarding and alighting stops occur in legal order.
- Transfers and endpoint modes are plausible.
- Arrival stays within the case's tolerance unless a reviewed update changes the golden result.

Reference assertions, such as an exact route sequence, may require review rather than automatic rejection after the custom router begins finding valid alternatives that OTP did not return.

The normalized OTP baseline is stored at `backend/src/test/resources/golden/otp-real-baseline.json`.

| ID | Date/time | Origin → destination | Purpose | OTP 2.9 reference |
|---|---|---|---|---|
| `NYC-01` | Thu 2026-08-13 08:00 | Van Cortlandt Park → Times Sq | Direct subway | 1 train, 08:02–08:43, 0 transfers |
| `NYC-02` | Thu 2026-08-13 08:00 | Flushing-Main St → World Trade Center | Subway transfer with an indoor walk | 7X → walk → E, 08:05:30–08:54 |
| `NYC-03` | Thu 2026-08-13 10:00 | Coney Island → Flushing-Main St | Long, multi-transfer subway trip | Q → B → F → walk → 7, 10:03–11:39 |
| `NYC-04` | Thu 2026-08-13 10:00 | Rockaway Park → Times Sq | Shuttle, mainline, and egress walk | S → A → walk, 10:09–11:47:12 |
| `NYC-05` | Sat 2026-08-15 10:00 | Inwood → Atlantic Av-Barclays Ctr | Weekend calendar and coordinate access | Walk → 1 → 2 → walk, 10:02:05–11:07:01 |
| `NYC-06` | Sat 2026-08-15 00:05 | Union Sq → Atlantic Av | After-midnight subway service | L, 00:13–00:43 |
| `NYC-07` | Thu 2026-08-13 08:00 | Penn Station → Jamaica | Direct LIRR | Rail, 08:05–08:26 |
| `NYC-08` | Thu 2026-08-13 08:00 | Long Beach → Penn Station | LIRR branch | Rail, 08:24–09:16 |
| `NYC-09` | Thu 2026-08-13 08:00 | Montauk → Penn Station | Infrequent long-distance LIRR with a wider search window | Rail → Rail at Jamaica, 11:29–14:53 |
| `NYC-10` | Thu 2026-08-13 08:00 | Ronkonkoma → Times Sq | LIRR-to-subway inter-feed transfer | Rail → walk → 2, 08:23–09:53 |

These dates are deliberately inside the frozen feeds. Do not update them merely because newer public schedules become available.

## 2. Synthetic exact-answer network

The miniature feed at `backend/src/test/resources/fixtures/synthetic-gtfs/` is not intended to resemble the complete subway. It creates situations whose correct answer is known exactly. `mock-graphhopper-footpaths.csv` replaces GraphHopper in these tests so street routing cannot make the result nondeterministic.

Shared speed configuration:

- `vWalk = 1 m/s`
- `vSprint = 3 m/s`
- Access `START → A` is 240 m: 240 seconds walking or 80 seconds sprinting.
- Transfer `B_RED → B_BLUE` is 120 m: 120 seconds walking or 40 seconds sprinting.
- Egress `C → END` is 180 m: 180 seconds walking or 60 seconds sprinting.

| ID | Request | Exact expected result | Behavior proved |
|---|---|---|---|
| `SYN-01` | `START → END`, Wed 2026-08-12 07:56:30, sprint budget 0 | `RED_LATE → BLUE_LATE`, arrive 08:33:00, sprint 0 s | Basic access, transfer, egress, and timetable search |
| `SYN-02` | Same request, sprint budget 120 s | Sprint access 80 s and transfer 40 s; `RED_EARLY → BLUE_EARLY`; arrive 08:18:50 | Sprint catches two earlier trains and saves 14m10s |
| `SYN-03` | Same request, sprint budget 100 s | Selected result is the no-sprint `SYN-01` result | Budget does not recharge on the first ride; an 80 s access sprint cannot fund the 40 s transfer sprint |
| `SYN-04` | `A → END`, Thu 2026-08-13 08:01:30, sprint budget 60 s, minimum payoff 5 min | `DIRECT_SLOW`, choose normal egress, arrive 08:28:00 | A 60 s egress sprint saves only 2 minutes and is not recommended |
| `SYN-05` | `START → B_RED`, Wed 2026-08-12 07:55:00, sprint budget 120 s | `RED_EARLY`, arrive 08:05:00, sprint 0 s | Sprint and walk catch the same train, so the higher-sprint label is dominated after boarding |
| `SYN-06` | `A → C`, service date Thu 2026-08-13 at 23:59 | `NIGHT`, depart at GTFS 24:05 and arrive at GTFS 24:15 / civil Fri 00:15 | GTFS times greater than 24:00 |
| `SYN-07` | `A → C`, Thu 2026-08-13 at 11:59 | `SPECIAL_ONLY`, arrive 12:10 | `calendar_dates.txt` addition is honored |
| `SYN-08` | `A → C`, Fri 2026-08-14 at 11:59 | No `SPECIAL_ONLY` trip | Exception-only service does not leak to other dates |
| `SYN-09` | `A → C`, Sat 2026-08-15 at 09:59 | `WEEKEND_ONLY`, arrive 10:15 | Weekend calendar filtering |

## How these become executable

The cases exist before the engine so every phase has an agreed target. They become automated incrementally:

1. Phase 1 loads the synthetic GTFS and asserts calendars, departures, and `24:xx` parsing.
2. Phase 2 asserts the ride-only portions and journey backtrace.
3. Phase 3 injects `mock-graphhopper-footpaths.csv` and asserts access, transfer, and egress timing without GraphHopper.
4. Phase 6 enables the sprint assertions and label dominance cases.
5. The real NYC suite remains an integration/regression suite against the frozen data.

Never regenerate expected results automatically as part of a normal test run. A changed result must be inspected and explicitly accepted.
