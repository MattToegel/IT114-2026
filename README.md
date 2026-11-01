# Ready Check: First Game Session

Fall 2026 IT114 reference checkpoint. Continue `project-prep` without replacing its package-organized `Client`, `Server`, or `ServerThread`. This is a small participation/lifecycle example, not a project-option solution.

From this checkpoint's root, use JDK 26 or later:

```bash
./build.sh Project
./run.sh Project server
# In two other terminals:
./run.sh Project client
```

Each client enters `/name Alice` (choose a different name per client), `/connect localhost:3000`, then `/ready`. The first ready request opens a 30-second window. Two or more ready clients start an eight-second demonstration session; a newcomer during play watches as a spectator. `/reset` from a participating client clears the session. A duplicate `/ready` does not add another participant. The default chat, reverse, presence, and reconnect commands still work.

`Server` owns one synchronization monitor for client requests, disconnects, and timer callbacks. `GameServer` owns phase/readiness state and invalidates stale deadlines with a generation number. Failed game sends are collected until the current game transition finishes, then their connections are removed. No base-server hierarchy, rooms, or separate Command class is added.

Changed from `project-prep`: added `Project/Server/GameServer.java`, `Project/Common/Phase.java`, and `Project/Common/BoolPayload.java`; changed `Project/Common/PayloadType.java`, `Project/Client/Client.java`, `Project/Server/Server.java`, `Project/Server/ServerThread.java`, and the readiness field/accessors in `Project/Common/User.java`. The game methods introduced are `handleReady`, `handleReset`, `onGameTimer`, `onClientLeft`, and `syncTo`; only Client and ServerThread construct or interpret payloads. Server routes ordinary values, and GameServer keeps User records rather than connection handlers.

Test-only JVM properties: `it114.readyMs` and `it114.sessionMs`. Student defaults above apply without overrides.

Run `/users` to see each known user as `[ready]` or `[not ready]`. Readiness changes only after the server accepts the request; reset clears it. Late joiners receive the current user, readiness and phase snapshots. The inherited ID-only leave notification remains unchanged.
