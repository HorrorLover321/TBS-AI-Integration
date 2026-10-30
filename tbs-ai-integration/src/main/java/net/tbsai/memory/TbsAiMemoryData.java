package net.tbsai.memory;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public class TbsAiMemoryData extends SavedData {
    public static final int MAX_ENTRIES = 1000;
    private final List<MemoryEntry> entries = new ArrayList<>();
    private final List<String> recentEvents = new ArrayList<>();
    private String summary = "";
    private int entriesSinceLastSummary = 0;

    public TbsAiMemoryData() {
    }

    public static SavedData.Factory<TbsAiMemoryData> factory() {
        return new SavedData.Factory<>(
                TbsAiMemoryData::new,
                TbsAiMemoryData::load
        );
    }

    public static TbsAiMemoryData get(MinecraftServer server, UUID playerUuid) {
        if (server == null || playerUuid == null) {
            return new TbsAiMemoryData();
        }
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        if (overworld == null) {
            return new TbsAiMemoryData();
        }
        String dataKey = "tbsai_memory_" + playerUuid;
        return overworld.getDataStorage().computeIfAbsent(factory(), dataKey);
    }

    public synchronized void addEntry(String role, String text, long gameTime) {
        entries.add(new MemoryEntry(role, text, gameTime));
        entriesSinceLastSummary++;
        pruneOldEntries();
        setDirty();
    }

    public synchronized List<MemoryEntry> getRecentEntries(int count) {
        if (entries.isEmpty()) {
            return Collections.emptyList();
        }
        int fromIndex = Math.max(0, entries.size() - count);
        return new ArrayList<>(entries.subList(fromIndex, entries.size()));
    }

    public synchronized int getEntryCount() {
        return entries.size();
    }

    public synchronized String getSummary() {
        return summary;
    }

    public synchronized void setSummary(String summary) {
        this.summary = summary != null ? summary : "";
        this.entriesSinceLastSummary = 0;
        pruneOldEntries();
        setDirty();
    }

    public synchronized int getEntriesSinceLastSummary() {
        return entriesSinceLastSummary;
    }

    public synchronized void setEntriesSinceLastSummary(int count) {
        this.entriesSinceLastSummary = count;
        setDirty();
    }

    public synchronized List<String> getRecentEvents() {
        return new ArrayList<>(recentEvents);
    }

    public synchronized void addRecentEvent(String eventId) {
        if (eventId != null && !eventId.isBlank()) {
            recentEvents.add(eventId);
            while (recentEvents.size() > 5) {
                recentEvents.remove(0);
            }
            setDirty();
        }
    }

    public synchronized String getLastEvent() {
        if (recentEvents.isEmpty()) {
            return "none";
        }
        return recentEvents.get(recentEvents.size() - 1);
    }

    public synchronized void clear() {
        entries.clear();
        recentEvents.clear();
        summary = "";
        entriesSinceLastSummary = 0;
        setDirty();
    }

    public synchronized void pruneOldEntries() {
        while (entries.size() > MAX_ENTRIES) {
            entries.remove(0);
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        synchronized (this) {
            for (MemoryEntry entry : entries) {
                list.add(entry.toNbt());
            }
            tag.put("entries", list);
            tag.putString("summary", summary);
            tag.putInt("sinceSummary", entriesSinceLastSummary);

            ListTag eventsTag = new ListTag();
            for (String ev : recentEvents) {
                eventsTag.add(StringTag.valueOf(ev));
            }
            tag.put("recentEvents", eventsTag);
        }
        return tag;
    }

    public static TbsAiMemoryData load(CompoundTag tag, HolderLookup.Provider registries) {
        TbsAiMemoryData data = new TbsAiMemoryData();
        ListTag list = tag.getList("entries", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entryTag = list.getCompound(i);
            data.entries.add(MemoryEntry.fromNbt(entryTag));
        }
        data.summary = tag.getString("summary");
        data.entriesSinceLastSummary = tag.getInt("sinceSummary");

        ListTag eventsTag = tag.getList("recentEvents", Tag.TAG_STRING);
        for (int i = 0; i < eventsTag.size(); i++) {
            data.recentEvents.add(eventsTag.getString(i));
        }
        return data;
    }
}
