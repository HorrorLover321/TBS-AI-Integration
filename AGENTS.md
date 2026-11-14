# AGENTS.md

## Repository & Toolchain

- **Layout:** Git root is the repo root; the Gradle project is `tbs-ai-integration/`. Run every `./gradlew` command from there.
- **Java 21:** system `/usr/bin/java` is Java 25, so `gradle.properties` pins `org.gradle.java.home=/opt/jdk-21`. Use the `java21` wrapper for standalone `java`/`javac`.
- **TBS dependency:** `thebrokenscript.jar` (TBS 2.0.5-hotfix, ~300 MB) lives in the repo root and is git-ignored (GitHub 100 MB limit); `build.gradle` references it as `../thebrokenscript.jar`. `libs/brokencore.jar` is extracted from it.

## Developer Commands

Run from `tbs-ai-integration/`:

```bash
./gradlew build            # jar + tests + verifyJarContents (check depends on it)
./gradlew test
./gradlew test --tests "net.tbsai.TbsAiTests.testCatalogHasExact90Events"

# Dev client with TBS
cp ../thebrokenscript.jar run/mods/ && ./gradlew runClient
```

- `verifyJarContents` fails on any `net/thebrokenscript`, `net/minecraft`, or `kotlin` entry in the mod jar. Keep `thebrokenscript.jar`, `libs/brokencore.jar`, and `kotlin-stdlib` `compileOnly`.
- Keep `addModdingDependenciesTo(sourceSets.test)`; tests use Minecraft classes such as `CompoundTag`.
- Run `runClient` detached (`setsid nohup ./gradlew runClient ... &`); a foreground shell timeout can SIGTERM the client. Check with `pgrep -f '[d]evlaunch'`, never `pgrep -af java` (it dumps the full classpath into context).

## Architecture

- `TbsAiMod` registers the common config, `ChatHandler`/`TickHandler`, `/tbsai` commands, and (client dist only) `ClientSetup`, which registers `TbsAiConfigScreen` through NeoForge `IConfigScreenFactory`.
- Chat flow: `ServerChatEvent` (`LOWEST`) → record message in `TbsAiMemoryData` → native TBS response check → `LlmManager.getClient().generate(...)` async → back on `server.execute(...)` for a delayed reply, memory update, and optional catalog event.
- `LlmManager` lazily creates `GeminiClient` or `OpenAiClient` from config; it is recreated on config load/reload and `/tbsai reload`. Config is not re-read per request.
- Commands: `/tbsai resetmemory`, `/tbsai memory`, `/tbsai reload` (permission 2), `/tbsai test`.
- The 90-ID catalog appears in both `TbsEventsManager.CATALOG` and the `NullLore.getSystemPrompt()` text; the 18-destructive-event gate exists only in the prompt. Keep them in sync — tests check the catalog size and a few IDs, not the prompt text.

## TBS API & Runtime Contracts

- **Random events:** registered in `BCRegistries.EVENT`; only IDs in `TbsEventsManager.CATALOG` may run (never story events). Always call `randomEvent.canExecute(level, player, player.position())` before `run(...)`, on the server thread, inside `try/catch(Throwable)`.
- **Native chat priority:** when `TbsAiConfig.RESPECT_NATIVE_RESPONSES` and `BCConfigs.INSTANCE.getServer().getChat().getEnableChatResponses()` are true, iterate `BCRegistries.CHAT_RESPONSE` and skip the LLM on `response.check(msg) && response.shouldExecute(level, player)`. Never call TBS config setters.
- **Secrets:** never log raw API keys; use `TbsAiConfig.sanitize(...)`. Use `TbsAiMod.LOGGER`, never `System.out`.

## GUI Conventions

- `Minecraft.setScreen(parent)` re-runs `init()`; unsaved widget values must be mirrored into fields and restored (`captureWorkingValues()` in `TbsAiConfigScreen`), or they are lost when returning from child screens.
- `AbstractSelectionList.clearEntries()` is protected in NeoForge 21.1.x; use the `ModelSelectScreen.ModelList.clearModels()` wrapper.
- The config screen has no keybind or command: Mods → TBS AI Integration → Настройки.
- Model scan can list deprecated/unavailable models; confirm with Test Connection before saving.

## Live GUI Testing (`runClient`)

- The window is Xwayland on `DISPLAY=:0`, fullscreen 1920×1080 at (0,0). TBS rewrites the title to `err.<o>`; find it by class: `xdotool search --class Minecraft`.
- Check `run/options.txt` before clicking: with `guiScale:2`, Screen space is 960×540, so double widget coordinates for `xdotool`.
- Activate the window first (`xdotool windowactivate <id>` or Alt+Tab), or clicks land in overlapping windows.
- Screenshots: `spectacle -b -o /tmp/<name>.png`; `import -window` can return stale frames after actions.
- The active keyboard layout here is Russian, so `xdotool type` emits Cyrillic. For Latin text use `wl-copy` + `xdotool key ctrl+v`.

## Agent Workflow & Token Hygiene

- Scope searches to `tbs-ai-integration/src/`; `run/`, `build/`, and the 300 MB jar are noise.
- `TbsAiConfigScreen.java` is ~470 lines; use `grep`/`offset`/`limit` instead of reading it whole.
- Never read or print `temp-API-keys.txt`. The configured key is also plaintext in `run/config/tbsai-common.toml`; don't echo config values.
