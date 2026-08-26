package com.klnon.sablepanel.panel.preview.resources;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.klnon.sablepanel.panel.storage.Digests;
import net.neoforged.fml.ModList;

import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Lazily indexes the vanilla compact archive and loaded mod JARs as one resource layer.
 * The class owns no Minecraft world state and can therefore run on the preview executor.
 */
public final class ModResourceStack implements AutoCloseable {
    public static final int RESOURCE_PROTOCOL_VERSION = 2;
    public static final long MAX_CLOSURE_BYTES = 256L * 1024 * 1024;
    public static final long MAX_JSON_BYTES = 4L * 1024 * 1024;
    public static final long MAX_OBJ_BYTES = 2L * 1024 * 1024;
    public static final long MAX_TEXTURE_BYTES = 32L * 1024 * 1024;
    public static final int MAX_SHARD_BYTES = 8 * 1024 * 1024;
    private static final int MAX_MODEL_DEPTH = 32;
    private static final int MAX_COMPOSITE_DEPTH = 8;
    private static final int MAX_COMPOSITE_NODES = 64;
    private static final int MAX_JSON_DEPTH = 64;
    private static final int MAX_PNG_EDGE = 4096;
    private static final int MAX_OBJ_FACES = 50_000;
    private static final int MAX_OBJ_MATERIALS = 128;
    private static final int MAX_OBJ_LINE = 64 * 1024;
    private static final int MAX_ASSEMBLY_SIBLINGS = 64;
    private static final long MAX_ASSEMBLY_SIBLING_BYTES = 2L * 1024 * 1024;
    /**
     * 闭包遍历逻辑的修订指纹 = 本类字节码哈希:逻辑一变自动变,无需人工记得升版。
     * 闭包内容依赖 (资源指纹, roots, 遍历逻辑) 三者;缓存校验此前只覆盖前两者,
     * 旧实例建的闭包(如缺 connection/ 子目录兄弟)会在升级后永续服务。
     */
    public static final String BUILDER_REVISION = builderRevision();

    private static String builderRevision() {
        try (InputStream input = ModResourceStack.class.getResourceAsStream("ModResourceStack.class")) {
            return Digests.sha256Hex(input.readAllBytes()).substring(0, 16);
        } catch (Exception error) {
            return "protocol-" + RESOURCE_PROTOCOL_VERSION;
        }
    }

    public record Layer(String id, Path archive) {
        public Layer {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("layer id is blank");
            archive = Objects.requireNonNull(archive, "archive").toAbsolutePath().normalize();
        }
    }

    public record Entry(String path, String sha256, long size, String shard,
                        int offset, int length, String layer) {
    }

