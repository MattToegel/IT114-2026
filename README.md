# Milestone 2 Project Preparation

Reference checkpoint for Fall 2026 IT114 Milestone 2 prep on `Milestone2-Prep`. Milestone 1 requires the package-only copy of Part 2; this checkpoint additionally includes Singletons, scripts and logging. If `Project/` already exists from Milestone 1, preserve its files and apply the prep changes incrementally. Use JDK26 or later and run from your personal repository root. Client and server must use the same checkpoint's shared classes. Preserve your earlier lesson work; follow the presentation's focused edits before comparing this complete reference.

```bash
./build.sh Project
./run.sh Project server
# A separate terminal:
./run.sh Project client
```

Continue the completed Part 2 presence program in `Project`: ten copied Java classes plus `Project/Common/LoggerUtil.java`. Keep `UserListPayload`, the `ServerThread` `User` reference, typed `/users`, join/leave updates, and the existing send/process methods. `Client` and `Server` use enum `INSTANCE` entry points; no room API or extra base thread is added. Follow the presentation's copy, package, Singleton, script, and logging steps before comparing this complete checkpoint.

Default port:3000. The server accepts an optional port argument; the client uses /connect localhost:3000 for a server on the same computer.
