# Multi-Client Chat Server Part 2

Minimum JDK: 26. Part 1 remains unchanged in `M4/MCCS/Part1`; Part 2 uses `M4/MCCS/Part2`.

Starting checkpoint: a copy of Part 1 with only package/import names changed.

## Run

From the repository root:

```sh
javac M4/MCCS/Part2/*.java
java M4.MCCS.Part2.Server
# In separate terminals:
java M4.MCCS.Part2.Client
```

Enter `/connect localhost:3000` in each client. Send chat text or use `/reverse MiXeD`, `/users`, `/disconnect`, and `/quit`.
