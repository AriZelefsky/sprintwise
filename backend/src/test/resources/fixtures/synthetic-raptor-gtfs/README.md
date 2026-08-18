# Synthetic transit-only RAPTOR fixture

This GTFS-format fixture exercises the Stage 2 exact-stop debug endpoint through
the production OneBusAway loader, Stage 1 indexes, composite RAPTOR network,
round router, journey reconstructor, application service, and HTTP controller.

```text
DIRECT_FIVE: A -> P -> Q -> R -> E
LEG_1:       A -> B
LEG_2:            B -> C
LEG_3:                 C -> D
NIGHT:       A ------------> C  (24:05 to 24:15)

ISOLATED has no transit service.
```

`LEG_1`, `LEG_2`, and `LEG_3` intentionally share one route but remain three
different trips and therefore three different journey legs. The one-minute
gaps at B and C are waits encoded by adjacent leg timestamps, not extra edges.
There are no walks, parent-station links, or cross-feed transfers.
