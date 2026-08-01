package com.klnon.sablepanel;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.ryanhcode.sable.api.physics.mass.MassData;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.ticket.SubLevelTicketInfo;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.holding.GlobalSavedSubLevelPointer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.joml.Quaterniondc;
import org.joml.Vector3dc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * /sablepanel dump:全维度运行时快照,含每个体的完整画像与存储指针
 * (last_pointer/ticket pointer 可与 tools/sable_scan.py 的静态扫描 key 直接对齐)。
 */
public final class SnapshotDumper {
    private static final Gson GSON = new Gson();
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private SnapshotDumper() {
    }

    public static Path dump(MinecraftServer server) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("ts", System.currentTimeMillis());
        JsonArray dims = new JsonArray();
        for (ServerLevel level : server.getAllLevels()) {
            try {
                JsonObject d = dumpLevel(level);
                if (d != null) {
                    dims.add(d);
                }
            } catch (Throwable t) {
                SablePanel.LOGGER.warn("sablepanel: dump failed for {}", level.dimension().location(), t);
                JsonObject err = new JsonObject();
                err.addProperty("dim", level.dimension().location().toString());
                err.addProperty("error", String.valueOf(t));
                dims.add(err);
            }
        }
        root.add("dims", dims);

        Path dir = EventLog.logDir();
        Files.createDirectories(dir);
        Path file = dir.resolve("snapshot-" + TS.format(LocalDateTime.now()) + ".json");
        Files.writeString(file, GSON.toJson(root));
        return file;
    }

    private static JsonObject dumpLevel(ServerLevel level) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return null;
        }
        int loaded = container.getLoadedCount();
        int occupancy = container.getOccupancy().cardinality();
        Map<UUID, SubLevelTicketInfo> tickets = container.getAllTickets();
        if (loaded == 0 && occupancy == 0 && tickets.isEmpty()) {
            return null;
        }

        JsonObject o = new JsonObject();
        o.addProperty("dim", level.dimension().location().toString());
        o.addProperty("loaded", loaded);
        o.addProperty("occupancy", occupancy);

        Set<UUID> forceLoaded = new HashSet<>();
        try {
            for (ServerSubLevel s : container.collectForceLoadedSubLevels()) {
                forceLoaded.add(s.getUniqueId());
            }
        } catch (Throwable t) {
            o.addProperty("force_loaded_error", String.valueOf(t));
        }

        JsonArray bodies = new JsonArray();
        for (ServerSubLevel sl : container.getAllSubLevels()) {
            try {
                bodies.add(dumpBody(sl, forceLoaded));
            } catch (Throwable t) {
                JsonObject err = new JsonObject();
                err.addProperty("uuid", String.valueOf(sl.getUniqueId()));
                err.addProperty("error", String.valueOf(t));
                bodies.add(err);
            }
        }
        o.add("sub_levels", bodies);

        JsonArray ticketArr = new JsonArray();
        for (Map.Entry<UUID, SubLevelTicketInfo> entry : tickets.entrySet()) {
            JsonObject t = new JsonObject();
            t.addProperty("uuid", entry.getKey().toString());
            GlobalSavedSubLevelPointer p = entry.getValue().getPointer();
            if (p != null) {
                addPointer(t, p);
            }
            try {
                t.addProperty("ticket_count", entry.getValue().tickets().size());
            } catch (Throwable ignored) {
            }
            ticketArr.add(t);
        }
        o.add("tickets", ticketArr);
        return o;
    }

    private static JsonObject dumpBody(ServerSubLevel sl, Set<UUID> forceLoaded) {
        JsonObject o = new JsonObject();
        o.addProperty("uuid", String.valueOf(sl.getUniqueId()));
        if (sl.getName() != null) {
            o.addProperty("name", sl.getName());
        }
        o.addProperty("runtime_id", sl.getRuntimeId());
        o.addProperty("removed", sl.isRemoved());
        o.addProperty("force_loaded", forceLoaded.contains(sl.getUniqueId()));

        Vector3dc pos = sl.logicalPose().position();
        o.add("pos", vec(pos.x(), pos.y(), pos.z()));
        Quaterniondc q = sl.logicalPose().orientation();
        JsonArray quat = new JsonArray();
        quat.add(round3(q.x()));
        quat.add(round3(q.y()));
        quat.add(round3(q.z()));
        quat.add(round3(q.w()));
        o.add("quat", quat);

        BoundingBox3dc bb = sl.boundingBox();
        o.add("size", vec(bb.maxX() - bb.minX(), bb.maxY() - bb.minY(), bb.maxZ() - bb.minZ()));

        o.addProperty("lin_vel", round3(sl.latestLinearVelocity.length()));
        o.addProperty("ang_vel", round3(sl.latestAngularVelocity.length()));

        try {
            MassData mass = sl.getMassTracker();
            if (mass != null) {
                o.addProperty("mass", Math.round(mass.getMass() * 100.0) / 100.0);
                Vector3dc com = mass.getCenterOfMass();
                o.add("com", vec(com.x(), com.y(), com.z()));
            }
        } catch (Throwable ignored) {
        }

        try {
            o.addProperty("tracking_players", sl.getTrackingPlayers().size());
        } catch (Throwable ignored) {
        }

        UUID splitFrom = sl.getSplitFromSubLevel();
        if (splitFrom != null) {
            o.addProperty("split_from", splitFrom.toString());
        }

        GlobalSavedSubLevelPointer pointer = sl.getLastSerializationPointer();
        if (pointer != null) {
            addPointer(o, pointer);
        }

        CompoundTag userData = sl.getUserDataTag();
        if (userData != null && !userData.isEmpty()) {
            JsonArray keys = new JsonArray();
            for (String key : userData.getAllKeys()) {
                keys.add(key);
            }
            o.add("user_data_keys", keys);
        }
        return o;
    }

    /** 与 sable_scan.py 的 (regionX, regionZ, storageIndex, subLevelIndex) key 对齐 */
    private static void addPointer(JsonObject o, GlobalSavedSubLevelPointer p) {
        JsonObject ptr = new JsonObject();
        ptr.addProperty("region", p.chunkPos().getRegionX() + "." + p.chunkPos().getRegionZ());
        ptr.addProperty("storage", p.storageIndex());
        ptr.addProperty("index", p.subLevelIndex());
        o.add("pointer", ptr);
    }

    private static JsonArray vec(double x, double y, double z) {
        JsonArray a = new JsonArray();
        a.add(round1(x));
        a.add(round1(y));
        a.add(round1(z));
        return a;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
