package com.klnon.sablepanel.panel.recommendation;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.klnon.sablepanel.panel.PanelConfig;

/** 以完整物理组为单位给出删除建议；只产出解释，不执行任何写操作。 */
public final class DeletionRecommendation {
    private DeletionRecommendation() {
    }

    public record Input(long totalBlocks, int maxBlocks, int blockTypes, int blockEntities, int contents,
                        boolean anyNamed, boolean anyTracked, boolean anyUserData,
                        int orphanCount, int nonOrphan, boolean dup, boolean cloneSuspect) {
    }

    public static JsonObject evaluate(PanelConfig config, Input input) {
        String[] protectionNames = {"named", "tracked", "userdata", "contents",
                "size", "variety", "machinery"};
        boolean[] protectionSignals = {input.anyNamed(), input.anyTracked(), input.anyUserData(),
                input.contents() > 0, input.totalBlocks() >= config.protectBlocks,
                input.blockTypes() >= config.protectBlockTypes,
                input.blockEntities() >= config.protectBlockEntities};
        JsonArray protectedBy = new JsonArray();
        for (int i = 0; i < protectionNames.length; i++) {
            if (protectionSignals[i]) protectedBy.add(protectionNames[i]);
        }
        if (protectedBy.size() > 0) {
            JsonObject result = new JsonObject();
            result.add("protected_by", protectedBy);
            return result;
        }

        JsonArray reasons = new JsonArray();
        reasons.add(input.totalBlocks() == 0 ? "empty" : input.maxBlocks() < 10 ? "fragment" : "debris");
        if (input.nonOrphan() == 0 && input.orphanCount() > 0) reasons.add("orphan");
        if (input.dup()) reasons.add("dup");
        if (input.cloneSuspect()) reasons.add("clone");
        JsonObject result = new JsonObject();
        result.add("reasons", reasons);
        return result;
    }
}
