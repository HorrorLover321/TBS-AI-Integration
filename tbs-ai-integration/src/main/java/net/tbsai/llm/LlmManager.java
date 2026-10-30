package net.tbsai.llm;

import net.tbsai.TbsAiMod;
import net.tbsai.config.ProviderType;
import net.tbsai.config.TbsAiConfig;

public final class LlmManager {
    private static volatile LlmClient activeClient = null;

    private LlmManager() {
    }

    public static LlmClient getClient() {
        if (activeClient == null) {
            synchronized (LlmManager.class) {
                if (activeClient == null) {
                    activeClient = createFromConfig();
                }
            }
        }
        return activeClient;
    }

    public static synchronized void recreateClient() {
        activeClient = createFromConfig();
        if (TbsAiConfig.DEBUG_LOGGING.get()) {
            TbsAiMod.LOGGER.debug("Recreated LLM client for provider {}", TbsAiConfig.PROVIDER.get());
        }
    }

    public static LlmClient createClient(ProviderType provider, String apiKey, String baseUrl, String model, double temperature, int maxTokens) {
        if (provider == ProviderType.GEMINI) {
            return new GeminiClient(apiKey, model, temperature, maxTokens);
        } else {
            return new OpenAiClient(apiKey, baseUrl, model, temperature, maxTokens);
        }
    }

    private static LlmClient createFromConfig() {
        ProviderType provider = TbsAiConfig.PROVIDER.get();
        String apiKey = TbsAiConfig.API_KEY.get();
        String baseUrl = TbsAiConfig.BASE_URL.get();
        String model = TbsAiConfig.MODEL.get();
        double temperature = TbsAiConfig.TEMPERATURE.get();
        int maxTokens = TbsAiConfig.MAX_TOKENS.get();

        return createClient(provider, apiKey, baseUrl, model, temperature, maxTokens);
    }
}
