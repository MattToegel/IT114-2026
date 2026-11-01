# Simultaneous Turns: Number Guesser

Fall 2026 IT114 reference checkpoint. Continue `scoring`; its transport, user identity, readiness, and timer ownership remain. This number-guesser example teaches simultaneous rounds, not any assessed project's full rules.

Use JDK 26 or later from this checkpoint's root: `./build.sh Project`; start `./run.sh Project server`, then `./run.sh Project client` in two other terminals. Each client uses `/name <name>`, `/connect localhost:3000`, and `/ready`. The normal ready window and round limit are 30 seconds each. During a round, each participating player sends `/guess <1-10>`. A guess is private until evaluation. A player may replace a guess while the round remains open; only the first submission changes the public acted status. The round closes when all participants have guessed or the timer expires. An exact match scores one point. After three rounds, the server announces the highest score and any tie, then clears state for another session. `/reset` also clears state. Late joiners receive phase, round, public statuses and scores, without another player's guess.

`GameServer.SessionPlayer` holds private guesses on the server. The existing `User` in `ServerThread` holds public identity, readiness, submitted status, and points; `UserListPayload` never carries a guess.

Changed from `scoring`: add round phases, guess/confirmation/status/round message types and client handlers. Keep the existing `IntPayload`, point synchronization, and `/users` score display. `GameServer` replaces the practice start bonus with one point per correct guess at round evaluation. The action path is `Client.sendGuess` -> `ServerThread.processGuess` -> `Server.handleGuess` -> `GameServer.handleGuess`. Round lifecycle methods open, evaluate, and finish the game.

Test-only JVM properties: `it114.readyMs`, `it114.roundMs`, and `it114.seed` (deterministic hidden numbers). Defaults use random hidden numbers and the normal windows.
