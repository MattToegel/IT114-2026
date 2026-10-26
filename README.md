# Ready Check: First Game Session

Local Fall 2026 IT114 reference checkpoint. Continue `project-prep` without replacing its flat `Client`, `Server`, or `ServerThread`. This is a small participation/lifecycle example, not a project-option solution or a verified remote branch.

From this checkpoint's root, use JDK 26 or later:

```bash
./build.sh Project
./run.sh Project server
# In two other terminals:
./run.sh Project client
```

Each client enters `/name Alice` (choose a different name per client), `/connect localhost:3000`, then `/ready`. The first ready request opens a 30-second window. Two or more ready clients start an eight-second demonstration session; a newcomer during play watches as a spectator. `/reset` from a participating client clears the session. A duplicate `/ready` does not add another participant. The default chat, reverse, presence, and reconnect commands still work.

`Server` owns one synchronization monitor for client requests, disconnects, and timer callbacks. `GameServer` owns phase/readiness state and invalidates stale deadlines with a generation number. Failed game sends are collected until the current game transition finishes, then their connections are removed. No base-server hierarchy, rooms, or separate Command class is added.

Changed from `project-prep`: added `Project/Server/GameServer.java`, `Project/Common/Phase.java`, and `Project/Common/BoolPayload.java`; changed `Project/Common/PayloadType.java`, `Project/Client/Client.java`, `Project/Server/Server.java`, and `Project/Server/ServerThread.java`. The game methods introduced are `handleReady`, `handleReset`, `onGameTimer`, `onClientLeft`, and `syncTo`; client/server send and process methods carry the typed phase and ready-status payloads. The exact retired ready-check files are retained under `archive/authoring/2026-10-26/it114-downstream-code/ready-check/`.

Test-only JVM properties: `it114.readyMs` and `it114.sessionMs`. Student defaults above apply without overrides. See the repository's `scripts/check_it114_progression.mjs` for release-26 compile and multi-client checks.
