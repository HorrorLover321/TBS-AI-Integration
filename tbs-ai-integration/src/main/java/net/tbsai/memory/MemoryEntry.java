package net.tbsai.memory;

import net.minecraft.nbt.CompoundTag;

public record MemoryEntry(String role, String text, long gameTime) {

    public CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putString("role", role != null ? role : "");
        tag.putString("text", text != null ? text : "");
        tag.putLong("time", gameTime);
        return tag;
    }

    public static MemoryEntry fromNbt(CompoundTag tag) {
        String role = tag.getString("role");
        String text = tag.getString("text");
        long time = tag.getLong("time");
        return new MemoryEntry(role, text, time);
    }
}
