# AGENTS.md

## Repository & Directory Layout

- **Gradle project root:** `tbs-ai-integration/` (all `./gradlew` commands must run from here).
- **External dependency:** `thebrokenscript.jar` (TBS 2.0.5) resides in repo root (`../thebrokenscript.jar`).
- **Extracted core lib:** `tbs-ai-integration/libs/brokencore.jar` is extracted from `thebrokenscript.jar` (`META-INF/jarjar/net.thebrokenscript.brokencore-neoforge-0.1.0+mc1.21.1-build.3658.jar`).
- Built mod JAR artifact: `tbs-ai-integration/build/libs/tbs-ai-integration-1.0.0.jar`.

## Environment & Toolchain

- **Java Version:** Requires Java 21 (system default `/usr/bin/java` is Java 25).
- **JDK 21 Path:** `/opt/jdk-21` (accessible via shell wrapper `java21` or `JAVA_HOME=/opt/jdk-21`).
- `tbs-ai-integration/gradle.properties` sets `org.gradle.java.home=/opt/jdk-21` so `./gradlew` invokes JDK 21 daemon automatically.

## Developer Commands

Run from `tbs-ai-integration/`:

```bash
# Build mod JAR
./gradlew build

# Run all unit tests
./gradlew test

# Run a single focused test
./gradlew test --tests "net.tbsai.TbsAiTests.testCatalogHasExact90Events"

# Verify built JAR does not bundle forbidden classes (must return empty)
jar tf build/libs/tbs-ai-integration-1.0.0.jar | grep -E "(net/thebrokenscript|net/minecraft|kotlin)"

# Launch development client with TBS mod
cp ../thebrokenscript.jar run/mods/
./gradlew runClient
```

## Build & Dependency Rules

- **Strict `compileOnly`:** `thebrokenscript.jar`, `libs/brokencore.jar`, and `org.jetbrains.kotlin:kotlin-stdlib:2.2.20` must remain `compileOnly`. Never bundle them via `jarJar` or runtime dependencies.
- **Test classpath:** `build.gradle` must include `addModdingDependenciesTo(sourceSets.test)` under the `neoForge` block so JUnit tests can resolve Minecraft classes (e.g. `CompoundTag`).
- **No Cloth Config:** GUI is built with vanilla Minecraft `Screen` / widgets registered via NeoForge `IConfigScreenFactory`.

## TBS API & Runtime Contracts

- **Random Events:**
  - Registered in `net.thebrokenscript.brokencore.api.registry.BCRegistries.EVENT`.
  - Always call `randomEvent.canExecute(level, player, player.position())` before `randomEvent.run(...)`. `run()` does not check `canExecute()`.
  - Execute only on the server thread, wrapped in `try/catch(Throwable)`.
  - Catalog strictly restricted to the 90 random events in `TbsEventsManager.CATALOG` (never trigger story events).
- **Native Chat Response Priority:**
  - Read `BCConfigs.INSTANCE.getServer().getChat().getEnableChatResponses()` (read-only; never call setter).
  - If enabled, iterate `BCRegistries.CHAT_RESPONSE` and check `response.check(msg) && response.shouldExecute(level, player)`. If matched, abort LLM call.
- **Chat Listener:**
  - Listen on `ServerChatEvent` at `EventPriority.LOWEST` and ignore if `event.isCanceled()` or starts with `/`.
- **Security & Logging:**
  - Never log or output raw API keys to chat or logs; use `TbsAiConfig.sanitize()`.
  - Use `TbsAiMod.LOGGER`, never `System.out`.
