# Swing UI Integration: Grid and Cards

Local Fall 2026 IT114 reference checkpoint. Continue the complete `cards` checkpoint; its server-owned turns, grid scoring, private hands, public counts, and typed late-join snapshots remain. This is a small Milestone 3 UI baseline for the teaching example, not a full project-option solution or a verified remote branch.

Use JDK 26 or later from this checkpoint's root:

```bash
./build.sh Project
./run.sh Project server
# In another terminal, choose one client entry:
./run.sh Project ui
./run.sh Project client
```

The Swing window asks for a name, host, and port before connecting. It shows readiness/phase/round, an approximate deadline, chat and game events, users with IDs and public scores/card counts, a private hand selector, and clickable grid cells. Select a card on your turn and click a cell. The server still validates every action and owns the timer expiry. Closing the window detaches its callback and closes only that client's socket; the terminal entry remains usable.

The window title uses `UCID` as a placeholder; replace it with your own UCID in `ClientUI.showWindow()`.

`Client` alone owns sockets and interprets payloads through `process*()`. The UI uses `ClientSnapshot` ordinary values and one `ClientListener` interface; it does not construct or inspect network payloads. Button actions enter one ordered background executor, while Swing components are created and updated on the EDT. Replacing/detaching the single listener prevents duplicate or stale delivery. Window cleanup captures the socket it observed so a delayed close cannot disconnect a later connection. `TIMER_SYNC` carries remaining milliseconds at schedule, reset, and join; the displayed countdown is approximate and never decides a turn or session outcome.

Changed from `cards`: added `Project/Client/ClientUI.java`, `ClientSnapshot.java`, and `ClientListener.java`; changed `Project/Client/Client.java`, `Project/Common/PayloadType.java`, `Project/Server/GameServer.java`, `run.sh`, and this README. The server change only sends `TIMER_SYNC`; no game rule or scoring method changed. The earlier `GridDemo` and terminal client remain.

Run `node scripts/check_it114_ui.mjs --java-home=<installed JDK26+>` from the repository root for release-26 compilation, headless Swing/callback/action-order checks, local multi-client protocol and late-join/private-hand checks, and a terminal connection check. The headless probe does not certify visible layout on a desktop display.
