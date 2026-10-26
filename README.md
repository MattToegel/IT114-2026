# Grid Integration: A Small Plus-Pattern Example

Local Fall 2026 IT114 reference checkpoint. Continue `individual-turns`, preserving readiness, sequential turns, scores, reconnect snapshots, and the existing transport. The active number-guess action is replaced by a grid action; the earlier checkpoint remains available for comparison. This is a teaching model, not an assessed game's complete rules or a verified remote branch.

Use JDK 26 or later from this checkpoint's root:

```bash
./build.sh Project
java Project.Sandbox.GridDemo
./run.sh Project server
# In two other terminals:
./run.sh Project client
```

The standalone `GridDemo` prints a known seeded 5x5 grid, applies a center and a corner action, and reports changed-cell/odd counts without networking. In the networked example, name/connect two clients and send `/ready` from each. On the announced player's turn, use `/gridtest <x> <y> <value 1-9>` with zero-based coordinates; `/grid` prints the current confirmed client grid. The server applies the modifier to the target and in-bounds cardinal neighbors, wrapping cell values to 0–9 with `Math.floorMod`. It scores one point for each affected cell ending odd and sends only the changed cells. A newcomer receives seed/dimensions and a full authoritative cell snapshot. After three rounds the high score or tie is announced and the grid clears. `/guess` is intentionally absent from this stage.

Changed from `individual-turns`: added `Project/Common/Cell.java`, `Grid.java`, `CoordinatePayload.java`, `GridActionPayload.java`, `GridCellPayload.java`, `GridSeedPayload.java`, and `Project/Sandbox/GridDemo.java`; changed `Project/Common/PayloadType.java`, `Project/Client/Client.java`, `Project/Server/Server.java`, `ServerThread.java`, and `GameServer.java`. New active action path: `Client.sendGridAction` -> `ServerThread.processGridAction` -> `Server.handleGridAction` -> `GameServer.handleGridAction`. New client receivers `processGridSeed` and `processGridCell` apply server state; `GameServer` sends seed, changed cells, full joiner cells, and grid reset. `TURN_STATUS` replaces guess-specific status while the same turn lifecycle remains.

Normal ready and turn windows are 30 seconds. `it114.readyMs` and `it114.turnMs` are test-only JVM overrides. See `scripts/check_it114_progression.mjs` at the repository root for release-26 compile, sandbox, and protocol checks.
