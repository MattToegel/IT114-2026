# Multi-Client Chat Server Part 2

Checkpoint: Client identity. Minimum JDK: 26.

Part 1 stays in `M4/MCCS/Part1`. Build Part 2 in the separate `M4/MCCS/Part2` package so the two versions can be compared.

## Run

From the repository root:

```bash
javac M4/MCCS/Part2/*.java
java M4.MCCS.Part2.Server
# In separate client terminals:
java M4.MCCS.Part2.Client
```

In each client, enter `/name Alice` (use a different name per client), then `/connect localhost:3000`. Send ordinary chat text or use `/reverse MiXeD`, `/users`, `/disconnect`, and `/quit`.

Check chat and reversal with two clients. The `/users` reply must reach only its requester.

## Lesson Progression

1. Typed chat: Payload, PayloadType, UserListPayload and TextFX
2. Identity: chosen names and server-assigned positive IDs
3. Presence: initial synchronization and join/leave updates

Each step is a separate commit on this branch. Payload data stays in send/process methods, and processPayload routes to the matching process method. ServerThread holds a User reference.

To run the terminal-color utility by itself: `java M4.MCCS.Part2.TextFX`.