    public record Shard(String sha256, byte[] bytes) {
        public Shard {
            bytes = Objects.requireNonNull(bytes, "bytes").clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    public record Bundle(String fingerprint, List<Entry> entries, List<Shard> shards,
                         List<String> missing, List<String> failures) {
        public Bundle {
            entries = List.copyOf(entries);
            shards = List.copyOf(shards);
            missing = List.copyOf(missing);
            failures = List.copyOf(failures);
        }

        public String manifestJson() {
            JsonObject root = new JsonObject();
            root.addProperty("version", RESOURCE_PROTOCOL_VERSION);
            root.addProperty("fingerprint", fingerprint);
            var entriesJson = new com.google.gson.JsonArray();
            for (Entry entry : entries) {
                JsonObject value = new JsonObject();
                value.addProperty("path", entry.path());
                value.addProperty("type", typeOf(entry.path()));
                value.addProperty("sha256", entry.sha256());
                value.addProperty("size", entry.size());
                value.addProperty("shard", entry.shard());
                value.addProperty("offset", entry.offset());
                value.addProperty("length", entry.length());
                value.addProperty("layer", entry.layer());
                entriesJson.add(value);
            }
            root.add("entries", entriesJson);
            addStrings(root, "missing", missing);
            addStrings(root, "failures", failures);
            return root.toString();
        }

        private static void addStrings(JsonObject object, String key, List<String> values) {
            var array = new com.google.gson.JsonArray();
            values.forEach(array::add);
            object.add(key, array);
        }
    }

    /** @param directory 层是已挂载的资源目录(模组)而不是 zip 归档(原版紧凑包/测试夹具) */
    private record ResourceRef(Layer layer, String path, long size, boolean directory) {
    }

    private record Pending(String path, int modelDepth, int compositeDepth, int compositeNodes, boolean optional) {
    }

    private record JsonNode(JsonElement value, int depth) {
    }

    private final List<Layer> layers;
    private final long maxClosureBytes;
    private final Map<String, ResourceRef> resources = new LinkedHashMap<>();
    private final Map<String, List<String>> modelDirectories = new java.util.HashMap<>();
    private final Object lock = new Object();
    private volatile boolean indexed;
    private volatile boolean closed;
    private String fingerprint = "";

    public ModResourceStack(Path vanillaArchive, List<Path> modJars) {
        this(buildLayers(vanillaArchive, modJars), MAX_CLOSURE_BYTES);
    }

    public ModResourceStack(List<Layer> layers) {
        this(layers, MAX_CLOSURE_BYTES);
    }

    ModResourceStack(List<Layer> layers, long maxClosureBytes) {
        if (layers == null || layers.isEmpty()) throw new IllegalArgumentException("resource layers are empty");
        if (maxClosureBytes <= 0) throw new IllegalArgumentException("closure byte limit must be positive");
        this.layers = List.copyOf(layers);
        this.maxClosureBytes = maxClosureBytes;
    }

    /**
     * Builds a stack in NeoForge's already resolved mod-file order.
     * <p>
     * 取 {@code findResource("assets")} 而不是 {@code getFilePath()}:jar-in-jar 打包的模组
     * (整合包里很常见,本地 269 个模组文件里有 88 个是)根本没有独立的磁盘路径 ——
     * {@code getFilePath()} 给回一条空路径,当 zip 打必然 FileNotFoundException,于是它们的
     * blockstate/模型/纹理全部缺失,预览里只能退回纯色。findResource 走的是加载器已经挂好的
     * 联合文件系统,内嵌与否一视同仁({@code BlockNames} 读模组语言文件早就是这么干的)。
     */
    public static ModResourceStack loaded(Path vanillaArchive) {
        List<Path> mods = new ArrayList<>();
        try {
            for (var info : ModList.get().getModFiles()) {
                Path assets = info.getFile().findResource("assets");
                if (assets != null) mods.add(assets);
            }
        } catch (Throwable ignored) {
            // Unit tests and very early loader phases may not have a ModList yet.
        }
        return new ModResourceStack(vanillaArchive, mods);
    }

    public String fingerprint() throws IOException {
        ensureIndexed();
        return this.fingerprint;
    }

    /** Resolve only the closure reachable from the supplied blockstate/model roots. */
    public Bundle closure(Set<String> roots) throws IOException {
        ensureIndexed();
        if (roots == null || roots.isEmpty()) return bundleOf(Set.of(), Set.of(), List.of(), List.of());
        Set<String> required = new LinkedHashSet<>();
        Set<String> optional = new LinkedHashSet<>();
        Set<String> visited = new LinkedHashSet<>();
        List<String> missing = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        Queue<Pending> requiredQueue = new ArrayDeque<>(), optionalQueue = new ArrayDeque<>();
        roots.stream().sorted().map(ModResourceStack::normalizePath)
                .forEach(path -> requiredQueue.add(new Pending(path, 0, 0, 0, false)));
        long total = 0;
        while (!requiredQueue.isEmpty() || !optionalQueue.isEmpty()) {
            Pending pending = (requiredQueue.isEmpty() ? optionalQueue : requiredQueue).remove();
            if (!visited.add(pending.path())) continue;
            Set<String> wanted = pending.optional() ? optional : required;
            wanted.add(pending.path());
            ResourceRef ref = this.resources.get(pending.path());
            if (ref == null) {
                if (!pending.path().endsWith(".png.mcmeta")) missing.add(pending.path());
                continue;
            }
            long limit = limitFor(pending.path());
            if (ref.size() >= 0 && ref.size() > limit) {
                failures.add(pending.path() + ":size");
                wanted.remove(pending.path());
                continue;
            }
            long declaredSize = Math.max(0, ref.size());
            if (pending.optional() && Math.addExact(total, declaredSize) > this.maxClosureBytes) {
                failures.add(pending.path() + ":closure_limit");
                wanted.remove(pending.path());
                continue;
            }
            try {
                if (pending.path().endsWith(".obj") || pending.path().endsWith(".mtl")) {
                    byte[] bytes = read(ref, limit);
                    validateObjResource(pending.path(), bytes);
                    collectObjReferences(pending.path(), bytes, pending,
                            pending.optional() ? optionalQueue : requiredQueue);
                } else if (pending.path().endsWith(".png")) {
                    validatePng(readPrefix(ref, 24));
                } else if (pending.path().endsWith(".json") || pending.path().endsWith(".mcmeta")) {
                    if (pending.path().endsWith(".json")) {
                        if (pending.modelDepth() >= MAX_MODEL_DEPTH) {
                            throw new IOException("model depth exceeds limit");
                        }
                        if (pending.compositeDepth() > MAX_COMPOSITE_DEPTH
                                || pending.compositeNodes() > MAX_COMPOSITE_NODES) {
                            throw new IOException("composite depth exceeds limit");
                        }
                    }
                    byte[] bytes = read(ref, limit);
                    JsonElement json = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
                    collectReferences(json, pending, pending.optional() ? optionalQueue : requiredQueue);
                    collectOptionalAssemblyReferences(pending, optionalQueue);
                }
            } catch (Exception error) {
                wanted.remove(pending.path());
                failures.add(pending.path() + ":invalid");
                continue;
            }
            long nextTotal = Math.addExact(total, declaredSize);
            if (nextTotal > this.maxClosureBytes) {
                wanted.remove(pending.path());
                failures.add(pending.path() + ":closure_limit");
                if (pending.optional()) continue;
                break;
            }
            total = nextTotal;
        }
        return bundleOf(required, optional, missing, failures);
    }

    /** 按路径读单个已索引资源。仅测试使用(分层合并语义的读取缝);shard 端点走 ResourceBundleCache。 */
    public byte[] read(String path) throws IOException {
        ensureIndexed();
        ResourceRef ref = this.resources.get(normalizePath(path));
        return ref == null ? null : read(ref, limitFor(ref.path()));
    }

    @Override
    public void close() {
        this.closed = true;
        synchronized (this.lock) {
            this.resources.clear();
            this.indexed = false;
            this.fingerprint = "";
        }
    }

    private Bundle bundleOf(Set<String> required, Set<String> optional,
                            List<String> missing, List<String> failures) throws IOException {
        record Packed(String path, String sha256, int length, int shardIndex, int offset, String layer) {}
        List<String> ordered = java.util.stream.Stream.concat(required.stream().sorted(),
                optional.stream().filter(path -> !required.contains(path)).sorted()).toList();
        List<Packed> packed = new ArrayList<>();
        List<Shard> shards = new ArrayList<>();
        ByteArrayOutputStream current = new ByteArrayOutputStream(MAX_SHARD_BYTES);
        long total = 0;
        for (String path : ordered) {
            ResourceRef ref = this.resources.get(path);
            if (ref == null) continue;
            byte[] bytes;
            try {
                bytes = read(ref, limitFor(path));
                validateResource(path, bytes);
            } catch (IOException error) {
                failures.add(path + ":invalid");
                continue;
            }
            if (bytes.length > MAX_SHARD_BYTES) {
                failures.add(path + ":shard_limit");
                continue;
            }
            long nextTotal = Math.addExact(total, bytes.length);
            if (nextTotal > this.maxClosureBytes) {
                failures.add(path + ":closure_limit");
                if (!required.contains(path)) continue;
                break;
            }
            total = nextTotal;
            if (current.size() > 0 && current.size() + bytes.length > MAX_SHARD_BYTES) {
                appendShard(shards, current);
                current = new ByteArrayOutputStream(MAX_SHARD_BYTES);
            }
            int offset = current.size();
            current.writeBytes(bytes);
            packed.add(new Packed(path, Digests.sha256Hex(bytes), bytes.length, shards.size(), offset, ref.layer().id()));
        }
        if (current.size() > 0) appendShard(shards, current);
        List<Entry> entries = new ArrayList<>(packed.size());
        for (Packed entry : packed) {
            Shard shard = shards.get(entry.shardIndex());
            entries.add(new Entry(entry.path(), entry.sha256(), entry.length(), shard.sha256(),
                    entry.offset(), entry.length(), entry.layer()));
        }
        return new Bundle(this.fingerprint, entries, shards, missing, failures);
    }

    private static void appendShard(List<Shard> shards, ByteArrayOutputStream bytes) {
        byte[] value = bytes.toByteArray();
        shards.add(new Shard(Digests.sha256Hex(value), value));
    }

    private void ensureIndexed() throws IOException {
        if (this.closed) throw new IOException("resource stack is closed");
        if (this.indexed) return;
        synchronized (this.lock) {
            if (this.indexed) return;
            MessageDigest digest = Digests.sha256();
            digest.update(("sablepanel-preview-resources-v" + RESOURCE_PROTOCOL_VERSION)
                    .getBytes(StandardCharsets.US_ASCII));
            digest.update((byte) 0);
            for (Layer layer : this.layers) {
                boolean directory = Files.isDirectory(layer.archive());
                if (!directory && !Files.isRegularFile(layer.archive())) continue;
                digest.update(layer.id().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(layer.archive().toString().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                /* 打不开的层只跳过并记一行,不能废掉整份闭包。
                   此前任意一个这样的层都会让所有预览永久停在低保真,而且服务端一个字都不记。
                   跳过后该层的资源走既定降级链,与"资源缺失"是同一条路。 */
                try {
                    if (directory) indexDirectory(layer, digest);
                    else indexArchive(layer, digest);
                } catch (IOException | RuntimeException unreadable) {
                    com.klnon.sablepanel.SablePanel.LOGGER.warn(
                            "sablepanel: preview skipped unreadable resource layer {} [{}]: {}",
                            layer.id(), layer.archive(), unreadable.toString());
                }
            }
            indexModelDirectories();
            this.fingerprint = HexFormat.of().formatHex(digest.digest());
            this.indexed = true;
        }
    }

    private void indexArchive(Layer layer, MessageDigest digest) throws IOException {
        digest.update(Long.toString(Files.size(layer.archive())).getBytes(StandardCharsets.US_ASCII));
        digest.update((byte) 0);
        digest.update(Long.toString(Files.getLastModifiedTime(layer.archive()).toMillis())
                .getBytes(StandardCharsets.US_ASCII));
        digest.update((byte) 0);
        try (ZipFile zip = new ZipFile(layer.archive().toFile())) {
            List<? extends ZipEntry> entries = zip.stream().filter(e -> !e.isDirectory())
                    .filter(e -> validPath(e.getName())).sorted(Comparator.comparing(ZipEntry::getName)).toList();
            for (ZipEntry entry : entries) {
                digestEntry(digest, layer, entry.getName(), entry.getSize());
                digest.update(Long.toString(entry.getCrc()).getBytes(StandardCharsets.US_ASCII));
                this.resources.put(entry.getName(),
                        new ResourceRef(layer, entry.getName(), entry.getSize(), false));
            }
        }
    }

    /**
     * 模组资源层:层根就是模组自己的 {@code assets} 目录,索引键补回 {@code assets/} 前缀,
     * 与原版归档层共用一套路径校验。内嵌 jar 的模组在加载器挂好的文件系统里与普通 jar 同形,
     * 所以这条路径两种都走得通。
     */
    private void indexDirectory(Layer layer, MessageDigest digest) throws IOException {
        Path root = layer.archive();
        try (var walk = Files.walk(root)) {
            List<Path> files = walk.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(Path::toString)).toList();
            for (Path file : files) {
                String name = "assets/" + root.relativize(file).toString().replace('\\', '/');
                if (!validPath(name)) continue;
                long size = Files.size(file);
                digestEntry(digest, layer, name, size);
                if ("jar".equalsIgnoreCase(file.getFileSystem().provider().getScheme())) {
                    digest.update(String.valueOf(Files.getAttribute(file, "zip:crc"))
                            .getBytes(StandardCharsets.US_ASCII));
                } else {
                    try (InputStream input = Files.newInputStream(file)) {
                        byte[] buffer = new byte[8192];
                        for (int read; (read = input.read(buffer)) >= 0; ) {
                            if (read > 0) digest.update(buffer, 0, read);
                        }
                    }
                }
                digest.update((byte) 0);
                this.resources.put(name, new ResourceRef(layer, name, size, true));
            }
        }
    }

    private static void digestEntry(MessageDigest digest, Layer layer, String name, long size) {
        digest.update(layer.id().getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(name.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(Long.toString(size).getBytes(StandardCharsets.US_ASCII));
        digest.update((byte) 0);
    }

    private static List<Layer> buildLayers(Path vanilla, List<Path> mods) {
        List<Layer> result = new ArrayList<>();
        result.add(new Layer("minecraft", vanilla));
        if (mods != null) {
            int i = 0;
            for (Path mod : mods) if (mod != null) result.add(new Layer("mod-" + i++, mod));
        }
        return result;
    }

    private void indexModelDirectories() {
        for (String path : this.resources.keySet()) {
            if (!path.endsWith(".json") || !path.contains("/models/block/")) continue;
            int slash = path.lastIndexOf('/');
            if (slash < 0) continue;
            this.modelDirectories.computeIfAbsent(path.substring(0, slash + 1), ignored -> new ArrayList<>())
                    .add(path);
        }
        this.modelDirectories.values().forEach(values -> values.sort(String::compareTo));
    }

    private void collectOptionalAssemblyReferences(Pending pending, Queue<Pending> queue) {
        String itemModel = itemModelForBlockstate(pending.path());
        if (itemModel != null && this.resources.containsKey(itemModel)) {
            queue.add(new Pending(itemModel, 0, 0, 0, true));
            return;
        }
        if (!pending.path().endsWith("/item.json") || !pending.path().contains("/models/block/")) return;
        String directory = pending.path().substring(0, pending.path().lastIndexOf('/') + 1);
        // 兄弟含一层子目录:管道的 connection/rim 部件模型放在子目录里、无任何 JSON 引用,
        // 前端定制拼装表要用它们。排序保证同层先于子目录、结果确定。
        List<String> directories = new ArrayList<>();
        for (String key : this.modelDirectories.keySet()) {
            if (key.equals(directory) || (key.startsWith(directory)
                    && key.indexOf('/', directory.length()) == key.length() - 1)) {
                directories.add(key);
            }
        }
        directories.sort(String::compareTo);
        long bytes = 0;
        int count = 0;
        for (String value : directories) {
            for (String sibling : this.modelDirectories.getOrDefault(value, List.of())) {
                if (count >= MAX_ASSEMBLY_SIBLINGS) return;
                if (sibling.equals(pending.path())) continue;
                ResourceRef ref = this.resources.get(sibling);
                long size = ref == null ? 0 : Math.max(0, ref.size());
                if (bytes + size > MAX_ASSEMBLY_SIBLING_BYTES) continue;
                queue.add(new Pending(sibling, 0, 0, 0, true));
                bytes += size;
                count++;
            }
        }
    }

    private static String itemModelForBlockstate(String path) {
        int marker = path.indexOf("/blockstates/");
        if (!path.startsWith("assets/") || marker < 0 || !path.endsWith(".json")) return null;
        String namespace = path.substring("assets/".length(), marker);
        String name = path.substring(marker + "/blockstates/".length(), path.length() - ".json".length());
        return "assets/" + namespace + "/models/item/" + name + ".json";
    }

    private void collectReferences(JsonElement element, Pending parent, Queue<Pending> queue) {
        if (element == null || element.isJsonNull()) return;
        ArrayDeque<JsonNode> nodes = new ArrayDeque<>();
        nodes.push(new JsonNode(element, 0));
        while (!nodes.isEmpty()) {
            JsonNode node = nodes.pop();
            if (node.depth() > MAX_JSON_DEPTH) throw new IllegalArgumentException("resource JSON nesting exceeds limit");
            JsonElement valueNode = node.value();
            if (valueNode.isJsonObject()) {
                for (var entry : valueNode.getAsJsonObject().entrySet()) {
                    String key = entry.getKey();
                    JsonElement value = entry.getValue();
                    if (key.equals("textures") && value.isJsonObject()) {
                        for (JsonElement texture : value.getAsJsonObject().asMap().values()) {
                            if (texture.isJsonPrimitive() && texture.getAsJsonPrimitive().isString()) {
                                addTexture(texture.getAsString(), parent, queue);
                            }
                        }
                    }
                    if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                        String text = value.getAsString();
                        addReference(key, text, parent, queue);
                    }
                    if (value.isJsonObject() || value.isJsonArray()) {
                        nodes.push(new JsonNode(value, node.depth() + 1));
                    }
                }
            } else if (valueNode.isJsonArray()) {
                for (JsonElement child : valueNode.getAsJsonArray()) {
                    if (child.isJsonObject() || child.isJsonArray()) {
                        nodes.push(new JsonNode(child, node.depth() + 1));
                    }
                }
            }
        }
    }

    private static void addReference(String key, String value, Pending parent, Queue<Pending> queue) {
        if (value == null || value.isBlank() || value.startsWith("#") || value.equals("builtin/generated")) return;
        if (value.endsWith(".obj") || value.endsWith(".mtl")) {
            String path = assetPath(value);
            if (path != null) queue.add(new Pending(path, parent.modelDepth() + 1,
                    parent.compositeDepth() + 1, parent.compositeNodes() + 1, parent.optional()));
            return;
        }
        if (key.equals("parent") || key.equals("model") || key.equals("child")) {
            String path = resourcePath(value, "models", ".json");
            if (path != null) queue.add(new Pending(path, parent.modelDepth() + 1,
                    parent.compositeDepth(), parent.compositeNodes(), parent.optional()));
        } else if (key.equals("texture") || key.equals("particle") || key.startsWith("texture")) {
            addTexture(value, parent, queue);
        }
    }

    private static void addTexture(String value, Pending parent, Queue<Pending> queue) {
        if (value == null || value.isBlank() || value.startsWith("#")) return;
        String path = resourcePath(value, "textures", ".png");
        if (path == null) return;
        queue.add(new Pending(path, parent.modelDepth(), parent.compositeDepth(),
                parent.compositeNodes(), parent.optional()));
        queue.add(new Pending(path + ".mcmeta", parent.modelDepth(), parent.compositeDepth(),
                parent.compositeNodes(), parent.optional()));
    }

    private static void collectObjReferences(String basePath, byte[] bytes, Pending parent,
                                             Queue<Pending> queue) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        String directory = basePath.substring(0, Math.max(0, basePath.lastIndexOf('/') + 1));
        for (String line : text.split("\\R")) {
            String value = line.trim();
            if (value.startsWith("mtllib ")) {
                String name = value.substring(7).trim();
                String path = relativeAsset(directory, name);
                if (path != null) queue.add(new Pending(path, parent.modelDepth() + 1,
                        parent.compositeDepth() + 1, parent.compositeNodes() + 1, parent.optional()));
            } else if (basePath.endsWith(".mtl") && value.startsWith("map_Kd ")) {
                String[] parts = value.substring(7).trim().split("\\s+");
                String name = parts.length == 0 ? "" : parts[parts.length - 1];
                String path = name.indexOf(':') >= 0
                        ? resourcePath(name, "textures", ".png") : relativeAsset(directory, name);
                if (path != null) {
                    queue.add(new Pending(path, parent.modelDepth(), parent.compositeDepth(),
                            parent.compositeNodes(), parent.optional()));
                    queue.add(new Pending(path + ".mcmeta", parent.modelDepth(), parent.compositeDepth(),
                            parent.compositeNodes(), parent.optional()));
                }
            }
        }
    }

    private static void validateResource(String path, byte[] bytes) throws IOException {
        if (path.endsWith(".png")) validatePng(bytes);
        else if (path.endsWith(".obj") || path.endsWith(".mtl")) validateObjResource(path, bytes);
        else if (path.endsWith(".json") || path.endsWith(".mcmeta")) {
            JsonElement json = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            validateJsonDepth(json);
        }
    }

    private static void validatePng(byte[] bytes) throws IOException {
        byte[] signature = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
        if (bytes.length < 24) throw new IOException("PNG header is truncated");
        for (int index = 0; index < signature.length; index++) {
            if (bytes[index] != signature[index]) throw new IOException("PNG signature is invalid");
        }
        ByteBuffer header = ByteBuffer.wrap(bytes);
        if (header.getInt(8) != 13 || header.getInt(12) != 0x49484452) {
            throw new IOException("PNG IHDR is missing");
        }
        int width = header.getInt(16), height = header.getInt(20);
        if (width <= 0 || height <= 0 || width > MAX_PNG_EDGE || height > MAX_PNG_EDGE) {
            throw new IOException("PNG dimensions exceed limit");
        }
    }

    private static void validateObjResource(String path, byte[] bytes) throws IOException {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException error) {
            throw new IOException("OBJ text is not UTF-8", error);
        }
        int faces = 0;
        Set<String> materials = new LinkedHashSet<>();
        try (BufferedReader reader = new BufferedReader(new StringReader(text))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.length() > MAX_OBJ_LINE) throw new IOException("OBJ line exceeds limit");
                String value = line.trim();
                if (value.isEmpty() || value.startsWith("#")) continue;
                int split = value.indexOf(' ');
                String directive = split < 0 ? value : value.substring(0, split);
                String rest = split < 0 ? "" : value.substring(split + 1).trim();
                if (directive.equals("f") && ++faces > MAX_OBJ_FACES) throw new IOException("OBJ faces exceed limit");
                if (directive.equals("newmtl") || directive.equals("usemtl")) {
                    if (!rest.isEmpty() && materials.add(rest) && materials.size() > MAX_OBJ_MATERIALS) {
                        throw new IOException("OBJ materials exceed limit");
                    }
                }
                if (directive.equals("v") || directive.equals("vn") || directive.equals("vt")
                        || directive.equals("vc") || (path.endsWith(".mtl") && isNumericMtl(directive))) {
                    for (String token : rest.split("\\s+")) {
                        if (token.isEmpty()) continue;
                        double number;
                        try { number = Double.parseDouble(token); }
                        catch (NumberFormatException error) { throw new IOException("OBJ number is invalid", error); }
                        if (!Double.isFinite(number)) throw new IOException("OBJ number is not finite");
                    }
                }
            }
        }
    }

