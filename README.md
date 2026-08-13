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
- JDK 25 and Maven (backend and OTP; use Java 25 consistently rather than a different major version)
- Node.js and npm (frontend)
- `curl`, `unzip`, and `osmium-tool` (used by the data download script; install osmium with `brew install osmium-tool`)

### 1. Clone the repo
```bash
git clone https://github.com/AriZelefsky/sprintwise.git
cd sprintwise
```

### 2. Download map and transit data
```bash
./scripts/download-data.sh
```
This downloads the NY state OpenStreetMap extract temporarily, clips it to the NYC + Long Island metro area, and saves it as `data/nyc-metro.osm.pbf`. It also downloads MTA subway and LIRR GTFS feeds into `data/gtfs/`. See `data/README.md` for details on what's included.

### 3. Backend setup

On macOS with Homebrew, install and select the pinned JDK for the current shell:

```bash
brew install openjdk@25
export JAVA_HOME="$(brew --prefix openjdk@25)"
export PATH="$JAVA_HOME/bin:$PATH"
```

Confirm that both the shell and Maven are using Java 25:

```bash
java --version
mvn --version
```

Both commands must report Java 25. The repository's `.java-version`, Maven compiler target, and Maven Enforcer rule all pin this requirement.

```bash
cd backend
mvn install
```

Run the normal synthetic/unit suite with `mvn test`. To also build and measure the
complete frozen MTA timetable index in a separate Java process capped at 2 GiB,
run `mvn verify -Preal-mta-index`. The optional integration test skips itself when
`data/gtfs/mta/` is unavailable.

### 4. Frontend setup
```bash
cd frontend
npm install
```

### 5. Build and run OTP
```bash
./scripts/run-otp.sh      # one-time graph build (re-run when data changes)
./scripts/start-otp.sh    # start routing server at http://localhost:8080
```
