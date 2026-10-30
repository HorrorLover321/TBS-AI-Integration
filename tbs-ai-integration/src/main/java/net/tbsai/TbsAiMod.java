package net.tbsai;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.tbsai.client.ClientSetup;
import net.tbsai.command.TbsAiCommands;
import net.tbsai.config.TbsAiConfig;
import net.tbsai.event.ChatHandler;
import net.tbsai.event.TickHandler;
import net.tbsai.llm.LlmManager;
import org.slf4j.Logger;

@Mod(TbsAiMod.MOD_ID)
public class TbsAiMod {
    public static final String MOD_ID = "tbsai";
    public static final Logger LOGGER = LogUtils.getLogger();

    public TbsAiMod(IEventBus modEventBus, ModContainer modContainer) {
        LOGGER.info("Initializing TBS: AI Integration (modId={})", MOD_ID);

        // Register configuration
        modContainer.registerConfig(ModConfig.Type.COMMON, TbsAiConfig.SPEC, "tbsai-common.toml");

        // React to config changes
        modEventBus.addListener((ModConfigEvent.Loading event) -> {
            if (event.getConfig().getSpec() == TbsAiConfig.SPEC) {
                LlmManager.recreateClient();
            }
        });
        modEventBus.addListener((ModConfigEvent.Reloading event) -> {
            if (event.getConfig().getSpec() == TbsAiConfig.SPEC) {
                LlmManager.recreateClient();
            }
        });

        // Register client GUI factory if running on client dist
        if (FMLEnvironment.dist.isClient()) {
            ClientSetup.init(modContainer);
        }

        // Register gameplay event listeners
        NeoForge.EVENT_BUS.register(ChatHandler.class);
        NeoForge.EVENT_BUS.register(TickHandler.class);
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        TbsAiCommands.register(event.getDispatcher());
    }
}