    private static boolean isNumericMtl(String directive) {
        return directive.equals("Ka") || directive.equals("Kd") || directive.equals("Ks")
                || directive.equals("Ke") || directive.equals("Ns") || directive.equals("d")
                || directive.equals("Tr");
    }

    private static void validateJsonDepth(JsonElement element) throws IOException {
        ArrayDeque<JsonNode> nodes = new ArrayDeque<>();
        nodes.push(new JsonNode(element, 0));
        while (!nodes.isEmpty()) {
            JsonNode node = nodes.pop();
            if (node.depth() > MAX_JSON_DEPTH) throw new IOException("resource JSON nesting exceeds limit");
            if (node.value().isJsonObject()) {
                for (JsonElement child : node.value().getAsJsonObject().asMap().values()) {
                    if (child.isJsonObject() || child.isJsonArray()) nodes.push(new JsonNode(child, node.depth() + 1));
                }
            } else if (node.value().isJsonArray()) {
                for (JsonElement child : node.value().getAsJsonArray()) {
                    if (child.isJsonObject() || child.isJsonArray()) nodes.push(new JsonNode(child, node.depth() + 1));
                }
            }
        }
    }

    private static String relativeAsset(String directory, String value) {
        if (value == null || value.isBlank() || value.startsWith("#")) return null;
        if (value.startsWith("assets/")) return validPath(value) ? value : null;
        int colon = value.indexOf(':');
        if (colon >= 0) return assetPath(value);
        String candidate = Path.of(directory, value.replace('\\', '/')).normalize()
                .toString().replace('\\', '/');
        return validPath(candidate) ? candidate : null;
    }

