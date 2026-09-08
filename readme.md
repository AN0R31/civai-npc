# civai-npc

A Paper Minecraft plugin that spawns AI-powered NPCs driven by a local [Ollama](https://ollama.ai) instance. NPCs observe their surroundings, think, speak, and move — no cloud APIs, no external dependencies.

---

## Architecture

```
Minecraft Server (Paper 1.21.x)
  └── ai-npc plugin (Java)
        ├── Game Layer      — spawns/controls NPC entity, scans world state
        ├── Translation Layer — serializes world state to prompt, parses AI response
        └── AI Layer        — HTTP calls to Ollama on local LXC
```

### Tick System

| Tick | Interval | Purpose |
|------|----------|---------|
| Game tick | 200ms (4 ticks) | Smooth NPC movement toward target |
| AI tick | 10 seconds | Collect world state → call Ollama → apply decision |

The NPC always executes the last AI decision while the next one is being computed asynchronously — the game thread is never blocked.

---

## Requirements

- **Paper** 1.21.x (tested on 1.21 and 1.21.1)
- **Java** 21 (JDK for building, JRE for running)
- **Maven** 3.x
- **Ollama** running on a reachable IP (local LXC recommended)
- A pulled Ollama model (tested with `llama3:latest`)

---

## Building

```bash
git clone https://github.com/AN0R31/civai-npc
cd civai-npc

# Copy and edit config
cp src/main/resources/config.example.yml src/main/resources/config.yml
nano src/main/resources/config.yml   # set your Ollama IP and model

# Build
export JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64
mvn package

# Output
target/ai-npc-1.0.0.jar
```

---

## Installation

1. Drop `ai-npc-1.0.0.jar` into your server's `plugins/` folder
2. Start the server — `plugins/ai-npc/config.yml` will be generated
3. Edit `config.yml` with your Ollama host and model
4. Restart the server

---

## Configuration

`plugins/ai-npc/config.yml`:

```yaml
npc:
  name: "SuperSteve"          # Display name above NPC head
  skin: "Steve"               # Currently unused, reserved for future skin API
  scan-radius: 10             # Blocks scanned around NPC for world state
  game-tick-ms: 200           # Movement update interval in ms
  ai-tick-seconds: 10         # How often to query Ollama

ollama:
  host: "http://YOUR_OLLAMA_IP:11434"
  model: "llama3:latest"      # Must match exact name from `ollama list`
  timeout-seconds: 30
```

---

## Commands

| Command | Permission | Description |
|---------|-----------|-------------|
| `/ainpc spawn` | `ainpc.admin` | Spawn SuperSteve at your location |
| `/ainpc remove` | `ainpc.admin` | Remove nearest AI NPC |
| `/ainpc status` | `ainpc.admin` | Show NPC position, action, and current thought |

---

## NPC Behavior

The NPC observes a configurable radius around itself every AI tick and builds a world state snapshot containing:

- Current position and world
- Time of day and weather
- Nearby players (with distances)
- Nearby mobs (with distances)
- Ground block types around the NPC
- Last player message spoken nearby or via right-click

This snapshot is sent to Ollama as a structured prompt. The model responds with a JSON action:

```json
{
  "thought": "A player is nearby, I should greet them",
  "action": "SPEAK",
  "speech": "Hey there traveller!",
  "target_x": 0.0,
  "target_y": 0.0,
  "target_z": 0.0
}
```

### Action Types

| Action | Behaviour |
|--------|-----------|
| `IDLE` | Stay put, do nothing |
| `SPEAK` | Say something to nearby players |
| `MOVE_TO` | Walk toward target coordinates |
| `MOVE_AND_SPEAK` | Walk and talk simultaneously |

Movement is capped at 50 blocks per decision to prevent the AI teleporting the NPC across the map.

---

## Player Interaction

- **Right-click** the NPC villager → sends `(right-clicked you)` as context to the AI
- **Chat nearby** (within scan radius) → NPC hears your message and uses it in the next AI tick

---

## Known Limitations

- NPCs do not persist across server restarts — you must `/ainpc spawn` again after each restart *(persistence planned)*
- NPC uses a Villager entity — appearance is a vanilla villager *(custom player skin support planned)*
- Movement is simple linear interpolation — no pathfinding around obstacles *(pathfinding planned)*
- One NPC personality/prompt is shared across all spawned instances

---

## Planned Features

- [ ] Persistence — save/restore NPC positions on restart
- [ ] Memory — NPC remembers past interactions across AI ticks
- [ ] Pathfinding — navigate around obstacles
- [ ] Custom skins via player skin API
- [ ] Multiple named NPCs with different personalities
- [ ] NPC-to-NPC interaction (multi-agent)
- [ ] Inventory and item awareness
- [ ] Day/night routine scheduling

---

## Project Structure

```
civai-npc/
├── pom.xml
└── src/main/
    ├── java/gg/civai/npc/
    │   ├── AiNpcPlugin.java          # Plugin entry point
    │   ├── ai/
    │   │   └── OllamaClient.java     # HTTP client + prompt builder + response parser
    │   ├── command/
    │   │   └── NpcCommand.java       # /ainpc command handler
    │   └── npc/
    │       ├── AiNpc.java            # NPC entity controller + tick loops
    │       ├── NpcAction.java        # Parsed AI decision model
    │       ├── NpcManager.java       # NPC lifecycle + player event listeners
    │       └── WorldState.java       # World state snapshot + prompt serializer
    └── resources/
        ├── config.example.yml
        └── plugin.yml
```

---

## License

MIT