package net.tbsai.event;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.tbsai.TbsAiMod;
import net.tbsai.config.TbsAiConfig;
import net.tbsai.llm.LlmManager;
import net.tbsai.tbs.NullLore;
import net.tbsai.llm.PromptBuilder;
import net.tbsai.memory.MemoryEntry;
import net.tbsai.memory.TbsAiMemoryData;
import net.tbsai.tbs.TbsEventsManager;
import net.thebrokenscript.brokencore.api.dsl.ChatUtil;
import net.thebrokenscript.brokencore.api.registry.BCRegistries;
import net.thebrokenscript.brokencore.api.responses.ChatResponse;
import net.thebrokenscript.brokencore.impl.config.BCConfigs;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ChatHandler {
    private static final Set<UUID> IN_FLIGHT_PLAYERS = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, String> PENDING_MESSAGES = new ConcurrentHashMap<>();
    private static final Random RANDOM = new Random();

    private static final List<String> SCRIPTED_FALLBACKS = List.of(
            "err.type=null.messageNotFound",
            "err.type=null.hello",
            "[0.1]",
            "...",
            "null"
    );

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onServerChat(ServerChatEvent event) {
        if (event.isCanceled()) {
            return;
        }

        ServerPlayer player = event.getPlayer();
        if (player == null) {
            return;
        }

        String rawText = event.getRawText();
        if (rawText == null || rawText.isBlank()) {
            return;
        }
        rawText = rawText.trim();
        if (rawText.startsWith("/")) {
            return;
        }

        MinecraftServer server = player.server;
        UUID uuid = player.getUUID();
        long gameTime = player.serverLevel().getGameTime();

        // Always store player message in memory
        TbsAiMemoryData memoryData = TbsAiMemoryData.get(server, uuid);
        memoryData.addEntry("player", rawText, gameTime);

        // Native TBS chat response check
        if (TbsAiConfig.RESPECT_NATIVE_RESPONSES.get()) {
            try {
                if (BCConfigs.INSTANCE.getServer().getChat().getEnableChatResponses()) {
                    for (ChatResponse response : BCRegistries.CHAT_RESPONSE) {
                        if (response.check(rawText) && response.shouldExecute(player.serverLevel(), player)) {
                            if (TbsAiConfig.DEBUG_LOGGING.get()) {
                                TbsAiMod.LOGGER.debug("TBS native response matched for message '{}'. Bypassing LLM.", rawText);
                            }
                            return;
                        }
                    }
                }
            } catch (Throwable t) {
                TbsAiMod.LOGGER.warn("Failed checking native TBS chat responses: {}", t.getMessage());
            }
        }

        // Check in-flight requests limit (at most 1 per player)
        if (IN_FLIGHT_PLAYERS.contains(uuid)) {
            PENDING_MESSAGES.put(uuid, rawText);
            if (TbsAiConfig.DEBUG_LOGGING.get()) {
                TbsAiMod.LOGGER.debug("Request in-flight for {}. Queued pending message.", player.getName().getString());
            }
            return;
        }

        IN_FLIGHT_PLAYERS.add(uuid);
        processChatRequest(player, rawText);
    }

    private static void processChatRequest(ServerPlayer player, String message) {
        MinecraftServer server = player.server;
        UUID uuid = player.getUUID();
        TbsAiMemoryData memoryData = TbsAiMemoryData.get(server, uuid);

        String userPrompt = PromptBuilder.buildUserPrompt(player.serverLevel(), player, message, memoryData);
        String systemPrompt = net.tbsai.tbs.NullLore.getSystemPrompt();

        LlmManager.getClient().generate(systemPrompt, userPrompt)
                .whenComplete((response, throwable) -> {
                    server.execute(() -> {
                        try {
                            if (throwable != null) {
                                handleFailure(player, throwable);
                            } else {
                                handleSuccess(player, response, memoryData);
                            }
                        } finally {
                            IN_FLIGHT_PLAYERS.remove(uuid);
                            // Process latest queued pending message if any
                            String nextMsg = PENDING_MESSAGES.remove(uuid);
                            if (nextMsg != null && isPlayerActive(player)) {
                                IN_FLIGHT_PLAYERS.add(uuid);
                                processChatRequest(player, nextMsg);
                            }
                        }
                    });
                });
    }

    private static void handleSuccess(ServerPlayer player, net.tbsai.llm.LlmResponse response, TbsAiMemoryData memoryData) {
        if (!isPlayerActive(player)) {
            return;
        }

        List<String> messages = response.messages();
        String eventId = response.event();
        long gameTime = player.serverLevel().getGameTime();

        // Record Null's lines to memory
        for (String msg : messages) {
            memoryData.addEntry("null", msg, gameTime);
        }

        // Check if rolling summary generation is triggered
        if (TbsAiConfig.MEMORY_ENABLED.get() && memoryData.getEntriesSinceLastSummary() >= TbsAiConfig.SUMMARY_EVERY.get()) {
            triggerSummary(player.server, player.getUUID(), memoryData);
        }

        if (messages.isEmpty()) {
            return;
        }

        // Send lines on server thread with 1.2s (24 ticks) pause
        int lineIndex = 0;
        for (String line : messages) {
            if (lineIndex >= 5) {
                break;
            }
            int delayTicks = lineIndex * 24;
            final String textToSend = line;
            TickHandler.schedule(player.getUUID(), delayTicks, () -> {
                if (isPlayerActive(player)) {
                    Component component = Component.translatable("chat.type.text", Component.literal("Null"), Component.literal(textToSend));
                    ChatUtil.chat(player.serverLevel(), component, false);
                }
            });
            lineIndex++;
        }

        // Schedule event 20 ticks after the last line
        if (eventId != null && TbsEventsManager.isValidEventId(eventId)) {
            int lastLineDelay = (lineIndex - 1) * 24;
            int eventDelay = lastLineDelay + 20;

            TickHandler.schedule(player.getUUID(), eventDelay, () -> {
                if (!isPlayerActive(player)) {
                    return;
                }
                if (RANDOM.nextInt(100) < TbsAiConfig.EVENT_CHANCE.get()) {
                    TbsEventsManager.tryExecuteEvent(player.serverLevel(), player, eventId, false);
                } else if (TbsAiConfig.DEBUG_LOGGING.get()) {
                    TbsAiMod.LOGGER.debug("Event {} skipped due to chance check for player {}", eventId, player.getName().getString());
                }
            });
        }
    }

    private static void handleFailure(ServerPlayer player, Throwable throwable) {
        String sanitizedError = TbsAiConfig.sanitize(throwable.getMessage());
        TbsAiMod.LOGGER.error("LLM request failed for player {}: {}", player.getName().getString(), sanitizedError);

        // 10% chance to send one short scripted Null phrase
        if (RANDOM.nextInt(100) < 10 && isPlayerActive(player)) {
            String phrase = SCRIPTED_FALLBACKS.get(RANDOM.nextInt(SCRIPTED_FALLBACKS.size()));
            Component component = Component.translatable("chat.type.text", Component.literal("Null"), Component.literal(phrase));
            ChatUtil.chat(player.serverLevel(), component, false);
        }
    }

    private static void triggerSummary(MinecraftServer server, UUID uuid, TbsAiMemoryData memoryData) {
        List<MemoryEntry> entries = memoryData.getRecentEntries(TbsAiConfig.SUMMARY_EVERY.get());
        String prompt = PromptBuilder.buildSummaryPrompt(memoryData.getSummary(), entries);

        LlmManager.getClient().summarize(prompt).whenComplete((summary, throwable) -> {
            server.execute(() -> {
                if (throwable != null) {
                    TbsAiMod.LOGGER.warn("Failed generating rolling summary: {}. Will retry in 10 entries.", TbsAiConfig.sanitize(throwable.getMessage()));
                    memoryData.setEntriesSinceLastSummary(TbsAiConfig.SUMMARY_EVERY.get() - 10);
                } else if (summary != null && !summary.isBlank()) {
                    memoryData.setSummary(summary.trim());
                    if (TbsAiConfig.DEBUG_LOGGING.get()) {
                        TbsAiMod.LOGGER.debug("Updated rolling summary for player UUID {}", uuid);
                    }
                }
            });
        });
    }

    private static boolean isPlayerActive(ServerPlayer player) {
        return player != null && player.isAlive() && player.connection != null && !player.hasDisconnected();
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            UUID uuid = player.getUUID();
            IN_FLIGHT_PLAYERS.remove(uuid);
            PENDING_MESSAGES.remove(uuid);
            TickHandler.cancelPlayer(uuid);
            TbsEventsManager.clearPlayerState(uuid);
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        IN_FLIGHT_PLAYERS.clear();
        PENDING_MESSAGES.clear();
        TickHandler.clearAll();
        TbsEventsManager.clearAll();
    }
}
