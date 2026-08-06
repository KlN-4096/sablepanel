package com.klnon.sablepanel.panel.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 回收站分页视图的渲染工具:目录清单/游标定位/单页调色板/字段白名单视图/固定尺寸摘要。
 * 全部无状态(调色板对象由每次 view 调用自建),字节预算与翻页循环仍在 RecycleStore.view。
 */
final class RecyclePages {
    private RecyclePages() {
    }

    /** 单页调色板条数上限,和 {@code BodyIndex} 同理:它是全表共享的,不封顶就随方块种类无限长 */
    static final int MAX_PALETTE = 20_000;
    /** 摘要形态里名称截到这么长:摘要必须是固定尺寸的,否则它自己就是下一个漏洞 */
    static final int SUMMARY_NAME_CHARS = 200;
    /** 组视图透传的字段白名单 —— 清单是磁盘上的结构,不该整份当响应发出去 */
    static final String[] GROUP_FIELDS = {
            "id", "state", "deleted_at", "restored_at", "recovery_required_at",
            "name", "members", "blocks", "dims", "file_count", "version_state"};
    /** 体视图透传的字段白名单 */
    static final String[] BODY_FIELDS = {
            "uuid", "dim", "name", "blocks", "pos", "size", "be", "contents",
            "dependencies", "backup_count"};

    /**
     * 一页共享的方块调色板。候选组先往 pending 里放,被接受了才并入 committed ——
     * 被拒的候选不能把自己的条目留在表里,那就成了没人引用又没记账的字节。
     */
    static final class PagePalette {
        final Map<String, Integer> committed = new LinkedHashMap<>();
        final JsonArray committedArr = new JsonArray();
        final Map<String, Integer> pending = new LinkedHashMap<>();
        JsonArray pendingArr = new JsonArray();

        /** @return 该方块在本页调色板里的下标;表满时返回 null(索引直接丢弃) */
        Integer index(String id) {
            Integer at = this.committed.get(id);
            if (at == null) at = this.pending.get(id);
            if (at != null) return at;
            if (this.committed.size() + this.pending.size() >= MAX_PALETTE) return null;
            at = this.committed.size() + this.pending.size();
            this.pending.put(id, at);
            this.pendingArr.add(BlockNames.paletteEntry(id));
            return at;
        }

        long pendingBytes() {
            return this.pending.isEmpty() ? 0 : JsonSize.of(this.pendingArr);
        }

        void commit() {
            this.committed.putAll(this.pending);
            this.committedArr.addAll(this.pendingArr);
            rollback();
        }

        void rollback() {
            this.pending.clear();
            this.pendingArr = new JsonArray();
        }
    }

    /**
     * 降序清单里第一个 id 严格小于游标的位置。keyset 语义:游标那一组即使在两次请求之间
     * 被清掉,也只是二分落到同一个位置,不会翻页失败。
     */
    static int cursorOffset(List<Path> directories, String from) {
        if (from.isEmpty()) return 0;
        int low = 0;
        int high = directories.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (directories.get(mid).getFileName().toString().compareTo(from) >= 0) low = mid + 1;
            else high = mid;
        }
        return low;
    }

    record PageIndex(List<Path> latest, List<Path> old) {
        List<Path> of(boolean oldVersion) {
            return oldVersion ? this.old : this.latest;
        }
    }

    /**
     * 按字段白名单重建响应,而不是把磁盘上的清单整份转发出去。
     * <p>
     * 从前是就地摘掉 {@code block_ids}/{@code backups} 之后直接发原对象:清单里今后多出来的
     * 任何字段都会自动跟着上线,而预算不知道它存在。白名单让"发什么"变成一处显式的清单。
     *
     * @param withBlocks false 时丢掉方块构成(只留计数)。单个组自己就超过整页预算时用它
     */
    static JsonObject toView(JsonObject manifest, PagePalette palette, boolean withBlocks) {
        JsonObject view = new JsonObject();
        copyFields(manifest, view, GROUP_FIELDS);
        JsonArray bodies = new JsonArray();
        JsonArray source = manifest.getAsJsonArray("bodies");
        for (var bodyElement : source == null ? new JsonArray() : source) {
            JsonObject body = bodyElement.getAsJsonObject();
            JsonObject out = new JsonObject();
            copyFields(body, out, BODY_FIELDS);
            JsonArray indexes = new JsonArray();
            JsonArray ids = body.getAsJsonArray("block_ids");
            if (withBlocks && ids != null) {
                for (var id : ids) {
                    Integer at = palette.index(id.getAsString());
                    if (at != null) indexes.add(at);   // 表满之后的索引直接丢掉
                }
            }
            out.add("blk", indexes);
            bodies.add(out);
        }
        view.add("bodies", bodies);
        if (!withBlocks) view.addProperty("blocks_omitted", true);
        return view;
    }

    /**
     * 固定尺寸摘要:连元数据都装不下的组用它。
     * <p>
     * 一个字符串都不从清单里复制。上一版是"复制白名单字段再截断 name",于是 {@code dims}
     * 和 {@code state} 这两个同样来自磁盘、同样没有长度限制的字段照样整份进来 ——
     * 一个 34 MiB 的 dims 就能让"摘要"自己越过协议上限。
     * <p>
     * 现在:{@code id} 用目录名(已经过 {@link RecycleStore#SAFE_ID} 校验,≤96 字符),数值字段一律
     * 强制转成 long,状态只认几个已知值,名称截断。大小与清单内容无关,是真的固定尺寸。
     */
    static JsonObject summaryView(String directoryId, JsonObject manifest) {
        JsonObject view = new JsonObject();
        view.addProperty("id", directoryId);
        view.addProperty("state", knownState(manifest));
        view.addProperty("version_state", "latest".equals(text(manifest, "version_state")) ? "latest" : "old");
        view.addProperty("name", clip(text(manifest, "name")));
        view.addProperty("dims", clip(text(manifest, "dims")));
        // 数字也可能是攻击面:JSON 里的数值是任意长度的字面量,原样转发就是原样的字节数
        view.addProperty("members", number(manifest, "members"));
        view.addProperty("blocks", number(manifest, "blocks"));
        view.addProperty("file_count", number(manifest, "file_count"));
        view.addProperty("deleted_at", number(manifest, "deleted_at"));
        view.add("bodies", new JsonArray());
        view.addProperty("blocks_omitted", true);
        view.addProperty("bodies_omitted", true);
        return view;
    }

    static final Set<String> KNOWN_STATES = Set.of("deleted", "restored", "recovery_required");

    static String knownState(JsonObject manifest) {
        String state = text(manifest, "state");
        return KNOWN_STATES.contains(state) ? state : "deleted";
    }

    static String text(JsonObject from, String field) {
        return from.has(field) && from.get(field).isJsonPrimitive() ? from.get(field).getAsString() : "";
    }

    static String clip(String value) {
        return value.length() > SUMMARY_NAME_CHARS ? value.substring(0, SUMMARY_NAME_CHARS) : value;
    }

    static long number(JsonObject from, String field) {
        try {
            return from.has(field) && from.get(field).isJsonPrimitive() ? from.get(field).getAsLong() : 0L;
        } catch (RuntimeException notANumber) {
            return 0L;
        }
    }

    static void copyFields(JsonObject from, JsonObject to, String[] fields) {
        for (String field : fields) {
            if (from.has(field)) to.add(field, from.get(field));
        }
    }
}