    private static String assetPath(String value) {
        if (value.startsWith("assets/")) return normalizePath(value);
        int colon = value.indexOf(':');
        String namespace = colon >= 0 ? value.substring(0, colon) : "minecraft";
        String path = colon >= 0 ? value.substring(colon + 1) : value;
        if (namespace.isBlank() || path.isBlank() || path.startsWith("/") || path.contains("..")) return null;
        return normalizePath("assets/" + namespace + "/" + path);
    }

    private static String resourcePath(String value, String category, String suffix) {
        String id = value;
        int colon = id.indexOf(':');
        String namespace = colon >= 0 ? id.substring(0, colon) : "minecraft";
        String path = colon >= 0 ? id.substring(colon + 1) : id;
        if (path.startsWith(category + "/")) path = path.substring(category.length() + 1);
        if (path.startsWith("/") || path.contains("..") || namespace.isBlank() || path.isBlank()) return null;
        return normalizePath("assets/" + namespace + "/" + category + "/" + path
                + (path.endsWith(suffix) ? "" : suffix));
    }

    private static byte[] read(ResourceRef ref, long limit) throws IOException {
        if (ref.directory()) {
            try (InputStream in = Files.newInputStream(filePath(ref))) {
                byte[] bytes = in.readNBytes(Math.toIntExact(Math.min(Integer.MAX_VALUE, limit + 1)));
                if (bytes.length > limit) throw new IOException("resource exceeds limit: " + ref.path());
                return bytes;
            }
        }
        try (ZipFile zip = new ZipFile(ref.layer().archive().toFile())) {
            ZipEntry entry = zip.getEntry(ref.path());
            if (entry == null) throw new IOException("resource disappeared: " + ref.path());
            try (InputStream in = zip.getInputStream(entry)) {
                byte[] bytes = in.readNBytes(Math.toIntExact(Math.min(Integer.MAX_VALUE, limit + 1)));
                if (bytes.length > limit) throw new IOException("resource exceeds limit: " + ref.path());
                if (entry.getSize() >= 0 && entry.getSize() != bytes.length) throw new IOException("resource size changed: " + ref.path());
                return bytes;
            }
        }
    }

