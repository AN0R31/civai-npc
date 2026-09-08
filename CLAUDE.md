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
- **Output jar**: `target/ai-npc-1.0.0.jar`
- **Deploy**: `cp target/ai-npc-1.0.0.jar /opt/crafty-controller/crafty/crafty-4/servers/da5eee84-3052-4a5f-9f07-9c126b40022f/plugins/`

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
├── AiNpcPlugin.java          # JavaPlugin entry point, wires everything together
├── ai/
│   └── OllamaClient.java     # HTTP POST to Ollama /api/chat, parses JSON response into NpcAction
├── command/
│   └── NpcCommand.java       # /ainpc spawn|remove|status
└── npc/
    ├── AiNpc.java            # Core NPC: spawns Villager entity, runs game tick + AI tick
    ├── NpcAction.java        # Enum + data class for AI decisions (IDLE/SPEAK/MOVE_TO/MOVE_AND_SPEAK)
    ├── NpcManager.java       # Manages list of NPCs, listens for player chat + interact events
    └── WorldState.java       # Scans surroundings, builds prompt string for Ollama
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

### Expected JSON response format:
```json
{
  "thought": "internal reasoning string",
  "action": "IDLE | SPEAK | MOVE_TO | MOVE_AND_SPEAK",
  "speech": "what the NPC says, or null",
  "target_x": 0.0,
  "target_y": 0.0,
  "target_z": 0.0
}
```

---

## Current Limitations (as of v1.0.0)

- **No persistence**: NPCs vanish on server restart, must `/ainpc spawn` again
- **No pathfinding**: movement is linear interpolation, NPC walks through walls/water
- **No memory**: each AI tick is stateless — NPC doesn't remember previous interactions
- **Single personality**: one system prompt for all NPCs
- **Villager entity only**: appearance is always a vanilla villager
- **Movement cap**: 50 blocks max per decision to prevent runaway coordinates

---

## Planned Next Steps

1. **Persistence** — save NPC spawn locations to `npcs.yml`, restore on plugin enable
2. **Conversation memory** — maintain a rolling message history per NPC, inject into prompt
3. **Pathfinding** — use Paper's pathfinding API or simple obstacle avoidance
4. **Multiple NPCs with different personalities** — config-driven personality profiles
5. **Custom skins** — fetch player skin via Mojang API and apply to ArmorStand or NPC lib
6. **NPC inventory awareness** — include held/nearby items in world state
7. **Multi-agent** — NPCs aware of each other, can interact

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

## Git Notes

- Repo: `https://github.com/AN0R31/civai-npc`
- `config.yml` is gitignored (contains real LAN IP)
- `config.example.yml` is committed with placeholder values
- `target/` is gitignored
- Build on the Crafty LXC directly (`/opt/civai-npc`)

---

## Useful Commands

```bash
# Build and deploy in one line
cd /opt/civai-npc && mvn package && cp target/ai-npc-1.0.0.jar \
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