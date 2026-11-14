package net.tbsai.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.tbsai.config.ProviderType;
import net.tbsai.config.TbsAiConfig;
import net.tbsai.llm.LlmClient;
import net.tbsai.llm.LlmManager;
import net.tbsai.memory.TbsAiMemoryData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class TbsAiConfigScreen extends Screen {
    private final Screen parent;

    // Session cached models
    private static List<String> cachedGeminiModels = new ArrayList<>();
    private static List<String> cachedOpenAiModels = new ArrayList<>();

    // Working values; kept across screen re-initialisation (e.g. returning from child screens)
    private ProviderType selectedProvider;
    private boolean showApiKey = false;
    private String systemPromptOverride;
    private String apiKeyValue;
    private String baseUrlValue;
    private String modelValue;
    private String temperatureValue;
    private String maxTokensValue;
    private boolean eventsEnabledValue;
    private String eventChanceValue;
    private String cooldownValue;
    private String maxPerHourValue;
    private boolean respectNativeValue;
    private boolean memoryEnabledValue;
    private String summaryEveryValue;

    // Widgets
    private ConfigList list;
    private CycleButton<ProviderType> providerButton;
    private EditBox apiKeyBox;
    private Button toggleKeyButton;
    private EditBox baseUrlBox;
    private EditBox modelBox;
    private EditBox tempBox;
    private EditBox maxTokensBox;

    private Checkbox eventsEnabledCheckbox;
    private EditBox eventChanceBox;
    private EditBox cooldownBox;
    private EditBox maxPerHourBox;

    private Checkbox respectNativeCheckbox;
    private Checkbox memoryEnabledCheckbox;
    private EditBox summaryEveryBox;

    private String statusMessage = "";
    private int statusColor = 0xFFFFFF;

    public TbsAiConfigScreen(Screen parent) {
        super(Component.translatable("tbsai.config.title"));
        this.parent = parent;
        this.selectedProvider = TbsAiConfig.PROVIDER.get();
        this.systemPromptOverride = TbsAiConfig.SYSTEM_PROMPT_OVERRIDE.get();
        this.apiKeyValue = TbsAiConfig.API_KEY.get();
        this.baseUrlValue = TbsAiConfig.BASE_URL.get();
        this.modelValue = TbsAiConfig.MODEL.get();
        this.temperatureValue = String.valueOf(TbsAiConfig.TEMPERATURE.get());
        this.maxTokensValue = String.valueOf(TbsAiConfig.MAX_TOKENS.get());
        this.eventsEnabledValue = TbsAiConfig.EVENTS_ENABLED.get();
        this.eventChanceValue = String.valueOf(TbsAiConfig.EVENT_CHANCE.get());
        this.cooldownValue = String.valueOf(TbsAiConfig.EVENT_COOLDOWN_MINUTES.get());
        this.maxPerHourValue = String.valueOf(TbsAiConfig.MAX_EVENTS_PER_HOUR.get());
        this.respectNativeValue = TbsAiConfig.RESPECT_NATIVE_RESPONSES.get();
        this.memoryEnabledValue = TbsAiConfig.MEMORY_ENABLED.get();
        this.summaryEveryValue = String.valueOf(TbsAiConfig.SUMMARY_EVERY.get());
    }

    @Override
    protected void init() {
        int listWidth = this.width;
        int listHeight = this.height - 70;
        int listY = 32;

        list = new ConfigList(this.minecraft, listWidth, listHeight, listY, 26);
        this.addWidget(list);

        Font font = this.font;
        int contentWidth = 420;
        int left = (this.width - contentWidth) / 2;

        // 1. Provider row
        providerButton = CycleButton.<ProviderType>builder(p -> Component.literal(p.getDisplayName()))
                .withValues(ProviderType.values())
                .withInitialValue(selectedProvider)
                .create(left + 140, 0, 260, 20, Component.empty(), (btn, val) -> {
                    selectedProvider = val;
                    updateFieldStates();
                });
        list.addRow(Component.translatable("tbsai.config.provider"), List.of(providerButton));

        // 2. API Key row
        apiKeyBox = new EditBox(font, left + 140, 0, 195, 20, Component.translatable("tbsai.config.api_key"));
        apiKeyBox.setMaxLength(512);
        apiKeyBox.setValue(apiKeyValue);
        updateApiKeyFormatter();

        toggleKeyButton = Button.builder(Component.literal("Show"), b -> {
            showApiKey = !showApiKey;
            b.setMessage(Component.literal(showApiKey ? "Hide" : "Show"));
            updateApiKeyFormatter();
        }).bounds(left + 340, 0, 60, 20).build();
        list.addRow(Component.translatable("tbsai.config.api_key"), List.of(apiKeyBox, toggleKeyButton));

        // 3. Base URL row
        baseUrlBox = new EditBox(font, left + 140, 0, 260, 20, Component.translatable("tbsai.config.base_url"));
        baseUrlBox.setMaxLength(512);
        baseUrlBox.setValue(baseUrlValue);
        list.addRow(Component.translatable("tbsai.config.base_url"), List.of(baseUrlBox));

        // 4. Model row
        modelBox = new EditBox(font, left + 140, 0, 150, 20, Component.translatable("tbsai.config.model"));
        modelBox.setMaxLength(128);
        modelBox.setValue(modelValue);

        Button selectModelButton = Button.builder(Component.translatable("tbsai.config.model_select"), b -> {
            captureWorkingValues();
            this.minecraft.setScreen(new ModelSelectScreen(this));
        }).bounds(left + 295, 0, 120, 20).build();
        list.addRow(Component.translatable("tbsai.config.model"), List.of(modelBox, selectModelButton));

        // 5. Sampling row: Temperature & Max Tokens
        tempBox = new EditBox(font, left + 140, 0, 70, 20, Component.literal("0.9"));
        tempBox.setValue(temperatureValue);

        maxTokensBox = new EditBox(font, left + 330, 0, 70, 20, Component.literal("300"));
        maxTokensBox.setValue(maxTokensValue);
        list.addRow(Component.translatable("tbsai.config.sampling"), List.of(tempBox, maxTokensBox));

        // 6. Events row: Enabled & Chance
        eventsEnabledCheckbox = Checkbox.builder(Component.translatable("tbsai.config.events_enabled"), font)
                .selected(eventsEnabledValue)
                .pos(left + 140, 0)
                .build();

        eventChanceBox = new EditBox(font, left + 340, 0, 60, 20, Component.literal("25"));
        eventChanceBox.setValue(eventChanceValue);
        list.addRow(Component.translatable("tbsai.config.events"), List.of(eventsEnabledCheckbox, eventChanceBox));

        // 7. Event Limits: Cooldown & Max/Hour
        cooldownBox = new EditBox(font, left + 140, 0, 70, 20, Component.literal("4"));
        cooldownBox.setValue(cooldownValue);

        maxPerHourBox = new EditBox(font, left + 330, 0, 70, 20, Component.literal("20"));
        maxPerHourBox.setValue(maxPerHourValue);
        list.addRow(Component.translatable("tbsai.config.event_limits"), List.of(cooldownBox, maxPerHourBox));

        // 8. Native Responses & Memory toggles
        respectNativeCheckbox = Checkbox.builder(Component.translatable("tbsai.config.respect_native"), font)
                .selected(respectNativeValue)
                .pos(left + 140, 0)
                .build();

        memoryEnabledCheckbox = Checkbox.builder(Component.translatable("tbsai.config.memory_enabled"), font)
                .selected(memoryEnabledValue)
                .pos(left + 290, 0)
                .build();
        list.addRow(Component.translatable("tbsai.config.toggles"), List.of(respectNativeCheckbox, memoryEnabledCheckbox));

        // 9. Summary frequency & Reset Memory
        summaryEveryBox = new EditBox(font, left + 140, 0, 70, 20, Component.literal("50"));
        summaryEveryBox.setValue(summaryEveryValue);

        Button resetMemButton = Button.builder(Component.translatable("tbsai.config.reset_memory"), b -> resetMemoryAction())
                .bounds(left + 260, 0, 140, 20).build();
        list.addRow(Component.translatable("tbsai.config.summary_reset"), List.of(summaryEveryBox, resetMemButton));

        // 10. System prompt edit & Test Connection
        Button editPromptButton = Button.builder(Component.translatable("tbsai.config.edit_prompt"), b -> {
            captureWorkingValues();
            this.minecraft.setScreen(new SystemPromptEditScreen(this, systemPromptOverride, savedPrompt -> {
                this.systemPromptOverride = savedPrompt;
            }));
        }).bounds(left + 140, 0, 125, 20).build();

        Button testConnButton = Button.builder(Component.translatable("tbsai.config.test_connection"), b -> testConnectionAsync())
                .bounds(left + 275, 0, 125, 20).build();
        list.addRow(Component.translatable("tbsai.config.prompt_test"), List.of(editPromptButton, testConnButton));

        updateFieldStates();

        // Bottom action buttons: Save & Cancel
        int btnWidth = 130;
        int btnY = this.height - 30;
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> saveAndClose())
                .bounds(this.width / 2 - btnWidth - 5, btnY, btnWidth, 20).build());

        this.addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose())
                .bounds(this.width / 2 + 5, btnY, btnWidth, 20).build());
    }

    private void updateApiKeyFormatter() {
        apiKeyBox.setFormatter((text, firstPos) -> {
            if (showApiKey) {
                return FormattedCharSequence.forward(text, Style.EMPTY);
            } else {
                return FormattedCharSequence.forward("*".repeat(text.length()), Style.EMPTY);
            }
        });
    }

    private void updateFieldStates() {
        boolean isOpenAi = selectedProvider == ProviderType.OPENAI_COMPATIBLE;
        baseUrlBox.setEditable(isOpenAi);
        baseUrlBox.setTextColor(isOpenAi ? 0xE0E0E0 : 0x707070);
    }

    // Session cache accessors shared with ModelSelectScreen
    static List<String> getSessionModels(ProviderType provider) {
        return provider == ProviderType.GEMINI ? cachedGeminiModels : cachedOpenAiModels;
    }

    static void updateSessionModels(ProviderType provider, List<String> models) {
        if (provider == ProviderType.GEMINI) {
            cachedGeminiModels = new ArrayList<>(models);
        } else {
            cachedOpenAiModels = new ArrayList<>(models);
        }
    }

    ProviderType getSelectedProvider() {
        return selectedProvider;
    }

    String getApiKeyInput() {
        return apiKeyBox.getValue().trim();
    }

    String getBaseUrlInput() {
        return baseUrlBox.getValue().trim();
    }

    String getModelInput() {
        return modelBox.getValue().trim();
    }

    void onModelPicked(String model) {
        modelValue = model;
        modelBox.setValue(model);
        statusMessage = Component.translatable("tbsai.config.model_select.selected", model).getString();
        statusColor = 0x55FF55;
    }

    void applyScannedDefaultModel(List<String> models) {
        // Fill the first scanned model only when the field is empty; manual input is never overwritten
        if (!models.isEmpty() && modelBox.getValue().isBlank() && modelValue.isBlank()) {
            modelValue = models.get(0);
            modelBox.setValue(modelValue);
        }
    }

    // Copy current widget values into working fields so they survive returning from child screens
    private void captureWorkingValues() {
        if (apiKeyBox == null) {
            return;
        }
        apiKeyValue = apiKeyBox.getValue();
        baseUrlValue = baseUrlBox.getValue();
        modelValue = modelBox.getValue();
        temperatureValue = tempBox.getValue();
        maxTokensValue = maxTokensBox.getValue();
        eventsEnabledValue = eventsEnabledCheckbox.selected();
        eventChanceValue = eventChanceBox.getValue();
        cooldownValue = cooldownBox.getValue();
        maxPerHourValue = maxPerHourBox.getValue();
        respectNativeValue = respectNativeCheckbox.selected();
        memoryEnabledValue = memoryEnabledCheckbox.selected();
        summaryEveryValue = summaryEveryBox.getValue();
    }

    private void testConnectionAsync() {
        statusMessage = "Testing connection...";
        statusColor = 0xFFFF55;

        double temp;
        try {
            temp = Double.parseDouble(tempBox.getValue().trim());
        } catch (Exception e) {
            temp = 0.9;
        }

        int tokens;
        try {
            tokens = Integer.parseInt(maxTokensBox.getValue().trim());
        } catch (Exception e) {
            tokens = 300;
        }

        LlmClient testClient = LlmManager.createClient(
                selectedProvider,
                apiKeyBox.getValue().trim(),
                baseUrlBox.getValue().trim(),
                modelBox.getValue().trim(),
                temp,
                tokens
        );

        long start = System.currentTimeMillis();
        testClient.testConnection().whenComplete((resp, err) -> {
            Minecraft.getInstance().execute(() -> {
                if (err != null) {
                    statusMessage = "Connection failed: " + TbsAiConfig.sanitize(err.getMessage());
                    statusColor = 0xFF5555;
                } else {
                    long latency = resp.latencyMs() > 0 ? resp.latencyMs() : (System.currentTimeMillis() - start);
                    statusMessage = String.format("Success! HTTP %d (%d ms)", resp.httpStatusCode(), latency);
                    statusColor = 0x55FF55;
                }
            });
        });
    }

    private void resetMemoryAction() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.hasSingleplayerServer() && mc.player != null) {
            TbsAiMemoryData.get(mc.getSingleplayerServer(), mc.player.getUUID()).clear();
            statusMessage = "Memory cleared for singleplayer world!";
            statusColor = 0x55FF55;
        } else {
            statusMessage = "Memory can only be reset while loaded in a singleplayer world.";
            statusColor = 0xFFAA00;
        }
    }

    private void saveAndClose() {
        captureWorkingValues();

        TbsAiConfig.PROVIDER.set(selectedProvider);
        TbsAiConfig.API_KEY.set(apiKeyValue.trim());
        TbsAiConfig.BASE_URL.set(baseUrlValue.trim());
        TbsAiConfig.MODEL.set(modelValue.trim());

        try {
            double temp = Double.parseDouble(temperatureValue.trim());
            TbsAiConfig.TEMPERATURE.set(Math.max(0.0, Math.min(1.5, temp)));
        } catch (Exception ignored) {
        }

        try {
            int tokens = Integer.parseInt(maxTokensValue.trim());
            TbsAiConfig.MAX_TOKENS.set(Math.max(64, Math.min(1024, tokens)));
        } catch (Exception ignored) {
        }

        TbsAiConfig.EVENTS_ENABLED.set(eventsEnabledValue);

        try {
            int chance = Integer.parseInt(eventChanceValue.trim());
            TbsAiConfig.EVENT_CHANCE.set(Math.max(0, Math.min(100, chance)));
        } catch (Exception ignored) {
        }

        try {
            int cd = Integer.parseInt(cooldownValue.trim());
            TbsAiConfig.EVENT_COOLDOWN_MINUTES.set(Math.max(0, cd));
        } catch (Exception ignored) {
        }

        try {
            int maxHour = Integer.parseInt(maxPerHourValue.trim());
            TbsAiConfig.MAX_EVENTS_PER_HOUR.set(Math.max(1, maxHour));
        } catch (Exception ignored) {
        }

        TbsAiConfig.RESPECT_NATIVE_RESPONSES.set(respectNativeValue);
        TbsAiConfig.MEMORY_ENABLED.set(memoryEnabledValue);

        try {
            int summary = Integer.parseInt(summaryEveryValue.trim());
            TbsAiConfig.SUMMARY_EVERY.set(Math.max(5, summary));
        } catch (Exception ignored) {
        }

        TbsAiConfig.SYSTEM_PROMPT_OVERRIDE.set(systemPromptOverride != null ? systemPromptOverride.trim() : "");

        TbsAiConfig.SPEC.save();
        LlmManager.recreateClient();

        this.minecraft.setScreen(parent);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        list.render(graphics, mouseX, mouseY, partialTick);

        graphics.drawCenteredString(this.font, this.title, this.width / 2, 10, 0xFFFFFF);

        if (!statusMessage.isEmpty()) {
            graphics.drawCenteredString(this.font, Component.literal(statusMessage), this.width / 2, this.height - 45, statusColor);
        }
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }

    // List container for options
    public static class ConfigList extends ContainerObjectSelectionList<ConfigList.RowEntry> {
        public ConfigList(Minecraft mc, int width, int height, int y, int itemHeight) {
            super(mc, width, height, y, itemHeight);
        }

        public void addRow(Component label, List<AbstractWidget> widgets) {
            addEntry(new RowEntry(label, widgets));
        }

        @Override
        public int getRowWidth() {
            return 420;
        }

        public static class RowEntry extends ContainerObjectSelectionList.Entry<RowEntry> {
            private final Component label;
            private final List<AbstractWidget> widgets;

            public RowEntry(Component label, List<AbstractWidget> widgets) {
                this.label = label;
                this.widgets = widgets;
            }

            @Override
            public List<? extends GuiEventListener> children() {
                return widgets;
            }

            @Override
            public List<? extends NarratableEntry> narratables() {
                return widgets;
            }

            @Override
            public void render(GuiGraphics graphics, int index, int top, int left, int width, int height, int mouseX, int mouseY, boolean isHovered, float partialTick) {
                if (label != null) {
                    graphics.drawString(Minecraft.getInstance().font, label, left, top + (height - 9) / 2, 0xFFFFFF);
                }
                for (AbstractWidget widget : widgets) {
                    widget.setY(top + (height - widget.getHeight()) / 2);
                    widget.render(graphics, mouseX, mouseY, partialTick);
                }
            }
        }
    }
}
