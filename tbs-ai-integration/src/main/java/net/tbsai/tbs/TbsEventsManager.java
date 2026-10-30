package net.tbsai.tbs;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.tbsai.TbsAiMod;
import net.tbsai.config.TbsAiConfig;
import net.tbsai.memory.TbsAiMemoryData;
import net.thebrokenscript.brokencore.api.event.RandomEvent;
import net.thebrokenscript.brokencore.api.registry.BCRegistries;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class TbsEventsManager {
    public static final String TBS_NAMESPACE = "thebrokenscript";

    public static final Set<String> CATALOG = Set.of(
            "aberration", "bad_sun", "behind_you", "breathe", "bsod",
            "can_someone_hear_me", "cave", "ceiling_circuit_event", "close_menu", "collinlock",
            "coord", "cursed_book", "damage", "door", "doors",
            "entity_discard", "experience", "explode_base", "eyes", "fake_disconnect",
            "false_villager", "gamma", "giift", "give_disc_11", "hallucination",
            "heartbeat", "hungry", "inventory_corruption", "isolation", "jframe_1",
            "jframe_2", "jframe_3", "jframe_4", "jframe_5", "keep_playing",
            "lava_cast", "look_and_damage", "madness_1", "moon_glitch", "moon_phase",
            "noop", "null_book", "null_getting_achievement", "null_interface_trigger", "null_invade_base",
            "null_is_near", "null_particle", "null_scare", "null_title", "null_whisper",
            "nulled_gui", "nullnullnull_advancement", "obfuscated_sign", "opengl_error", "paranoia",
            "place_all_dead", "place_bedrock", "place_cave_air", "place_empty", "place_flowing_water",
            "place_hello", "place_lava", "place_netherrack", "place_oak_sign", "place_redstone_torch",
            "place_water", "play_sound", "port", "psst_event", "push",
            "random_song", "rejoin", "reset_rotation", "run", "screen_dupe",
            "set_do_daylight_cycle", "set_on_fire", "set_random_time_of_day", "set_time", "shadow_bug",
            "sky_blue", "stare_at_player", "stick", "strike_lightning", "tbe_curious",
            "text", "title_event", "txt", "why_cant_you_leave", "wrong_overlay"
    );

    private static final Map<UUID, Long> LAST_EVENT_TIME = new ConcurrentHashMap<>();
    private static final Map<UUID, Deque<Long>> EVENT_HISTORY_1H = new ConcurrentHashMap<>();

    private TbsEventsManager() {
    }

    public static boolean isValidEventId(String id) {
        return id != null && CATALOG.contains(id.trim().toLowerCase());
    }

    public static Set<String> getCatalog() {
        return Collections.unmodifiableSet(CATALOG);
    }

    public static RandomEvent getEvent(String id) {
        if (!isValidEventId(id)) {
            return null;
        }
        ResourceLocation location = ResourceLocation.fromNamespaceAndPath(TBS_NAMESPACE, id.trim().toLowerCase());
        return BCRegistries.EVENT.get(location);
    }

    public static synchronized boolean canTriggerEvent(UUID playerUuid, long currentGameTime) {
        if (!TbsAiConfig.EVENTS_ENABLED.get()) {
            return false;
        }

        // Check cooldown
        long cooldownTicks = (long) TbsAiConfig.EVENT_COOLDOWN_MINUTES.get() * 60L * 20L;
        Long lastTime = LAST_EVENT_TIME.get(playerUuid);
        if (lastTime != null && (currentGameTime - lastTime) < cooldownTicks) {
            return false;
        }

        // Check rolling hour rate limit (1 hour = 72000 ticks)
        long oneHourAgo = currentGameTime - 72000L;
        Deque<Long> history = EVENT_HISTORY_1H.computeIfAbsent(playerUuid, k -> new ArrayDeque<>());
        while (!history.isEmpty() && history.peekFirst() < oneHourAgo) {
            history.pollFirst();
        }

        return history.size() < TbsAiConfig.MAX_EVENTS_PER_HOUR.get();
    }

    public static boolean tryExecuteEvent(ServerLevel level, ServerPlayer player, String eventId, boolean bypassCooldown) {
        if (level == null || player == null || !isValidEventId(eventId)) {
            return false;
        }

        String normalizedId = eventId.trim().toLowerCase();
        long gameTime = level.getGameTime();
        UUID uuid = player.getUUID();

        if (!bypassCooldown && !canTriggerEvent(uuid, gameTime)) {
            if (TbsAiConfig.DEBUG_LOGGING.get()) {
                TbsAiMod.LOGGER.debug("Event {} rate-limited or on cooldown for player {}", normalizedId, player.getName().getString());
            }
            return false;
        }

        RandomEvent randomEvent = getEvent(normalizedId);
        if (randomEvent == null) {
            TbsAiMod.LOGGER.warn("RandomEvent {} found in catalog but not registered in BCRegistries.EVENT", normalizedId);
            return false;
        }

        Vec3 pos = player.position();
        try {
            if (!randomEvent.canExecute(level, player, pos)) {
                if (TbsAiConfig.DEBUG_LOGGING.get()) {
                    TbsAiMod.LOGGER.debug("Event {} cannot execute at position {} for player {}", normalizedId, pos, player.getName().getString());
                }
                return false;
            }

            // Execute on server thread
            randomEvent.run(level, player, pos);

            if (!bypassCooldown) {
                LAST_EVENT_TIME.put(uuid, gameTime);
                Deque<Long> history = EVENT_HISTORY_1H.computeIfAbsent(uuid, k -> new ArrayDeque<>());
                history.addLast(gameTime);
            }

            TbsAiMemoryData memoryData = TbsAiMemoryData.get(level.getServer(), uuid);
            memoryData.addRecentEvent(normalizedId);

            if (TbsAiConfig.DEBUG_LOGGING.get()) {
                TbsAiMod.LOGGER.debug("Successfully executed event {} for player {}", normalizedId, player.getName().getString());
            }
            return true;
        } catch (Throwable t) {
            TbsAiMod.LOGGER.error("Failed to run TBS event {}: {}", normalizedId, t.getMessage(), t);
            return false;
        }
    }

    public static void clearPlayerState(UUID uuid) {
        LAST_EVENT_TIME.remove(uuid);
        EVENT_HISTORY_1H.remove(uuid);
    }

    public static void clearAll() {
        LAST_EVENT_TIME.clear();
        EVENT_HISTORY_1H.clear();
    }
}
