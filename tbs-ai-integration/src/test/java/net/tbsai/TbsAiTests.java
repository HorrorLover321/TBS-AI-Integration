package net.tbsai;

import net.minecraft.nbt.CompoundTag;
import net.tbsai.config.TbsAiConfig;
import net.tbsai.event.ChatHandler;
import net.tbsai.llm.LlmResponse;
import net.tbsai.llm.OpenAiClient;
import net.tbsai.llm.PromptBuilder;
import net.tbsai.memory.MemoryEntry;
import net.tbsai.tbs.TbsEventsManager;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class TbsAiTests {

    @Test
    void testCatalogHasExact90Events() {
        assertEquals(90, TbsEventsManager.getCatalog().size(), "Catalog must contain exactly 90 random events");
        assertTrue(TbsEventsManager.isValidEventId("null_scare"));
        assertTrue(TbsEventsManager.isValidEventId("behind_you"));
        assertTrue(TbsEventsManager.isValidEventId("bsod"));
        assertTrue(TbsEventsManager.isValidEventId("bad_sun"));
        assertFalse(TbsEventsManager.isValidEventId("story_event_custom"));
        assertFalse(TbsEventsManager.isValidEventId("unknown_event"));
        assertFalse(TbsEventsManager.isValidEventId(null));
    }

    @Test
    void testLlmResponseJsonParsing() {
        String json = """
                {
                  "messages": [
                    "I see you.",
                    "Why are you here?"
                  ],
                  "event": "null_scare"
                }
                """;
        LlmResponse resp = LlmResponse.parse(json, 200, 150);
        assertEquals(2, resp.messages().size());
        assertEquals("I see you.", resp.messages().get(0));
        assertEquals("Why are you here?", resp.messages().get(1));
        assertEquals("null_scare", resp.event());
    }

    @Test
    void testLlmResponseWithMarkdownFence() {
        String fenced = """
                ```json
                {
                  "messages": ["Behind you."],
                  "event": "behind_you"
                }
                ```
                """;
        LlmResponse resp = LlmResponse.parse(fenced, 200, 120);
        assertEquals(1, resp.messages().size());
        assertEquals("Behind you.", resp.messages().get(0));
        assertEquals("behind_you", resp.event());
    }

    @Test
    void testLlmResponseInvalidJsonFallback() {
        String raw = "I hear you in the dark.\nNothing can be changed.\nAnother line.";
        LlmResponse resp = LlmResponse.parse(raw, 200, 100);
        assertEquals(2, resp.messages().size(), "Fallback must take at most 2 lines");
        assertEquals("I hear you in the dark.", resp.messages().get(0));
        assertEquals("Nothing can be changed.", resp.messages().get(1));
        assertNull(resp.event(), "Fallback must have event = null");
    }

    @Test
    void testLlmResponseSanitization() {
        String raw = """
                {
                  "messages": ["**Look** behind _you_ §4now! 🎮"]
                }
                """;
        LlmResponse resp = LlmResponse.parse(raw, 200, 100);
        assertEquals(1, resp.messages().size());
        assertEquals("Look behind you now!", resp.messages().get(0));
    }

    @Test
    void testOpenAiBaseUrlNormalization() {
        assertEquals("https://api.openai.com/v1", OpenAiClient.normalizeBaseUrl("https://api.openai.com/v1"));
        assertEquals("https://api.openai.com/v1", OpenAiClient.normalizeBaseUrl("https://api.openai.com/v1/"));
        assertEquals("https://api.openai.com/v1", OpenAiClient.normalizeBaseUrl("https://api.openai.com"));
        assertEquals("http://localhost:11434/v1", OpenAiClient.normalizeBaseUrl("http://localhost:11434"));
        assertEquals("http://localhost:11434/v1", OpenAiClient.normalizeBaseUrl("http://localhost:11434/"));
        assertEquals("https://api.openai.com/v1", OpenAiClient.normalizeBaseUrl(""));
        assertEquals("https://api.openai.com/v1", OpenAiClient.normalizeBaseUrl(null));
    }

    @Test
    void testMemoryEntryNbt() {
        MemoryEntry entry = new MemoryEntry("player", "hello", 12345L);
        CompoundTag tag = entry.toNbt();
        MemoryEntry loaded = MemoryEntry.fromNbt(tag);
        assertEquals("player", loaded.role());
        assertEquals("hello", loaded.text());
        assertEquals(12345L, loaded.gameTime());
    }

    @Test
    void testPromptBuilderSummaryPrompt() {
        List<MemoryEntry> entries = List.of(
                new MemoryEntry("player", "Where are you?", 100L),
                new MemoryEntry("null", "Behind you.", 124L)
        );
        String prompt = PromptBuilder.buildSummaryPrompt("Previous summary text", entries);
        assertTrue(prompt.contains("Previous summary text"));
        assertTrue(prompt.contains("Where are you?"));
        assertTrue(prompt.contains("Behind you."));
    }

    @Test
    void testComputeRemainingTicks() {
        // Fast LLM response: LLM took 1000ms, target delay was 3500ms -> remaining 2500ms -> 50 ticks
        assertEquals(50, ChatHandler.computeRemainingTicks(1000L, 3500L, 2000L));

        // Exact match: LLM took exactly target delay -> remaining 0ms -> 0 ticks
        assertEquals(0, ChatHandler.computeRemainingTicks(1000L, 2500L, 3500L));

        // Slow LLM response: LLM took 6000ms, target delay was 3000ms -> remaining -3000ms -> 0 ticks
        assertEquals(0, ChatHandler.computeRemainingTicks(1000L, 3000L, 7000L));

        // Ceiling rounding checks (20 ticks per second, 50ms per tick)
        // 1ms remaining -> rounds up to 1 tick
        assertEquals(1, ChatHandler.computeRemainingTicks(0L, 2000L, 1999L));
        // 50ms remaining -> exactly 1 tick
        assertEquals(1, ChatHandler.computeRemainingTicks(0L, 2000L, 1950L));
        // 51ms remaining -> rounds up to 2 ticks
        assertEquals(2, ChatHandler.computeRemainingTicks(0L, 2000L, 1949L));
        // 100ms remaining -> exactly 2 ticks
        assertEquals(2, ChatHandler.computeRemainingTicks(0L, 2000L, 1900L));

        // Full 5000ms delay with 0ms elapsed -> 100 ticks
        assertEquals(100, ChatHandler.computeRemainingTicks(0L, 5000L, 0L));
    }

    @Test
    void testPendingMessageRecord() {
        ChatHandler.PendingMessage pending = new ChatHandler.PendingMessage("hello null", 123456789L);
        assertEquals("hello null", pending.text());
        assertEquals(123456789L, pending.sentAtMs());
    }
}
