package com.klnon.sablepanel.panel.storage;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * UUID 并查集(路径压缩):依赖连通组的唯一实现。
 * 从前 DiskScanner 与 BodyIndex 各写一份 find/union,CopyVersionScanner 再写一份建图+BFS,
 * 三处算的都是同一件事 —— "哪些体通过 loading_dependencies 连在一起"。
 */
public final class UnionFind {
    private final Map<UUID, UUID> parent = new HashMap<>();

    public void add(UUID uuid) {
        this.parent.putIfAbsent(uuid, uuid);
    }

    public boolean contains(UUID uuid) {
        return this.parent.containsKey(uuid);
    }

    public UUID find(UUID uuid) {
        UUID root = uuid;
        while (!this.parent.get(root).equals(root)) root = this.parent.get(root);
        while (!this.parent.get(uuid).equals(root)) {
            UUID next = this.parent.get(uuid);
            this.parent.put(uuid, root);
            uuid = next;
        }
        return root;
    }

    public void union(UUID first, UUID second) {
        UUID firstRoot = find(first);
        UUID secondRoot = find(second);
        if (!firstRoot.equals(secondRoot)) this.parent.put(firstRoot, secondRoot);
    }

    public Set<UUID> members() {
        return this.parent.keySet();
    }
}
