# CLAUDE.md — civai-npc

This file gives AI assistants full context on this project so they can contribute effectively without re-explaining the setup every session.

---

## What This Is

A **Paper Minecraft plugin** (Java) that spawns AI-powered NPCs driven by a **local Ollama instance**. No cloud APIs. The NPC observes its surroundings, sends a world state snapshot to Ollama every 10 seconds, receives a JSON action back, and executes it in-game.

---

## Infrastructure

| Component | Details |
|-----------|---------|
| Proxmox host | Homelab, multiple LXC containers |
| Crafty Controller LXC | IP: `192.168.1.102`, Debian 13, hosts the Minecraft servers |
| Ollama LXC | IP: `192.168.1.104`, model: `llama3:latest` (8B Q4_0) |
| Minecraft Server 1 (cherry core) | Paper 1.21, port `6969`, UUID `da5eee84-3052-4a5f-9f07-9c126b40022f` — **this is where the plugin runs** |
| Minecraft Server 2 | Paper 1.21.1, port `25566`, UUID `25cc214e-3a14-4010-8917-b7565e55f8da` |
| Crafty web UI | `http://192.168.1.102:8000` |
| Dynmap server 1 | `http://192.168.1.102:7000` |
| Dynmap server 2 | `http://192.168.1.102:8123` |

---

## Build Environment

- **Java**: Temurin 21 JDK at `/usr/lib/jvm/temurin-21-jdk-amd64`
- **Maven**: 3.9.9 at `/usr/share/maven`
- **JAVA_HOME must be set**: `export JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64`
- **Build command**: `cd /opt/civai-npc && mvn package`
- **Output jar**: `target/ai-npc-1.1.2.jar`
- **Deploy**: `cp target/ai-npc-1.1.2.jar /opt/crafty-controller/crafty/crafty-4/servers/da5eee84-3052-4a5f-9f07-9c126b40022f/plugins/`

---

## Plugin File Locations

| File | Path |
|------|------|
| Source | `/opt/civai-npc/` |
| Plugin config (runtime) | `/opt/crafty-controller/crafty/crafty-4/servers/da5eee84-.../plugins/ai-npc/config.yml` |
| Plugin jar (deployed) | `/opt/crafty-controller/crafty/crafty-4/servers/da5eee84-.../plugins/ai-npc-1.0.0.jar` |
| Server 1 plugins dir | `/opt/crafty-controller/crafty/crafty-4/servers/da5eee84-3052-4a5f-9f07-9c126b40022f/plugins/` |

---

## Package Structure

```
gg.civai.npc
├── AiNpcPlugin.java              # JavaPlugin entry point, wires everything together
├── ai/
│   └── OllamaClient.java         # HTTP POST to Ollama /api/chat, parses JSON response into NpcAction
├── command/
│   └── NpcCommand.java           # /ainpc spawn|remove|status
└── npc/
    ├── AiNpc.java                # Core NPC: spawns Villager entity, game tick + AI tick + memory
    ├── ConversationMemory.java   # Circular buffer of last 10 player↔NPC exchanges (v1.1)
    ├── NpcAction.java            # Enum + data class (IDLE/SPEAK/MOVE_TO/MOVE_AND_SPEAK/REPORT/TIME_REPORT)
    ├── NpcManager.java           # Manages NPC list, routes @mention vs passive chat
    ├── NpcPersistenceManager.java# Save/load NPC locations to npcs.yml (v1.1)
    └── WorldState.java           # Surroundings snapshot + time/weather details for Ollama prompt
```

---

## How It Works

1. Player runs `/ainpc spawn`
2. `NpcManager.spawnAt()` creates an `AiNpc`, which spawns a Villager entity with vanilla AI disabled
3. Two schedulers start:
    - **Game tick** (every 4 ticks / 200ms): moves NPC toward `targetLocation` via linear interpolation
    - **AI tick** (every 10 seconds): collects `WorldState`, calls `OllamaClient.think()` async, applies returned `NpcAction` on main thread
4. Player right-clicks NPC or chats nearby → stored as `lastPlayerMessage`, consumed on next AI tick
5. `OllamaClient` sends a system prompt + world state to `POST /api/chat`, expects JSON response, strips markdown fences, parses into `NpcAction`

