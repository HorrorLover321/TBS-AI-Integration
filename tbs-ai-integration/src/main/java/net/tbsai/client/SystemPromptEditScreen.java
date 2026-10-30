package net.tbsai.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

public class SystemPromptEditScreen extends Screen {
    private final Screen parent;
    private final String initialPrompt;
    private final Consumer<String> onSave;
    private MultiLineEditBox editBox;

    public SystemPromptEditScreen(Screen parent, String initialPrompt, Consumer<String> onSave) {
        super(Component.translatable("tbsai.config.system_prompt.title"));
        this.parent = parent;
        this.initialPrompt = initialPrompt != null ? initialPrompt : "";
        this.onSave = onSave;
    }

    @Override
    protected void init() {
        int boxWidth = Math.min(this.width - 40, 500);
        int boxHeight = this.height - 90;
        int boxX = (this.width - boxWidth) / 2;
        int boxY = 40;

        editBox = new MultiLineEditBox(
                this.font,
                boxX,
                boxY,
                boxWidth,
                boxHeight,
                Component.translatable("tbsai.config.system_prompt.placeholder"),
                Component.translatable("tbsai.config.system_prompt.title")
        );
        editBox.setCharacterLimit(8192);
        editBox.setValue(initialPrompt);
        this.addRenderableWidget(editBox);

        int buttonWidth = 120;
        int buttonY = this.height - 35;
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> {
            onSave.accept(editBox.getValue());
            this.minecraft.setScreen(parent);
        }).bounds(this.width / 2 - buttonWidth - 5, buttonY, buttonWidth, 20).build());

        this.addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> {
            this.minecraft.setScreen(parent);
        }).bounds(this.width / 2 + 5, buttonY, buttonWidth, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 12, 0xFFFFFF);
        graphics.drawCenteredString(this.font, Component.translatable("tbsai.config.system_prompt.hint"), this.width / 2, 25, 0x888888);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }
}
