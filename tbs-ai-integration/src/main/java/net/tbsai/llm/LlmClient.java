package net.tbsai.llm;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface LlmClient {

    CompletableFuture<LlmResponse> generate(String systemPrompt, String userPrompt);

    CompletableFuture<String> summarize(String summaryPrompt);

    CompletableFuture<List<String>> scanModels();

    CompletableFuture<LlmResponse> testConnection();
}
