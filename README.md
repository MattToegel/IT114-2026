# Multi-Client Chat Server Part 2

Minimum JDK: 26. Part 1 remains unchanged in `M4/MCCS/Part1`; Part 2 uses `M4/MCCS/Part2`.

Completed lesson: typed chat, chosen names, positive server-assigned IDs, synchronized user lists and TextFX.

## Run

From the repository root:

```sh
javac M4/MCCS/Part2/*.java
java M4.MCCS.Part2.Server
# In separate terminals:
java M4.MCCS.Part2.Client
```

Enter `/name Alice` (choose a different name in each client), then `/connect localhost:3000`. Send chat text or use `/reverse MiXeD`, `/users`, `/disconnect`, and `/quit`.

Connect a third client and compare all `/users` lists. Disconnect a client, verify one leave update, then reconnect.

Only Client and ServerThread construct, read or transport payloads. Server handles client intent and selects recipients using ordinary values, User and List<User>. ServerThread has a User reference.

## History

Part 1 history is preserved. The first two Part 2 commits show the package-only copy and the completed lesson; later commits contain lesson corrections. The earlier three-stage branch is preserved in `Spring-Module05-Sockets-MCCS-Part2-before-responsibility-fix-20261026`.

[TextFX.java](https://github.com/MattToegel/IT114-2026/blob/Module05-Sockets-MCCS-Part2/M4/MCCS/Part2/TextFX.java) provides terminal colors. Run its utility demo with `java M4.MCCS.Part2.TextFX`.
