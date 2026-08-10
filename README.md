# sprintwise
Multimodal trip planner factoring in walking pace, including short sprints, to find faster transit routes. Built on OpenTripPlanner with resource constrained shortest path routing and GTFS realtime data.


## Setup 

### 0. Prerequisites
- Java 17+ and Maven (backend)
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