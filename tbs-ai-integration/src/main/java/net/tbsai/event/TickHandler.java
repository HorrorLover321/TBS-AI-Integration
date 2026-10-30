package net.tbsai.event;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.tbsai.TbsAiMod;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

public final class TickHandler {
    private static final List<ScheduledTask> TASKS = new ArrayList<>();
    private static long currentTick = 0;

    private static class ScheduledTask {
        final UUID playerUuid;
        final long targetTick;
        final Runnable action;

        ScheduledTask(UUID playerUuid, long targetTick, Runnable action) {
            this.playerUuid = playerUuid;
            this.targetTick = targetTick;
            this.action = action;
        }
    }

    public static synchronized void schedule(UUID playerUuid, int delayTicks, Runnable action) {
        long runTick = currentTick + Math.max(0, delayTicks);
        TASKS.add(new ScheduledTask(playerUuid, runTick, action));
    }

    public static synchronized void cancelPlayer(UUID playerUuid) {
        if (playerUuid == null) {
            return;
        }
        TASKS.removeIf(task -> playerUuid.equals(task.playerUuid));
    }

    public static synchronized void clearAll() {
        TASKS.clear();
        currentTick = 0;
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        List<Runnable> readyToRun = new ArrayList<>();
        synchronized (TickHandler.class) {
            currentTick++;
            Iterator<ScheduledTask> it = TASKS.iterator();
            while (it.hasNext()) {
                ScheduledTask task = it.next();
                if (currentTick >= task.targetTick) {
                    readyToRun.add(task.action);
                    it.remove();
                }
            }
        }

        for (Runnable action : readyToRun) {
            try {
                action.run();
            } catch (Throwable t) {
                TbsAiMod.LOGGER.error("Error executing scheduled server task: {}", t.getMessage(), t);
            }
        }
    }
}
