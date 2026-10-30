package net.tbsai.config;

public enum ProviderType {
    GEMINI("Google AI Studio (Gemini)"),
    OPENAI_COMPATIBLE("OpenAI-Compatible");

    private final String displayName;

    ProviderType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
