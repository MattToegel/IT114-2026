# Cards Integration: Play a Modifier on the Grid

Local Fall 2026 IT114 reference checkpoint. Continue `grid`, keeping its seeded 5x5 board, plus-pattern cell updates, odd-cell scoring, sequential turns, readiness, and reconnect snapshots. A card from the current player's private hand now supplies the modifier. This remains a small teaching model, not a complete assessed-game solution or a verified remote branch.

Use JDK 26 or later from this checkpoint's root. Keep `Project/Server/cards.txt` at its supplied path; the server loads it at session start:

```bash
./build.sh Project
java Project.Sandbox.GridDemo
./run.sh Project server
# In two other terminals:
./run.sh Project client
```

Name/connect at least two clients and send `/ready` from each. The current player draws up to three cards and sees only their own IDs with `/hand`. Card definitions (`id,modifier`) are public; each hand's size is public, but its IDs are private. Use `/card <cardId> <x> <y>` with an ID in your hand and zero-based grid coordinates. The server checks turn ownership, card ownership, catalog entry, and bounds before applying the card's modifier. It sends confirmed changed cells, scores affected odd cells, removes that card from the private hand, and broadcasts the new public count. A newcomer receives public catalog, counts, and grid, without another player's hand. `/gridtest` is intentionally removed from the active card stage; the grid checkpoint retains that example.

Changed from `grid`: added `Project/Common/Card.java`, `CardActionPayload.java`, `CardCatalogPayload.java`, `CardHandPayload.java`, `Project/Server/Deck.java`, and `Project/Server/cards.txt`; removed only the copied `Project/Common/GridActionPayload.java` (still present in the prior grid checkpoint); changed `Project/Common/PayloadType.java`, `Project/Client/Client.java`, `Project/Server/Server.java`, `ServerThread.java`, and `GameServer.java`. New active action path: `Client.sendCardAction` -> `ServerThread.processCardAction` -> `Server.handleCardAction` -> `GameServer.handleCardAction`. `Client.processCardCatalog`, `processCardHand`, and `processCardCount` receive state; `GameServer` sends the matching public/private snapshots. The earlier `GridDemo` is unchanged and remains runnable.

Normal ready and turn windows are 30 seconds. `it114.readyMs` and `it114.turnMs` are test-only JVM overrides. See `scripts/check_it114_progression.mjs` at the repository root for release-26 compile, sandbox, and multi-client checks.
