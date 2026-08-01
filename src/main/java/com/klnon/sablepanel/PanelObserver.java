package com.klnon.sablepanel;

import com.google.gson.JsonObject;
import dev.ryanhcode.sable.api.sublevel.SubLevelObserver;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import org.joml.Vector3dc;

import java.util.UUID;

/**
 * 生命周期观察者:add/remove 各写一行 JSONL。
 * 回调在容器主循环里,任何异常都必须吞掉,不能影响 sable 本体。
 */
public final class PanelObserver implements SubLevelObserver {
    private final String dim;

    public PanelObserver(String dim) {
        this.dim = dim;
    }

    @Override
    public void onSubLevelAdded(SubLevel subLevel) {
        try {
            JsonObject o = base("add", subLevel);
            if (subLevel instanceof ServerSubLevel ssl) {
                o.addProperty("runtime_id", ssl.getRuntimeId());
                UUID splitFrom = ssl.getSplitFromSubLevel();
                if (splitFrom != null) {
                    o.addProperty("split_from", splitFrom.toString());
                }
            }
            EventLog.write(o);
        } catch (Throwable t) {
            SablePanel.LOGGER.warn("sablepanel: observer add failed", t);
        }
    }

    @Override
    public void onSubLevelRemoved(SubLevel subLevel, SubLevelRemovalReason reason) {
        try {
            JsonObject o = base("remove", subLevel);
            o.addProperty("reason", reason.name());
            EventLog.write(o);
        } catch (Throwable t) {
            SablePanel.LOGGER.warn("sablepanel: observer remove failed", t);
        }
    }

    private JsonObject base(String ev, SubLevel subLevel) {
        JsonObject o = new JsonObject();
        o.addProperty("ev", ev);
        o.addProperty("dim", this.dim);
        o.addProperty("uuid", String.valueOf(subLevel.getUniqueId()));
        if (subLevel.getName() != null) {
            o.addProperty("name", subLevel.getName());
        }
        Vector3dc pos = subLevel.logicalPose().position();
        o.addProperty("x", round1(pos.x()));
        o.addProperty("y", round1(pos.y()));
        o.addProperty("z", round1(pos.z()));
        BoundingBox3dc bb = subLevel.boundingBox();
        o.addProperty("size", round1(bb.maxX() - bb.minX()) + "x"
                + round1(bb.maxY() - bb.minY()) + "x"
                + round1(bb.maxZ() - bb.minZ()));
        return o;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
