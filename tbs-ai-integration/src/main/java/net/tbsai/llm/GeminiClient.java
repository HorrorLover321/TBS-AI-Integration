package net.tbsai.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.tbsai.TbsAiMod;
import net.tbsai.config.TbsAiConfig;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class GeminiClient implements LlmClient {
    private static final String BASE_ENDPOINT = "https://generativelanguage.googleapis.com/v1beta";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
    private final HttpClient httpClient;
    private final String apiKey;
    private final String model;
    private final double temperature;
    private final int maxTokens;

    public GeminiClient(String apiKey, String model, double temperature, int maxTokens) {
        this.apiKey = apiKey != null ? apiKey.trim() : "";
        this.model = (model != null && !model.isBlank()) ? model.trim() : "gemini-3.5-flash-lite";
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    private String sanitize(String str) {
        if (str == null || apiKey.isEmpty()) {
            return str;
        }
        return str.replace(apiKey, "***");
    }

    @Override
    public CompletableFuture<LlmResponse> generate(String systemPrompt, String userPrompt) {
        if (apiKey.isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Gemini API key is not configured"));
        }

        JsonObject root = new JsonObject();

        if (systemPrompt != null && !systemPrompt.isBlank()) {
            JsonObject sysInst = new JsonObject();
            JsonArray sysParts = new JsonArray();
            JsonObject part = new JsonObject();
            part.addProperty("text", systemPrompt);
            sysParts.add(part);
            sysInst.add("parts", sysParts);
            root.add("system_instruction", sysInst);
        }

        JsonArray contents = new JsonArray();
        JsonObject userMsg = new JsonObject();
        JsonArray userParts = new JsonArray();
        JsonObject userPart = new JsonObject();
        userPart.addProperty("text", userPrompt);
        userParts.add(userPart);
        userMsg.add("parts", userParts);
        contents.add(userMsg);
        root.add("contents", contents);

        JsonObject genConfig = new JsonObject();
        genConfig.addProperty("temperature", temperature);
        genConfig.addProperty("maxOutputTokens", maxTokens);
        genConfig.addProperty("responseMimeType", "application/json");
        root.add("generationConfig", genConfig);

        String jsonBody = root.toString();
        String url = BASE_ENDPOINT + "/models/" + URLEncoder.encode(model, StandardCharsets.UTF_8)
                + ":generateContent?key=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8);

        long start = System.currentTimeMillis();
        return executeWithRetry(url, jsonBody, 1).thenApply(resp -> {
            long latency = System.currentTimeMillis() - start;
            int code = resp.statusCode();
            if (code < 200 || code >= 300) {
                throw new RuntimeException("Gemini HTTP " + code + ": " + sanitize(resp.body()));
            }

            String text = extractText(resp.body());
            return LlmResponse.parse(text, code, latency);
        });
    }

    @Override
    public CompletableFuture<String> summarize(String summaryPrompt) {
        if (apiKey.isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Gemini API key is not configured"));
        }

        JsonObject root = new JsonObject();
        JsonArray contents = new JsonArray();
        JsonObject userMsg = new JsonObject();
        JsonArray userParts = new JsonArray();
        JsonObject userPart = new JsonObject();
        userPart.addProperty("text", summaryPrompt);
        userParts.add(userPart);
        userMsg.add("parts", userParts);
        contents.add(userMsg);
        root.add("contents", contents);

        JsonObject genConfig = new JsonObject();
        genConfig.addProperty("temperature", 0.3);
        genConfig.addProperty("maxOutputTokens", 300);
        root.add("generationConfig", genConfig);

        String jsonBody = root.toString();
        String url = BASE_ENDPOINT + "/models/" + URLEncoder.encode(model, StandardCharsets.UTF_8)
                + ":generateContent?key=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8);

        return executeWithRetry(url, jsonBody, 1).thenApply(resp -> {
            int code = resp.statusCode();
            if (code < 200 || code >= 300) {
                throw new RuntimeException("Gemini summarize HTTP " + code + ": " + sanitize(resp.body()));
            }
            return extractText(resp.body());
        });
    }

    @Override
    public CompletableFuture<List<String>> scanModels() {
        if (apiKey.isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Gemini API key is not configured"));
        }

        String url = BASE_ENDPOINT + "/models?key=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8) + "&pageSize=1000";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> {
                    int code = resp.statusCode();
                    if (code < 200 || code >= 300) {
                        throw new RuntimeException("Gemini models HTTP " + code + ": " + sanitize(resp.body()));
                    }

                    List<String> modelsList = new ArrayList<>();
                    JsonObject obj = JsonParser.parseString(resp.body()).getAsJsonObject();
                    if (obj.has("models") && obj.get("models").isJsonArray()) {
                        for (JsonElement item : obj.getAsJsonArray("models")) {
                            if (item.isJsonObject()) {
                                JsonObject m = item.getAsJsonObject();
                                boolean canGenerate = false;
                                if (m.has("supportedGenerationMethods") && m.get("supportedGenerationMethods").isJsonArray()) {
                                    for (JsonElement method : m.getAsJsonArray("supportedGenerationMethods")) {
                                        if ("generateContent".equals(method.getAsString())) {
                                            canGenerate = true;
                                            break;
                                        }
                                    }
                                }
                                if (canGenerate && m.has("name")) {
                                    String name = m.get("name").getAsString();
                                    if (name.startsWith("models/")) {
                                        name = name.substring("models/".length());
                                    }
                                    modelsList.add(name);
                                }
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
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenCompose(response -> {
                    int code = response.statusCode();
                    // Retry once on 429 or 5xx
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

    private String extractText(String responseBody) {
        try {
            JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
            if (root.has("candidates") && root.get("candidates").isJsonArray()) {
                JsonArray candidates = root.getAsJsonArray("candidates");
                if (!candidates.isEmpty()) {
                    JsonObject first = candidates.get(0).getAsJsonObject();
                    if (first.has("content") && first.get("content").isJsonObject()) {
                        JsonObject content = first.getAsJsonObject("content");
                        if (content.has("parts") && content.get("parts").isJsonArray()) {
                            StringBuilder sb = new StringBuilder();
                            for (JsonElement p : content.getAsJsonArray("parts")) {
                                if (p.isJsonObject() && p.getAsJsonObject().has("text")) {
                                    sb.append(p.getAsJsonObject().get("text").getAsString());
                                }
                            }
                            return sb.toString();
                        }
                    }
                }
            }
        } catch (Throwable t) {
            TbsAiMod.LOGGER.error("Failed to parse Gemini response: {}", sanitize(t.getMessage()));
        }
        return "";
    }
}
