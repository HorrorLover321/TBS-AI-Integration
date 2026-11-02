# AGENTS.md

## Repository & Toolchain

- **Monorepo Structure:** Git root is the repository root; Gradle root is `tbs-ai-integration/`. All `./gradlew` commands must run from `tbs-ai-integration/`.
- **Remote:** `origin/main` (`https://github.com/HorrorLover321/TBS-AI-Integration.git`).
- **Java 21 Requirement:** System default `/usr/bin/java` is Java 25. Build requires JDK 21 at `/opt/jdk-21` (configured via `tbs-ai-integration/gradle.properties`). Use shell wrapper `java21` for standalone Java execution.
- **Dependencies:** `thebrokenscript.jar` (299 MB, TBS 2.0.5) sits in repo root (`../thebrokenscript.jar` from Gradle root). Ignored by git due to GitHub's 100 MB limit. `tbs-ai-integration/libs/brokencore.jar` is extracted from it.

## Developer Commands

Run from `tbs-ai-integration/`:

```bash
# Build mod JAR
./gradlew build

# Run all unit tests
./gradlew test

# Run a single focused test
./gradlew test --tests "net.tbsai.TbsAiTests.testCatalogHasExact90Events"

# Verify built JAR bundles no forbidden classes (must return empty)
jar tf build/libs/tbs-ai-integration-<version>.jar | grep -E "(net/thebrokenscript|net/minecraft|kotlin)"

# Launch dev client with TBS mod
cp ../thebrokenscript.jar run/mods/ && ./gradlew runClient
```

## Build & Dependency Rules

- **Strict `compileOnly`:** `thebrokenscript.jar`, `libs/brokencore.jar`, and `org.jetbrains.kotlin:kotlin-stdlib:2.2.20` must remain `compileOnly`. Never bundle them into the JAR or add as runtime dependencies.
- **Test classpath:** `build.gradle` must keep `addModdingDependenciesTo(sourceSets.test)` under `neoForge` block so tests resolve Minecraft classes (`CompoundTag`).
- **GUI:** Built with vanilla Minecraft `Screen` / widgets registered via NeoForge `IConfigScreenFactory` (no Cloth Config dependency).

## TBS API & Runtime Contracts

- **Random Events:**
  - Registered in `net.thebrokenscript.brokencore.api.registry.BCRegistries.EVENT`.
  - Always call `randomEvent.canExecute(level, player, player.position())` before `randomEvent.run(...)`. `run()` does not validate execution conditions.
  - Execute only on the server thread wrapped in `try/catch(Throwable)`.
  - Strictly restricted to the 90 cataloged random events in `TbsEventsManager.CATALOG` (never trigger story events).
- **Native Chat Response Priority:**
  - Check `BCConfigs.INSTANCE.getServer().getChat().getEnableChatResponses()` (read-only; never call setter).
  - If enabled, iterate `BCRegistries.CHAT_RESPONSE` and check `response.check(msg) && response.shouldExecute(level, player)`. If matched, abort LLM call.
- **Chat Listener:**
  - Listen on `ServerChatEvent` at `@SubscribeEvent(priority = EventPriority.LOWEST)` and ignore if `event.isCanceled()` or message starts with `/`.
- **Security & Logging:**
  - Never log raw API keys to chat or logs; use `TbsAiConfig.sanitize(text)`.
  - Use `TbsAiMod.LOGGER`, never `System.out`.

## Agent Workflow & Token Hygiene

- **Search Scope:** Scope `grep` and file search tools to `tbs-ai-integration/src/` to avoid scanning build/run artifacts.
- **Large Files:** When inspecting `TbsAiConfigScreen.java` (~430 lines), use `grep` or `offset`/`limit` rather than loading the whole file.