    private static byte[] readPrefix(ResourceRef ref, int length) throws IOException {
        if (ref.directory()) {
            try (InputStream in = Files.newInputStream(filePath(ref))) {
                return in.readNBytes(length);
            }
        }
        try (ZipFile zip = new ZipFile(ref.layer().archive().toFile())) {
            ZipEntry entry = zip.getEntry(ref.path());
            if (entry == null) throw new IOException("resource disappeared: " + ref.path());
            try (InputStream in = zip.getInputStream(entry)) {
                return in.readNBytes(length);
            }
        }
    }

    /** 索引键带 {@code assets/} 前缀而层根就是 assets 目录本身,取回来时要脱掉这一段。 */
    private static Path filePath(ResourceRef ref) {
        return ref.layer().archive().resolve(ref.path().substring("assets/".length()));
    }

    private static long limitFor(String path) {
        if (path.endsWith(".json")) return MAX_JSON_BYTES;
        if (path.endsWith(".obj") || path.endsWith(".mtl")) return MAX_OBJ_BYTES;
        if (path.endsWith(".mcmeta")) return MAX_JSON_BYTES;
        if (path.endsWith(".png")) return MAX_TEXTURE_BYTES;
        return MAX_TEXTURE_BYTES;
    }

    private static String typeOf(String path) {
        if (path.endsWith(".json")) return "json";
        if (path.endsWith(".png")) return "png";
        if (path.endsWith(".obj")) return "obj";
        if (path.endsWith(".mtl")) return "mtl";
        if (path.endsWith(".mcmeta")) return "mcmeta";
        return "binary";
    }

    private static String normalizePath(String path) {
        if (path == null) throw new IllegalArgumentException("resource path is null");
        if (path.indexOf('\\') >= 0) throw new IllegalArgumentException("invalid resource path");
        String value = path;
        if (value.startsWith("/") || value.indexOf('\0') >= 0 || value.contains("://")) throw new IllegalArgumentException("invalid resource path");
        while (value.startsWith("./")) value = value.substring(2);
        if (!validPath(value)) throw new IllegalArgumentException("invalid resource path");
        return value;
    }

    private static boolean validPath(String path) {
        if (path == null || path.isBlank() || path.startsWith("/") || path.indexOf('\\') >= 0 || path.indexOf('\0') >= 0 || path.contains("://")) return false;
        for (String part : path.split("/", -1)) if (part.isBlank() || part.equals(".") || part.equals("..")) return false;
        return path.startsWith("assets/");
    }

}
