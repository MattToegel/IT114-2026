# Chat Part 1

Minimum JDK: 26

Run from the personal repository root. Preserve earlier lesson work and follow the presentation's focused changes before comparing this completed checkpoint.

```bash
javac M4/MCCS/Part1/*.java
java M4.MCCS.Part1.Server
# Separate client terminals:
java M4.MCCS.Part1.Client
```

Use `/connect localhost:3000`. Chat reaches both clients; `/users` requests a reply only for the requesting client. Use `/disconnect` to close one connection and `/quit` to exit the client.

# Presence And Cleanup

Reference checkpoint for Fall 2026 IT114. Use JDK26 or later and run from your personal repository root. Client and server must use the same checkpoint's shared classes. Preserve your earlier lesson work; follow the presentation's focused edits before comparing this complete reference.

```bash
javac M4/MCCS/Part2/*.java
java M4.MCCS.Part2.Server
# A separate terminal:
java M4.MCCS.Part2.Client
```

Use /name <name>, then /connect <host>:<port>. Use /users to check the server-maintained local list.

Default port:3000. The server accepts an optional port argument; the client uses /connect localhost:3000 for a server on the same computer.

Part 2 continues Part 1 in `M4/MCCS/Part2`. It adds typed messages, identity and presence. The package/base-class refactor belongs to the later project-preparation lesson.
