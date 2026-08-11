# sprintwise
SprintWise is a multimodal trip planner built around a simple idea: transit directions should account for how fast you actually walk, and that sometimes running for a quick minute can save you a whole lot more than a minute.

Most mapping apps assume everyone walks at roughly the same pace. SprintWise lets you choose your actual walking speed, then uses it to find routes across walking and public transit that better match how you really move.

But the fun part is what happens when a route is almost possible. SprintWise can recognize when a short sprint to a station, bus stop, or transfer point could get you onto a much earlier train or bus. If running for 60 or 90 seconds can shave several minutes off the trip, it will tell you.

The goal is not to make every route a workout. SprintWise specifically looks for the moments where a little extra effort has an outsized payoff.

Maybe the normal route has you walking to the subway, missing a train by a minute, and waiting eight minutes for the next one. SprintWise might instead tell you to pick up the pace for a few blocks, catch the first train, and get to your destination noticeably sooner.

Under the hood, we built the routing engine largely from scratch. Streets, stops, transfers, and transit connections are modeled as a multimodal graph, and routes are found using a modified Dijkstra-style shortest path algorithm. We extended the normal shortest path problem into a resource-constrained one, so the algorithm can reason about walking at different speeds, limited amounts of sprinting, scheduled departures, and whether spending some of that sprint time now unlocks a significantly faster route later. GTFS and GTFS-Realtime data supply the transit network, schedules, and live updates, while our own routing logic determines how to actually get from point A to point B.

Ever got stuck waiting 59 minutes for the next train, wishing Google Maps would have just told you to sprint to catch the train that departed moments ago? Splitwise is there for you.


## Setup 

### 0. Prerequisites
- Java 25+ and Maven (backend)
- Node.js and npm (frontend)
- `curl` and `unzip` (used by the data download script)

### 1. Clone the repo
```bash
git clone https://github.com/AriZelefsky/sprintwise.git
cd sprintwise
```

### 2. Download map and transit data
```bash
./scripts/download-data.sh
```
This downloads the OpenStreetMap extract for New York state and the MTA subway GTFS feed into `data/`. See `data/README.md` for details on what's included.

### 3. Backend setup
```bash
cd backend
mvn install
```

### 4. Frontend setup
```bash
cd frontend
npm install
```

### 5. Run
*(fill in once you have actual run commands for OTP + backend + frontend)*