---

## Ollama Integration

- **Endpoint**: `http://192.168.1.104:11434/api/chat`
- **Model**: `llama3:latest` (confirmed working via `curl http://192.168.1.104:11434/api/tags`)
- **Stream**: `false` (blocking single response)
- **Format**: JSON object enforced via `format` parameter + system prompt
- **Timeout**: 60 seconds on the HTTP request (Ollama on 8B model takes 2-10s typically)
- **Common gotcha**: model name must be exact — `llama3` fails, `llama3:latest` works

### Prompt structure (v1.1):
```
## Recent Conversation History        ← last 10 player↔Steve exchanges
## Recent Nearby Chat                 ← last 5 passive messages (not @Steve)
## PRIORITY: Direct Message           ← only present on @mention
=== WORLD STATE ===                   ← position, time, weather, entities
```

### Expected JSON response format:
```json
{
  "thought": "internal reasoning string",
  "action": "IDLE | SPEAK | MOVE_TO | MOVE_AND_SPEAK | REPORT | TIME_REPORT",
  "speech": "what the NPC says, or null",
  "target_x": 0.0,
  "target_y": 0.0,
  "target_z": 0.0
}
```

---

## Current Limitations (as of v1.1.0)

- **No pathfinding**: movement is linear interpolation, NPC walks through walls/water
- **Memory is in-RAM only**: conversation history resets on server restart (persistence coming later)
- **Single personality**: one system prompt for all NPCs (config-driven profiles planned)
- **Villager entity only**: appearance is always a vanilla villager
- **Movement cap**: 50 blocks max per decision to prevent runaway coordinates
- **Single NPC**: only one NPC (npc.name) is supported; multi-NPC planned

---

## Planned Next Steps

1. **Pathfinding** — use Paper's pathfinding API or simple obstacle avoidance
2. **Persist conversation memory** — write memory to `npcs.yml` so it survives restarts
3. **Multiple NPCs with different personalities** — config-driven personality profiles
4. **Custom skins** — fetch player skin via Mojang API and apply to ArmorStand or NPC lib
5. **NPC inventory awareness** — include held/nearby items in world state
6. **Multi-agent** — NPCs aware of each other, can interact

---

## Config Reference

`plugins/ai-npc/config.yml` (runtime, not committed):
```yaml
npc:
  name: "SuperSteve"
  skin: "Steve"
  scan-radius: 10
  game-tick-ms: 200
  ai-tick-seconds: 10

ollama:
  host: "http://192.168.1.104:11434"
  model: "llama3:latest"
  timeout-seconds: 30
```

`src/main/resources/config.example.yml` (committed, no real IPs):
```yaml
npc:
  name: "SuperSteve"
  skin: "Steve"
  scan-radius: 10
  game-tick-ms: 200
  ai-tick-seconds: 10

ollama:
  host: "http://YOUR_OLLAMA_IP:11434"
  model: "llama3:latest"
  timeout-seconds: 30
```

---

## Git / Workflow Rules

- Repo: `https://github.com/AN0R31/civai-npc`
- **AI assistants must never `git commit`, `git push`, or create branches.** All commits and pushes are done manually by the human after review.
- Always work directly on `main`. No feature branches.
- `config.yml` is gitignored (contains real LAN IP)
- `config.example.yml` is committed with placeholder values
- `target/` is gitignored
- Build on the Crafty LXC directly (`/opt/civai-npc`)

---

## Changelog

Each entry records a session's worth of changes. Format: `vX.Y.Z — YYYY-MM-DD — summary`.

### v1.0.0 — 2026-09-08 — Initial release
- Plugin scaffolded: `AiNpcPlugin`, `AiNpc`, `NpcManager`, `NpcAction`, `WorldState`, `OllamaClient`, `NpcCommand`
- Villager-based NPC with vanilla AI disabled
- Linear-interpolation movement (game tick every 200 ms)
- Ollama `/api/chat` integration, `llama3:latest`, stream=false, 60 s timeout
- JSON action schema: `IDLE | SPEAK | MOVE_TO | MOVE_AND_SPEAK`
- `/ainpc spawn|remove|status` commands
- 50-block movement cap per decision
- `config.yml` (runtime, gitignored) + `config.example.yml` (committed)

