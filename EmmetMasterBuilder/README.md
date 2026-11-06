# Emmet — The Master Builder (prototype v0.1)

Target: Paper 1.21.8 / Java 21 / Citizens 2.0.39. This is a **source project**, not a compiled plugin. It has not been compiled or tested against your live server.

## Build
1. Install JDK 21 and Maven on a development computer.
2. Run `mvn clean package` in this folder.
3. Upload `target/EmmetMasterBuilder-0.1.0.jar` to your Apex server's `plugins/` folder.
4. Configure `plugins/EmmetMasterBuilder/config.yml` with an OpenAI API key, supported model, and **the exact name of a disposable test world** under `allowed-worlds`. Keep the key private; it is billed separately by your AI provider.
5. Restart the server.

## Commands
- `/build` toggles your personal Emmet Citizens NPC.
- Right-click your Emmet NPC to begin conversation; normal chat messages are captured until you say `bye`.
- `/emmet <message>` sends a message explicitly.
- `/emmet confirm` places a proposed prototype building.
- `/emmet cancel` discards the pending proposal.

## Important limitations
- This is an early prototype. It can chat through an AI API and generate only simple procedural houses, towers, and rectangular castle shells, **not arbitrary detailed structures**.
- The NPC starts with a default player appearance; an Emmet-inspired orange construction skin must be applied separately through Citizens `/npc skin` with a skin you have permission to use. There is no automatic skin download.
- Right-click opens a conversation in regular Minecraft chat; it does not automatically focus the client's chat text box (server plugins cannot reliably do that without a client mod).
- The current builder **overwrites blocks**, has **no undo**, and does not yet enforce WorldGuard region boundaries. It is restricted by an allowed-worlds list, defaults to an unavailable test world, and is admin-only. Do not enable it on production worlds or player islands.
- A castle is a basic rectangular shell, not a detailed castle.
- This version does not yet call WorldEdit APIs, despite WorldEdit being present on your server.
- AI replies are processed asynchronously. Do not paste API keys into chat.
- If the Citizens dependency coordinates differ for your installed build, adjust the Maven dependency accordingly.
