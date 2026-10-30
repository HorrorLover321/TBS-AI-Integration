package net.tbsai.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.biome.Biome;
import net.tbsai.memory.MemoryEntry;
import net.tbsai.memory.TbsAiMemoryData;
import net.tbsai.tbs.NullLore;
import net.thebrokenscript.api.ext.PlayerExt;
import net.thebrokenscript.util.ReputationEnum;

import java.util.List;

public final class PromptBuilder {

    private PromptBuilder() {
    }

    public static String buildUserPrompt(ServerLevel level, ServerPlayer player, String currentMessage, TbsAiMemoryData memoryData) {
        StringBuilder sb = new StringBuilder();

        // 1. World state JSON
        JsonObject worldState = new JsonObject();
        worldState.addProperty("player", player.getName().getString());

        String rep = "NORMAL";
        try {
            ReputationEnum reputationEnum = PlayerExt.INSTANCE.getReputation(player);
            if (reputationEnum != null) {
                rep = reputationEnum.name();
            }
        } catch (Throwable ignored) {
        }
        worldState.addProperty("reputation", rep);

        long gameTime = level.getDayTime();
        long day = gameTime / 24000L;
        long timeOfDay = gameTime % 24000L;
        String timeStr;
        if (timeOfDay < 1000L || timeOfDay >= 23000L) {
            timeStr = "dawn";
        } else if (timeOfDay < 12000L) {
            timeStr = "day";
        } else if (timeOfDay < 13000L) {
            timeStr = "dusk";
        } else {
            timeStr = "night";
        }
        worldState.addProperty("day", day);
        worldState.addProperty("time", timeStr);
        worldState.addProperty("dimension", level.dimension().location().toString());
        worldState.addProperty("health", Math.round(player.getHealth() * 10.0) / 10.0);
        worldState.addProperty("food", player.getFoodData().getFoodLevel());

        Holder<Biome> biomeHolder = level.getBiome(player.blockPosition());
        String biomeKey = biomeHolder.unwrapKey().map(ResourceKey::location).map(Object::toString).orElse("unknown");
        worldState.addProperty("biome", biomeKey);

        JsonArray eventsArr = new JsonArray();
        List<String> recentEvents = memoryData.getRecentEvents();
        int startEv = Math.max(0, recentEvents.size() - 3);
        for (int i = startEv; i < recentEvents.size(); i++) {
            eventsArr.add(recentEvents.get(i));
        }
        worldState.add("recent_events", eventsArr);

        sb.append("CURRENT WORLD STATE:\n").append(worldState).append("\n\n");

        // 2. Rolling summary
        String summary = memoryData.getSummary();
        if (summary != null && !summary.isBlank()) {
            sb.append("PREVIOUS ENCOUNTERS SUMMARY:\n").append(summary.trim()).append("\n\n");
        }

        // 3. Recent history (up to last 40 entries)
        List<MemoryEntry> recent = memoryData.getRecentEntries(40);
        if (!recent.isEmpty()) {
            sb.append("RECENT DIALOGUE HISTORY:\n");
            for (MemoryEntry entry : recent) {
                sb.append(entry.role()).append(": ").append(entry.text()).append("\n");
            }
            sb.append("\n");
        }

        // 4. Current message
        sb.append("CURRENT PLAYER MESSAGE:\nplayer: ").append(currentMessage);

        return sb.toString();
    }

    public static String buildSummaryPrompt(String previousSummary, List<MemoryEntry> newEntries) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are maintaining an internal memory summary for the entity Null watching a player.\n");
        sb.append("Summarize the ongoing interaction in under 200 words. Focus on:\n");
        sb.append("- Player's key statements, fears, promises, actions, and attitude\n");
        sb.append("- Past paranormal events and their effects on the player\n");
        sb.append("- Null's disposition, threats, and relationship with this player\n\n");

        if (previousSummary != null && !previousSummary.isBlank()) {
            sb.append("PREVIOUS SUMMARY:\n").append(previousSummary.trim()).append("\n\n");
        }

        sb.append("NEW DIALOGUE ENTRIES:\n");
        for (MemoryEntry entry : newEntries) {
            sb.append(entry.role()).append(": ").append(entry.text()).append("\n");
        }

        sb.append("\nReturn ONLY the updated summary in English, concise and factual, maximum 200 words. No intro or outro.");
        return sb.toString();
    }
}
