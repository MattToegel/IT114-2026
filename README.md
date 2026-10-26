# Individual Turns: Same Number Guesser

Local Fall 2026 IT114 reference checkpoint. Continue `simultaneous-turns` with the same three-round number guesser and scoring rule. Only turn order changes; this is not a full assessed-game solution or a verified remote branch.

Use JDK 26 or later from this checkpoint's root: `./build.sh Project`; start `./run.sh Project server`, then `./run.sh Project client` in two other terminals. Name/connect two clients, send `/ready` from each, and wait for the 30-second ready window. The server announces the current player by ID. Only that player may use `/guess <1-10>`; a second action in the same round is rejected. Players act in increasing client-ID order. A 30-second turn timeout records a skipped turn, without awarding a guessed-number point. If the current player disconnects, the next eligible player receives the turn. Late joiners receive the current turn, phase, round, public statuses and scores; private guesses stay private. `/reset` clears the session, and normal chat still works.

`GameServer.SessionPlayer` is server-only round state. It does not change the existing public `User` identity or `UserListPayload`.

Changed from `simultaneous-turns`: changed only `Project/Common/PayloadType.java`, `Project/Client/Client.java`, and `Project/Server/GameServer.java`. Added `CURRENT_TURN` and `Client.processCurrentTurn`; `GameServer.onTurnStart`, `onTurnEnd`, `onTurnTimeout`, `sendCurrentTurnTo`, and `sendCurrentTurnToAll` select and synchronize the current participant. `handleGuess` now checks turn ownership and one action per round. Earlier send/process transport methods and ready/round lifecycle remain.

Test-only JVM properties: `it114.readyMs`, `it114.turnMs`, and `it114.seed`. Defaults use random hidden numbers and normal 30-second windows. See `scripts/check_it114_progression.mjs` at the repository root for release-26 compile and protocol checks.
