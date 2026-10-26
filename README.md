# FindBlock 1.4.0 (Paper 1.21.11)

FindBlock searches for the nearest occurrence of one block or any of several block types. It supports nearby searches, full-world scans of saved chunks, resumable progress, configurable performance limits, clickable coordinate copying, result history, optional teleport, permissions, and admin commands.

## Requirements
- Paper 1.21.11
- Java 21
- Build with Maven (`mvn package`)

The compiled plugin JAR will be in `target/FindBlock-1.4.0.jar`.

## Commands
- `/findblock <block> [radius]` — search loaded chunks within the radius (default 128, max from config).
- `/findblock <block1,block2,...> [radius]` — find the nearest of several block types.
- `/findblock world <block[,block...]>` — scan existing saved chunks in the current world. This can take a long time on large worlds.
- `/findblock resume` — resume your saved full-world search after disconnect/restart.
- `/findblock cancel` — cancel the current search and delete its checkpoint.
- `/findblock history` — show recent results; click coordinates to copy them.
- `/findblock tp` — teleport to the most recent result (requires `findblock.tp`).
- `/findblock admin reload` — reload `config.yml`.
- `/findblock admin cancel <player>` — cancel another player's active search.
- `/findblock help` — show command help.

Material names are Bukkit material IDs, e.g. `stripped_cherry_log`, `diamond_ore`, `chest`. Separate multiple types with commas and no spaces is easiest, e.g. `chest,barrel`.

## Permissions
- `findblock.use` — basic commands (default: everyone)
- `findblock.world` — full-world search and resume (default: op)
- `findblock.tp` — teleport to last result (default: op)
- `findblock.admin` — config reload and cancel other players' searches (default: op)

## Performance and safety notes
- Nearby mode checks only chunks already loaded at the start; it does not load or generate additional chunks.
- Full-world mode reads region-file headers and visits only chunks with saved entries. It does not generate new terrain. A scan can still take a long time and use significant server resources.
- Tune `settings.chunk-delay-ticks` and `settings.progress-every-chunks` in `config.yml`. Increasing the delay lowers scan throughput and can reduce impact on the server.
- Checkpoints are saved under `plugins/FindBlock/` so full-world searches can be resumed. Do not manually edit them while a search is running.
- Test on a backup or disposable world before using on a large production server.

## Build
```bash
mvn clean package
```

This ZIP is the complete **source project**. It is not a precompiled plugin JAR. Compile it with Maven and install the JAR from `target/` into your Paper server's `plugins` folder.