### v1.1.2 — 2026-09-08 — Time accuracy fix, @mention queue
- **Time hallucination fix** (`WorldState.toPromptString`): time facts now formatted as a labelled block with `[TIME - use these EXACT numbers, do not invent your own]` header and one line per event (sunset/midnight/sunrise/noon) so small models copy the values directly instead of guessing
- **@mention queue** (`AiNpc`): when `thinkingLock` is held (periodic tick in flight), the @mention is now queued (most-recent-wins) instead of silently dropped; fires automatically when the current think completes; logged as "queued" / "firing queued" for visibility

### v1.1.1 — 2026-09-08 — Speech fix, terrain movement
- **Speech fix**: rewrote system prompt to be shorter and more directive for 8B models; `HARD RULES` section forces non-null speech on PRIORITY messages; action descriptions simplified
- **Player location in PRIORITY block**: player's XYZ is now included so the model can use correct coords for MOVE_AND_SPEAK
- **Improved log**: `think()` now logs `action` and `speech` alongside thought, so you can see what Steve actually chose
- **Terrain-following movement** (`AiNpc.resolveY`): NPC uses block passability + `getHighestBlockYAt` to follow terrain; simple 1-block step-up for fences/walls; cliff guard (stops if ground delta > 3 blocks)

### v1.1.0 — 2026-09-08 — Persistence, Memory, @mention, improved AI context
- **Persistence** (`NpcPersistenceManager`): spawn location saved to `npcs.yml`; NPCs auto-restored on plugin enable; `/ainpc remove` cleans persistence; `/ainpc spawn` warns if already saved
- **Self-detection fix** (`WorldState`): NPC UUID filtered from entity scan — Steve no longer sees himself
- **Rolling memory** (`ConversationMemory`): circular buffer of last 10 `{player, playerMsg, steveResponse}` entries; injected into Ollama prompt as "## Recent Conversation History"; in-RAM only
- **Passive chat log** (`AiNpc`): last 5 non-directed nearby messages stored per-NPC; injected as "## Recent Nearby Chat"; Steve observes without immediately reacting
- **@mention model** (`NpcManager`): `AsyncPlayerChatEvent` → Paper `AsyncChatEvent`; `@Steve <msg>` triggers immediate async Ollama call; other messages go to passive log; right-click handler removed
- **Immediate response** (`AiNpc.triggerImmediateResponse`): skips AI tick queue via `thinkingLock`; proximity-checked on main thread
- **New actions** (`NpcAction`): `REPORT` (narrate surroundings) and `TIME_REPORT` (answer time/weather naturally)
- **Richer world state** (`WorldState`): full tick value, minutes-until-{sunrise,noon,sunset,midnight}, weather (clear/rain/thunder), day/night boolean
- **Updated system prompt** (`OllamaClient`): NPC name parameterised; REPORT/TIME_REPORT documented; IDLE restricted to truly idle situations; prompt injection order: memory → passive chat → priority message → world state
- **`/ainpc status`** now shows memory fill (e.g. `memory=3/10`)

---

## Useful Commands

```bash
# Build and deploy in one line
cd /opt/civai-npc && mvn package && cp target/ai-npc-1.1.2.jar \
  /opt/crafty-controller/crafty/crafty-4/servers/da5eee84-3052-4a5f-9f07-9c126b40022f/plugins/

# Test Ollama reachability
curl http://192.168.1.104:11434/api/tags

# Test Ollama inference
curl http://192.168.1.104:11434/api/chat \
  -d '{"model":"llama3:latest","stream":false,"messages":[{"role":"user","content":"say hi in one sentence"}]}'

# Watch server logs live
tail -f /opt/crafty-controller/crafty/crafty-4/servers/da5eee84-3052-4a5f-9f07-9c126b40022f/logs/latest.log

# In-game commands
/ainpc spawn    # spawn at your feet
/ainpc status   # see current thought + action
/ainpc remove   # despawn nearest
```