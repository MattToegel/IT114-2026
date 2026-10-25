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

This branch supplies the lesson baseline only. Copy the five Java files into `M4/MCCS/Part1HW` and change their package declarations as directed by the worksheet before adding your own homework solutions.
