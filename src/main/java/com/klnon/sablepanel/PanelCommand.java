package com.klnon.sablepanel;

import com.mojang.brigadier.CommandDispatcher;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

public final class PanelCommand {

    private PanelCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("sablepanel")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("stats").executes(ctx -> {
                    StringBuilder sb = new StringBuilder("sablepanel stats:");
                    for (ServerLevel level : ctx.getSource().getServer().getAllLevels()) {
                        try {
                            ServerSubLevelContainer c = SubLevelContainer.getContainer(level);
                            if (c == null) {
                                continue;
                            }
                            int loaded = c.getLoadedCount();
                            int occupancy = c.getOccupancy().cardinality();
                            int tickets = c.getAllTickets().size();
                            if (loaded == 0 && occupancy == 0 && tickets == 0) {
                                continue;
                            }
                            sb.append("\n  ").append(level.dimension().location())
                                    .append(" loaded=").append(loaded)
                                    .append(" occupancy=").append(occupancy)
                                    .append(" tickets=").append(tickets);
                        } catch (Throwable ignored) {
                        }
                    }
                    String msg = sb.toString();
                    ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
                    return 1;
                })));
    }
}
