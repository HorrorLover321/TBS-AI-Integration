package net.tbsai.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.tbsai.config.TbsAiConfig;
import net.tbsai.llm.LlmManager;
import net.tbsai.memory.TbsAiMemoryData;
import net.tbsai.tbs.TbsEventsManager;

public final class TbsAiCommands {

    private TbsAiCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("tbsai")
                // /tbsai resetmemory
                .then(Commands.literal("resetmemory")
                        .executes(ctx -> {
                            CommandSourceStack source = ctx.getSource();
                            if (!(source.getEntity() instanceof ServerPlayer player)) {
                                source.sendFailure(Component.literal("Only players can reset their memory."));
                                return 0;
                            }
                            TbsAiMemoryData memoryData = TbsAiMemoryData.get(source.getServer(), player.getUUID());
                            memoryData.clear();
                            source.sendSuccess(() -> Component.literal("[TBS AI] Memory cleared for your character in this world."), false);
                            return 1;
                        })
                )
                // /tbsai memory
                .then(Commands.literal("memory")
                        .executes(ctx -> {
                            CommandSourceStack source = ctx.getSource();
                            if (!(source.getEntity() instanceof ServerPlayer player)) {
                                source.sendFailure(Component.literal("Only players can view their memory stats."));
                                return 0;
                            }
                            TbsAiMemoryData memoryData = TbsAiMemoryData.get(source.getServer(), player.getUUID());
                            int entries = memoryData.getEntryCount();
                            String summary = memoryData.getSummary();
                            int words = summary.isBlank() ? 0 : summary.trim().split("\\s+").length;
                            String lastEvent = memoryData.getLastEvent();

                            source.sendSuccess(() -> Component.literal(String.format(
                                    "[TBS AI] Memory: %d entries | Summary: %d words (%d chars) | Last Event: %s",
                                    entries, words, summary.length(), lastEvent
                            )), false);
                            return 1;
                        })
                )
                // /tbsai reload
                .then(Commands.literal("reload")
                        .requires(src -> src.hasPermission(2))
                        .executes(ctx -> {
                            CommandSourceStack source = ctx.getSource();
                            try {
                                LlmManager.recreateClient();
                                source.sendSuccess(() -> Component.literal("[TBS AI] Configuration reloaded and HTTP client refreshed."), true);
                                return 1;
                            } catch (Exception e) {
                                source.sendFailure(Component.literal("[TBS AI] Reload failed: " + e.getMessage()));
                                return 0;
                            }
                        })
                )
                // /tbsai test
                .then(Commands.literal("test")
                        .executes(ctx -> {
                            CommandSourceStack source = ctx.getSource();
                            source.sendSuccess(() -> Component.literal("[TBS AI] Sending test query to provider..."), false);
                            long startTime = System.currentTimeMillis();

                            LlmManager.getClient().testConnection().whenComplete((resp, err) -> {
                                source.getServer().execute(() -> {
                                    if (err != null) {
                                        String safeErr = TbsAiConfig.sanitize(err.getMessage());
                                        source.sendFailure(Component.literal("[TBS AI] Test failed: " + safeErr));
                                    } else {
                                        long latency = resp.latencyMs() > 0 ? resp.latencyMs() : (System.currentTimeMillis() - startTime);
                                        source.sendSuccess(() -> Component.literal(String.format(
                                                "[TBS AI] Test success! Provider: %s | Model: %s | HTTP: %d | Latency: %d ms",
                                                TbsAiConfig.PROVIDER.get().getDisplayName(),
                                                TbsAiConfig.MODEL.get(),
                                                resp.httpStatusCode(),
                                                latency
                                        )), false);
                                    }
                                });
                            });
                            return 1;
                        })
                )
                // /tbsai event <id>
                .then(Commands.literal("event")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.argument("id", StringArgumentType.string())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(TbsEventsManager.getCatalog(), builder))
                                .executes(ctx -> {
                                    CommandSourceStack source = ctx.getSource();
                                    if (!(source.getEntity() instanceof ServerPlayer player)) {
                                        source.sendFailure(Component.literal("Event execution requires a player context."));
                                        return 0;
                                    }
                                    String id = StringArgumentType.getString(ctx, "id").trim().toLowerCase();
                                    if (!TbsEventsManager.isValidEventId(id)) {
                                        source.sendFailure(Component.literal("[TBS AI] Unknown TBS event ID: " + id));
                                        return 0;
                                    }

                                    boolean executed = TbsEventsManager.tryExecuteEvent(player.serverLevel(), player, id, true);
                                    if (executed) {
                                        source.sendSuccess(() -> Component.literal("[TBS AI] Force executed event: " + id), true);
                                        return 1;
                                    } else {
                                        source.sendFailure(Component.literal("[TBS AI] Event " + id + " canExecute() returned false at this player state/location."));
                                        return 0;
                                    }
                                })
                        )
                )
        );
    }
}
