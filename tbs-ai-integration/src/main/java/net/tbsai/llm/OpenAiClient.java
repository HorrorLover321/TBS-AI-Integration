package net.tbsai.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.tbsai.TbsAiMod;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class OpenAiClient implements LlmClient {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
    private final HttpClient httpClient;
    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final double temperature;
    private final int maxTokens;

    public OpenAiClient(String apiKey, String baseUrl, String model, double temperature, int maxTokens) {
        this.apiKey = apiKey != null ? apiKey.trim() : "";
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.model = (model != null && !model.isBlank()) ? model.trim() : "gpt-4o-mini";
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    public static String normalizeBaseUrl(String url) {
        if (url == null || url.isBlank()) {
            return "https://api.openai.com/v1";
        }
        String normalized = url.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1).trim();
        }
        if (!normalized.endsWith("/v1")) {
            normalized = normalized + "/v1";
        }
        return normalized;
    }

    private String sanitize(String str) {
        if (str == null || apiKey.isEmpty()) {
            return str;
        }
        return str.replace(apiKey, "***");
    }

    @Override
    public CompletableFuture<LlmResponse> generate(String systemPrompt, String userPrompt) {
        JsonObject root = new JsonObject();
        root.addProperty("model", model);
        root.addProperty("temperature", temperature);
        root.addProperty("max_tokens", maxTokens);

        JsonObject respFormat = new JsonObject();
        respFormat.addProperty("type", "json_object");
        root.add("response_format", respFormat);

        JsonArray messages = new JsonArray();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            JsonObject sys = new JsonObject();
            sys.addProperty("role", "system");
            sys.addProperty("content", systemPrompt);
            messages.add(sys);
        }

        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", userPrompt);
        messages.add(user);

        root.add("messages", messages);

        String jsonBody = root.toString();
        String url = baseUrl + "/chat/completions";

        long start = System.currentTimeMillis();
        return executeWithRetry(url, jsonBody, 1).thenApply(resp -> {
            long latency = System.currentTimeMillis() - start;
            int code = resp.statusCode();
            if (code < 200 || code >= 300) {
                throw new RuntimeException("OpenAI HTTP " + code + ": " + sanitize(resp.body()));
            }

            String content = extractContent(resp.body());
            return LlmResponse.parse(content, code, latency);
        });
    }

    @Override
    public CompletableFuture<String> summarize(String summaryPrompt) {
        JsonObject root = new JsonObject();
        root.addProperty("model", model);
        root.addProperty("temperature", 0.3);
        root.addProperty("max_tokens", 300);

        JsonArray messages = new JsonArray();
        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", summaryPrompt);
        messages.add(user);
        root.add("messages", messages);

        String jsonBody = root.toString();
        String url = baseUrl + "/chat/completions";

        return executeWithRetry(url, jsonBody, 1).thenApply(resp -> {
            int code = resp.statusCode();
            if (code < 200 || code >= 300) {
                throw new RuntimeException("OpenAI summarize HTTP " + code + ": " + sanitize(resp.body()));
            }
            return extractContent(resp.body());
        });
    }

    @Override
    public CompletableFuture<List<String>> scanModels() {
        String url = baseUrl + "/models";
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .GET();

        if (!apiKey.isEmpty()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }

        return httpClient.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> {
                    int code = resp.statusCode();
                    if (code < 200 || code >= 300) {
                        throw new RuntimeException("OpenAI models HTTP " + code + ": " + sanitize(resp.body()));
                    }

                    List<String> modelsList = new ArrayList<>();
                    JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
                    if (root.has("data") && root.get("data").isJsonArray()) {
                        for (JsonElement el : root.getAsJsonArray("data")) {
                            if (el.isJsonObject() && el.getAsJsonObject().has("id")) {
                                modelsList.add(el.getAsJsonObject().get("id").getAsString());
                            }
                        }
                    }
                    Collections.sort(modelsList);
                    return modelsList;
                });
    }

    @Override
    public CompletableFuture<LlmResponse> testConnection() {
        String testPrompt = "Return {\"messages\": [\"I see you\"], \"event\": null}";
        return generate("You are Null. Return only valid JSON.", testPrompt);
    }

    private CompletableFuture<HttpResponse<String>> executeWithRetry(String url, String body, int retriesLeft) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));

        if (!apiKey.isEmpty()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }

        return httpClient.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString())
                .thenCompose(response -> {
                    int code = response.statusCode();
                    if (retriesLeft > 0 && (code == 429 || (code >= 500 && code < 600))) {
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException ignored) {
                        }
                        return executeWithRetry(url, body, retriesLeft - 1);
                    }
                    return CompletableFuture.completedFuture(response);
                });
    }

    private String extractContent(String responseBody) {
        try {
            JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
            if (root.has("choices") && root.get("choices").isJsonArray()) {
                JsonArray choices = root.getAsJsonArray("choices");
                if (!choices.isEmpty()) {
                    JsonObject choice = choices.get(0).getAsJsonObject();
                    if (choice.has("message") && choice.get("message").isJsonObject()) {
                        JsonObject msg = choice.getAsJsonObject("message");
                        if (msg.has("content")) {
                            return msg.get("content").getAsString();
                        }
                    }
                }
            }
        } catch (Throwable t) {
            TbsAiMod.LOGGER.error("Failed to parse OpenAI response: {}", sanitize(t.getMessage()));
        }
        return "";
    }
}
