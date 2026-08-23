package com.klnon.sablepanel.panel.ops;

import static com.klnon.sablepanel.panel.api.PanelResponse.messageOf;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.klnon.sablepanel.SablePanel;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 孤儿收养(依赖闭包同 tick 一起),不动盘,全部经 sable 原生加载管线入场。 */
public final class AdoptOps {
    private final OpKit kit;

    AdoptOps(OpKit kit) {
        this.kit = kit;
    }

    /** 孤儿收养(依赖闭包一起):不动盘,全部经 sable 原生 loadHoldingSubLevel 入场 */
    public JsonObject adopt(UUID uuid) throws Exception {
        JsonObject result = adoptOne(uuid);
        this.kit.rescan.run();
        return result;
    }

    /** 收养单体但不触发重扫。批量路径用它,整批结束后只扫一次。 */
    private JsonObject adoptOne(UUID uuid) throws Exception {
        Map<UUID, OpKit.MemberPlan> chain = this.kit.prepareChain(uuid);
        if (chain.isEmpty()) throw new IllegalStateException("找不到该体的存档条目");
        // 链闭包触到 OpKit.MAX_CHAIN 上限说明还有成员没进本次收养,要让用户知道是部分收养
        boolean truncated = chain.size() >= OpKit.MAX_CHAIN;
        if (truncated) {
            SablePanel.LOGGER.warn("sablepanel: adopt {} dependency closure hit the {} member cap, adopting partially",
                    uuid, OpKit.MAX_CHAIN);
        }
        JsonObject result = this.kit.onMainUntilComplete(() -> {
            JsonObject per = new JsonObject();
            for (Map.Entry<UUID, OpKit.MemberPlan> en : chain.entrySet()) {
                UUID u = en.getKey();
                try {
                    if (this.kit.resolveLoaded(u) != null) {
                        per.addProperty(u.toString(), "already_loaded");
                        continue;
                    }
                    this.kit.loadOne(u, en.getValue());
                    per.addProperty(u.toString(), this.kit.resolveLoaded(u) != null ? "adopted" : "load_failed");
                } catch (Throwable t) {
                    per.addProperty(u.toString(), "error: " + t.getMessage());
                }
            }
            this.kit.audit("adopt", uuid, null, per.toString());
            JsonObject r = new JsonObject();
            r.addProperty("ok", this.kit.resolveLoaded(uuid) != null);
            if (truncated) r.addProperty("truncated", OpKit.MAX_CHAIN);
            r.add("chain", per);
            return r;
        });
        return result;
    }

    /**
     * 批量收养。前端从前是对每个孤儿体单独 POST 一次,N 个体就是 N 次作业提交 + N 次全量
     * bodies 刷新,选区一大就线性放大;现在整批一个作业,失败项结构化留在 results/failed 里。
     * <p>
     * 逐项走 {@link #adoptOne} 而不是 {@code adopt}:后者每次都要重扫一遍磁盘,合并门闩只在
     * 扫描仍排队时能合并,前一次扫完了下一项照样会再排一次,N 个体最坏就是 N 次全量扫描。
     */
    public JsonObject adoptBatch(List<UUID> uuids) {
        JsonArray results = new JsonArray();
        JsonArray failed = new JsonArray();
        int ok = 0;
        int index = 0;
        for (UUID uuid : uuids) {
            JobService.phase("收养");
            JobService.detail(++index + "/" + uuids.size());
            JsonObject item = new JsonObject();
            item.addProperty("uuid", uuid.toString());
            try {
                JsonObject one = adoptOne(uuid);
                boolean adopted = one.has("ok") && one.get("ok").getAsBoolean();
                item.addProperty("ok", adopted);
                if (one.has("truncated")) item.add("truncated", one.get("truncated"));
                if (adopted) ok++;
                else failed.add(uuid.toString());
            } catch (Exception error) {
                item.addProperty("ok", false);
                item.addProperty("error", messageOf(error));
                failed.add(uuid.toString());
                SablePanel.LOGGER.warn("sablepanel: batch adopt {} failed", uuid, error);
            }
            results.add(item);
        }
        this.kit.rescan.run();   // 整批一次
        JsonObject out = new JsonObject();
        out.addProperty("ok", ok);
        out.addProperty("total", uuids.size());
        out.add("results", results);
        if (!failed.isEmpty()) out.add("failed", failed);
        return out;
    }
}
