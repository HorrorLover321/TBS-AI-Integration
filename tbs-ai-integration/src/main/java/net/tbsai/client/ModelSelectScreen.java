package net.tbsai.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.tbsai.config.ProviderType;
import net.tbsai.config.TbsAiConfig;
import net.tbsai.llm.LlmClient;
import net.tbsai.llm.LlmManager;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public class ModelSelectScreen extends Screen {
    private final TbsAiConfigScreen parent;

    // Shared in-flight scan so reopening the screen reuses the running request
    private static CompletableFuture<List<String>> inFlightScan = null;
    private static ProviderType inFlightProvider = null;

    private ModelList list;
    private Button scanButton;
    private boolean scanning = false;

    private String statusMessage = "";
    private int statusColor = 0xFFFFFF;

    public ModelSelectScreen(TbsAiConfigScreen parent) {
        super(Component.translatable("tbsai.config.model_select.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int listHeight = this.height - 92;
        int listY = 32;
        list = new ModelList(this.minecraft, this.width, listHeight, listY, 24);
        this.addWidget(list);
        refreshList();

        int btnWidth = 120;
        int btnY = this.height - 30;
        scanButton = Button.builder(Component.translatable("tbsai.config.scan"), b -> scanModelsAsync())
                .bounds(this.width / 2 - btnWidth - 5, btnY, btnWidth, 20).build();
        this.addRenderableWidget(scanButton);

        this.addRenderableWidget(Button.builder(Component.translatable("tbsai.config.model_select.back"), b -> onClose())
                .bounds(this.width / 2 + 5, btnY, btnWidth, 20).build());

        scanButton.active = !scanning;

        // Auto-scan on open when the session cache is empty; cached lists are shown without a network request
        if (!scanning && TbsAiConfigScreen.getSessionModels(parent.getSelectedProvider()).isEmpty()) {
            scanModelsAsync();
        }
    }

    private void refreshList() {
        list.clearModels();
        String currentModel = parent.getModelInput();
        for (String model : TbsAiConfigScreen.getSessionModels(parent.getSelectedProvider())) {
            list.addModel(model, model.equals(currentModel), this::selectModel);
        }
    }

    private void selectModel(String model) {
        parent.onModelPicked(model);
        onClose();
    }

    private void scanModelsAsync() {
        scanning = true;
        scanButton.active = false;
        statusMessage = Component.translatable("tbsai.config.model_select.scanning").getString();
        statusColor = 0xFFFF55;

        ProviderType provider = parent.getSelectedProvider();
        CompletableFuture<List<String>> future;
        if (inFlightScan != null && !inFlightScan.isDone() && inFlightProvider == provider) {
            future = inFlightScan;
        } else {
            LlmClient scanner = LlmManager.createClient(
                    provider,
                    parent.getApiKeyInput(),
                    parent.getBaseUrlInput(),
                    parent.getModelInput(),
                    0.9,
                    300
            );
            future = scanner.scanModels();
            inFlightScan = future;
            inFlightProvider = provider;
        }

        // The callback updates the session cache even if this screen gets closed before completion
        future.whenComplete((models, err) -> Minecraft.getInstance().execute(() -> {
            scanning = false;
            scanButton.active = true;
            if (err != null) {
                statusMessage = Component.translatable("tbsai.config.model_select.error",
                        TbsAiConfig.sanitize(err.getMessage())).getString();
                statusColor = 0xFF5555;
            } else {
                TbsAiConfigScreen.updateSessionModels(provider, models);
                parent.applyScannedDefaultModel(models);
                statusMessage = Component.translatable("tbsai.config.model_select.scanned", models.size()).getString();
                statusColor = 0x55FF55;
                if (list != null) {
                    refreshList();
                }
            }
        }));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        list.render(graphics, mouseX, mouseY, partialTick);

        graphics.drawCenteredString(this.font, this.title, this.width / 2, 10, 0xFFFFFF);

        if (!list.hasEntries()) {
            graphics.drawCenteredString(this.font,
                    Component.translatable("tbsai.config.model_select.empty"),
                    this.width / 2, this.height / 2 - 10, 0xFFAA00);
        }

        if (!statusMessage.isEmpty()) {
            graphics.drawCenteredString(this.font, Component.literal(statusMessage), this.width / 2, this.height - 45, statusColor);
        }
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }

    // Scrollable list of models; each row is a button that picks the model
    public static class ModelList extends ContainerObjectSelectionList<ModelList.Entry> {
        public ModelList(Minecraft mc, int width, int height, int y, int itemHeight) {
            super(mc, width, height, y, itemHeight);
        }

        @Override
        public int getRowWidth() {
            return 300;
        }

        public void clearModels() {
            clearEntries();
        }

        public boolean hasEntries() {
            return !children().isEmpty();
        }

        public void addModel(String model, boolean current, Consumer<String> onSelect) {
            Component label = current
                    ? Component.translatable("tbsai.config.model_select.current", model)
                    : Component.literal(model);
            Button button = Button.builder(label, b -> onSelect.accept(model))
                    .bounds(0, 0, getRowWidth(), 20).build();
            addEntry(new Entry(button));
        }

        public static class Entry extends ContainerObjectSelectionList.Entry<Entry> {
            private final AbstractWidget widget;

            public Entry(AbstractWidget widget) {
                this.widget = widget;
            }

            @Override
            public List<? extends GuiEventListener> children() {
                return List.of(widget);
            }

            @Override
            public List<? extends NarratableEntry> narratables() {
                return List.of((NarratableEntry) widget);
            }

            @Override
            public void render(GuiGraphics graphics, int index, int top, int left, int width, int height, int mouseX, int mouseY, boolean isHovered, float partialTick) {
                widget.setX(left);
                widget.setY(top + (height - widget.getHeight()) / 2);
                widget.setWidth(width);
                widget.render(graphics, mouseX, mouseY, partialTick);
            }
        }
    }
}
