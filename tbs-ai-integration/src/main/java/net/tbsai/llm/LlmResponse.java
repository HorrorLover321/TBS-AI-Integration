package net.tbsai.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.tbsai.tbs.TbsEventsManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public record LlmResponse(
        List<String> messages,
        String event,
        int httpStatusCode,
        long latencyMs,
        String rawResponse
) {

    public static LlmResponse empty(int httpStatusCode, long latencyMs) {
        return new LlmResponse(Collections.emptyList(), null, httpStatusCode, latencyMs, "");
    }

    public static LlmResponse parse(String rawText, int statusCode, long latencyMs) {
        if (rawText == null || rawText.isBlank()) {
            return empty(statusCode, latencyMs);
        }

        String cleanedText = rawText.trim();
        // Remove markdown code fences if wrapped in ```json ... ```
        if (cleanedText.startsWith("```")) {
            int firstNewline = cleanedText.indexOf('\n');
            if (firstNewline != -1) {
                cleanedText = cleanedText.substring(firstNewline + 1);
            }
            int lastFences = cleanedText.lastIndexOf("```");
            if (lastFences != -1) {
                cleanedText = cleanedText.substring(0, lastFences);
            }
            cleanedText = cleanedText.trim();
        }

        List<String> messages = new ArrayList<>();
        String eventId = null;

        try {
            JsonElement element = JsonParser.parseString(cleanedText);
            if (element.isJsonObject()) {
                JsonObject obj = element.getAsJsonObject();

                if (obj.has("messages") && obj.get("messages").isJsonArray()) {
                    JsonArray arr = obj.getAsJsonArray("messages");
                    for (JsonElement item : arr) {
                        if (item.isJsonPrimitive()) {
                            String msg = cleanLine(item.getAsString());
                            if (!msg.isEmpty()) {
                                messages.add(msg);
                            }
                        }
                    }
                } else if (obj.has("message") && obj.get("message").isJsonPrimitive()) {
                    String msg = cleanLine(obj.get("message").getAsString());
                    if (!msg.isEmpty()) {
                        messages.add(msg);
                    }
                }

                if (obj.has("event") && !obj.get("event").isJsonNull()) {
                    String candidate = obj.get("event").getAsString();
                    if (candidate != null) {
                        candidate = candidate.trim().toLowerCase();
                        if (TbsEventsManager.isValidEventId(candidate)) {
                            eventId = candidate;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            // Fallback: split raw text into lines
            messages.clear();
            eventId = null;
        }

        // If JSON parsing yielded no messages, fallback to plain text lines
        if (messages.isEmpty()) {
            String[] lines = rawText.split("\r?\n");
            for (String line : lines) {
                String cl = cleanLine(line);
                if (!cl.isEmpty()) {
                    messages.add(cl);
                    if (messages.size() >= 2) {
                        break;
                    }
                }
            }
            eventId = null;
        }

        // Enforce maximum 2 lines returned by LLM format
        if (messages.size() > 2) {
            messages = new ArrayList<>(messages.subList(0, 2));
        }

        return new LlmResponse(Collections.unmodifiableList(messages), eventId, statusCode, latencyMs, rawText);
    }

    private static String cleanLine(String line) {
        if (line == null) {
            return "";
        }
        // Remove markdown asterisks, backticks, hashes, brackets, quotes
        line = line.replaceAll("[`*_#\"><]", "");
        // Remove section sign color codes (§x)
        line = line.replaceAll("§[0-9a-fk-orA-FK-OR]", "");
        // Strip emojis and non-standard symbols
        line = line.replaceAll("[\\p{So}\\p{Cn}]", "");
        // Replace multiple spaces and trim
        line = line.replaceAll("\\s+", " ").trim();
        // Limit to 160 characters
        if (line.length() > 160) {
            line = line.substring(0, 160).trim();
        }
        return line;
    }
}
