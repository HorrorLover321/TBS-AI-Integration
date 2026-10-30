package net.tbsai.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class TbsAiConfig {
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.EnumValue<ProviderType> PROVIDER;
    public static final ModConfigSpec.ConfigValue<String> API_KEY;
    public static final ModConfigSpec.ConfigValue<String> BASE_URL;
    public static final ModConfigSpec.ConfigValue<String> MODEL;
    public static final ModConfigSpec.DoubleValue TEMPERATURE;
    public static final ModConfigSpec.IntValue MAX_TOKENS;

    public static final ModConfigSpec.BooleanValue EVENTS_ENABLED;
    public static final ModConfigSpec.IntValue EVENT_CHANCE;
    public static final ModConfigSpec.IntValue EVENT_COOLDOWN_MINUTES;
    public static final ModConfigSpec.IntValue MAX_EVENTS_PER_HOUR;

    public static final ModConfigSpec.BooleanValue RESPECT_NATIVE_RESPONSES;
    public static final ModConfigSpec.BooleanValue MEMORY_ENABLED;
    public static final ModConfigSpec.IntValue SUMMARY_EVERY;
    public static final ModConfigSpec.ConfigValue<String> SYSTEM_PROMPT_OVERRIDE;
    public static final ModConfigSpec.BooleanValue DEBUG_LOGGING;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.comment("TBS: AI Integration Configuration").push("general");

        PROVIDER = builder
                .comment("LLM Provider to use (GEMINI or OPENAI_COMPATIBLE)")
                .defineEnum("provider", ProviderType.GEMINI);

        API_KEY = builder
                .comment("API key for the selected provider (stored in plain text, masked in GUI/logs)")
                .define("apiKey", "");

        BASE_URL = builder
                .comment("Base URL for OpenAI-compatible provider (e.g. https://api.openai.com/v1)")
                .define("baseUrl", "https://api.openai.com/v1");

        MODEL = builder
                .comment("Model name to query")
                .define("model", "gemini-3.5-flash-lite");

        TEMPERATURE = builder
                .comment("Sampling temperature (0.0 to 1.5)")
                .defineInRange("temperature", 0.9, 0.0, 1.5);

        MAX_TOKENS = builder
                .comment("Maximum tokens for chat completion response (64 to 1024)")
                .defineInRange("maxTokens", 300, 64, 1024);

        EVENTS_ENABLED = builder
                .comment("Whether Null can trigger random TBS events from responses")
                .define("eventsEnabled", true);

        EVENT_CHANCE = builder
                .comment("Percentage chance (0-100) to execute an event when chosen by LLM")
                .defineInRange("eventChance", 25, 0, 100);

        EVENT_COOLDOWN_MINUTES = builder
                .comment("Cooldown in minutes between random event triggers per player")
                .defineInRange("eventCooldownMinutes", 4, 0, 1440);

        MAX_EVENTS_PER_HOUR = builder
                .comment("Maximum number of random events allowed per player in a rolling hour")
                .defineInRange("maxEventsPerHour", 20, 1, 1000);

        RESPECT_NATIVE_RESPONSES = builder
                .comment("If true, native TBS chat responses take priority when matched")
                .define("respectNativeResponses", true);

        MEMORY_ENABLED = builder
                .comment("Whether to maintain dialogue history and world summaries in SavedData")
                .define("memoryEnabled", true);

        SUMMARY_EVERY = builder
                .comment("Generate a new rolling summary every N recorded entries")
                .defineInRange("summaryEvery", 50, 5, 500);

        SYSTEM_PROMPT_OVERRIDE = builder
                .comment("Custom system prompt override (leave empty for built-in Null persona)")
                .define("systemPromptOverride", "");

        DEBUG_LOGGING = builder
                .comment("Enable verbose debug logging")
                .define("debugLogging", false);

        builder.pop();

        SPEC = builder.build();
    }

    private TbsAiConfig() {
    }

    /**
     * Sanitizes strings (urls, error messages, etc.) replacing active API key with '***'.
     */
    public static String sanitize(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String key = API_KEY.get();
        if (key != null && !key.isBlank() && key.length() >= 4) {
            text = text.replace(key, "***");
        }
        return text;
    }
}
