package com.klnon.sablepanel.panel.preview.resources;

import com.klnon.sablepanel.panel.storage.Digests;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Downloads/compacts the immutable Minecraft 1.21.1 vanilla client resources. */
public final class VanillaResourceCache {
    private static final long CLIENT_SIZE = 26_836_906L;
    private static final String CLIENT_SHA256 = "499f6897d1837516680f3114072d8106e11c9adcd933fe5cf051b551089b0c99";
    private static final String VERSION = "1.21.1";
    private static final String CACHE_FILE = "vanilla-1.21.1.zip";
    private static final String MANIFEST = "META-INF/sablepanel-vanilla.properties";
    private static final List<String> ROOTS = List.of(
            "assets/minecraft/blockstates/", "assets/minecraft/models/block/",
            "assets/minecraft/textures/block/", "assets/minecraft/textures/colormap/",
            "assets/minecraft/atlases/blocks.json");
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofSeconds(180);
    private static final List<URI> SOURCES = List.of(
            URI.create("https://piston-data.mojang.com/v1/objects/30c73b1c5da787909b2f73340419fdf13b9def88/client.jar"),
            URI.create("https://bmclapi2.bangbang93.com/version/1.21.1/client"));

    @FunctionalInterface
    public interface Downloader {
        void download(URI source, Path target) throws IOException;
    }

    public enum Phase { IDLE, CACHE, VALIDATING, DOWNLOADING, EXTRACTING, READY, FAILED }

    public record Progress(Phase phase, String source, long downloaded, long total, String message) {
        public Progress {
            Objects.requireNonNull(phase, "phase");
            source = source == null ? "" : source;
            message = message == null ? "" : message;
        }
    }

    private static final Consumer<Progress> NO_PROGRESS = progress -> { };

    public record Baseline(Path archive, String fingerprint) {}

    private final Path instanceRoot;
    private final Downloader downloader;
    private final long expectedSize;
    private final String expectedSha256;
    private final Consumer<Progress> progressListener;

    public VanillaResourceCache(Path instanceRoot, Consumer<Progress> progressListener) {
        this(instanceRoot, new HttpDownloader(CLIENT_SIZE, progressListener), CLIENT_SIZE, CLIENT_SHA256,
                progressListener);
    }

    VanillaResourceCache(Path instanceRoot, Downloader downloader, long expectedSize, String expectedSha256) {
        this(instanceRoot, downloader, expectedSize, expectedSha256, NO_PROGRESS);
    }

    VanillaResourceCache(Path instanceRoot, Downloader downloader, long expectedSize, String expectedSha256,
                         Consumer<Progress> progressListener) {
        this.instanceRoot = Objects.requireNonNull(instanceRoot, "instanceRoot");
        this.downloader = Objects.requireNonNull(downloader, "downloader");
        this.expectedSize = expectedSize;
        this.expectedSha256 = expectedSha256;
        this.progressListener = progressListener == null ? NO_PROGRESS : progressListener;
    }

    public Baseline prepare() throws IOException {
        try {
            return prepareInternal();
        } catch (IOException error) {
            report(Phase.FAILED, "", 0, -1, error.getMessage());
            throw error;
        }
    }

    private Baseline prepareInternal() throws IOException {
        Path cacheDir = instanceRoot.resolve("cache/sablepanel/resources/v1");
        Path cache = cacheDir.resolve(CACHE_FILE);
        report(Phase.CACHE, "本地精简缓存", 0, -1, "检查缓存");
        Baseline valid = validateCache(cache);
        if (valid != null) {
            report(Phase.READY, "本地精简缓存", 0, 0, "资源已就绪");
            return valid;
        }
        Files.createDirectories(cacheDir);
        Path client = null;
        try {
            Path admin = instanceRoot.resolve("config/sablepanel/assets/minecraft-1.21.1-client.jar");
            if (Files.isRegularFile(admin)) {
                report(Phase.VALIDATING, "管理员离线文件", 0, expectedSize, admin.toString());
                client = validateClient(admin) ? admin : null;
            }
            if (client == null) {
                for (URI source : SOURCES) {
                    Path candidate = Files.createTempFile(cacheDir, "client-", ".jar.tmp");
                    try {
                        try {
                            report(Phase.DOWNLOADING, sourceName(source), 0, expectedSize, source.toString());
                            downloader.download(source, candidate);
                            report(Phase.VALIDATING, sourceName(source), expectedSize, expectedSize, "校验客户端 JAR");
                            if (validateClient(candidate)) { client = candidate; break; }
                        } catch (IOException failure) {
                            // Try the next configured source.
                        }
                    } finally {
                        if (client != candidate) Files.deleteIfExists(candidate);
                    }
                }
            }
            if (client == null) throw new IOException("Minecraft 1.21.1 client JAR unavailable or failed SHA-256/size validation; provide " + admin);
            Path temp = Files.createTempFile(cacheDir, "vanilla-", ".zip.tmp");
            try {
                report(Phase.EXTRACTING, "Minecraft 1.21.1", 0, -1, "提取方块资源");
                Baseline result = compact(client, temp);
                com.klnon.sablepanel.panel.storage.AtomicIo.move(temp, cache);
                report(Phase.READY, "Minecraft 1.21.1", 0, 0, "资源已就绪");
                return new Baseline(cache, result.fingerprint());
            } finally { Files.deleteIfExists(temp); }
        } finally {
            if (client != null && !client.toAbsolutePath().normalize().equals(instanceRoot.resolve("config/sablepanel/assets/minecraft-1.21.1-client.jar").toAbsolutePath().normalize())) Files.deleteIfExists(client);
        }
    }

    private void report(Phase phase, String source, long downloaded, long total, String message) {
        try {
            this.progressListener.accept(new Progress(phase, source, downloaded, total, message));
        } catch (RuntimeException ignored) {
        }
    }

    private static String sourceName(URI source) {
        return source.getHost() != null && source.getHost().contains("mojang") ? "Mojang" : "BMCLAPI";
    }

    private Baseline validateCache(Path cache) {
        if (!Files.isRegularFile(cache)) return null;
        try (ZipFile zip = new ZipFile(cache.toFile())) {
            ZipEntry meta = zip.getEntry(MANIFEST);
            if (meta == null) return null;
            Properties p = new Properties();
            try (InputStream in = zip.getInputStream(meta)) { p.load(in); }
            if (!VERSION.equals(p.getProperty("version")) || !expectedSha256.equals(p.getProperty("client.sha256"))) return null;
            int count = Integer.parseInt(p.getProperty("entries"));
            String fingerprint = p.getProperty("fingerprint", "");
            if (count < 0 || fingerprint.length() != 64) return null;
            MessageDigest digest = Digests.sha256();
            var names = zip.stream().map(ZipEntry::getName).filter(VanillaResourceCache::selected).sorted().toList();
            if (zip.stream().anyMatch(e -> !MANIFEST.equals(e.getName()) && !selected(e.getName()))) return null;
            if (names.size() != count) return null;
            for (String name : names) { ZipEntry e = zip.getEntry(name); try (InputStream in = zip.getInputStream(e)) { digestEntry(digest, name, in); } }
            return HexFormat.of().formatHex(digest.digest()).equals(fingerprint) ? new Baseline(cache, fingerprint) : null;
        } catch (Exception ignored) { return null; }
    }

    private Baseline compact(Path client, Path target) throws IOException {
        MessageDigest digest = Digests.sha256(); int count = 0;
        List<ZipEntry> entries;
        try (ZipFile source = new ZipFile(client.toFile()); ZipOutputStream out = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(target)))) {
            entries = source.stream().filter(e -> !e.isDirectory() && selected(e.getName())).sorted(Comparator.comparing(ZipEntry::getName)).map(e -> (ZipEntry) e).toList();
            for (ZipEntry original : entries) {
                ZipEntry copy = new ZipEntry(original.getName()); copy.setTime(0); out.putNextEntry(copy);
                try (InputStream in = source.getInputStream(original)) { digestEntry(digest, original.getName(), in, out); }
                out.closeEntry(); count++;
            }
            Properties p = new Properties(); p.setProperty("version", VERSION); p.setProperty("client.sha256", expectedSha256);
            p.setProperty("entries", Integer.toString(count)); p.setProperty("fingerprint", HexFormat.of().formatHex(digest.digest()));
            out.putNextEntry(new ZipEntry(MANIFEST)); p.store(out, "SablePanel vanilla resource baseline"); out.closeEntry();
            return new Baseline(target, p.getProperty("fingerprint"));
        }
    }

    private boolean validateClient(Path file) {
        try { return Files.size(file) == expectedSize && HexFormat.of().formatHex(hash(file)).equalsIgnoreCase(expectedSha256); }
        catch (IOException ignored) { return false; }
    }

    private static byte[] hash(Path file) throws IOException { try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) { return digest(in); } }
    private static byte[] digest(InputStream in) throws IOException { MessageDigest d = Digests.sha256(); in.transferTo(new java.security.DigestOutputStream(java.io.OutputStream.nullOutputStream(), d)); return d.digest(); }
    private static void digestEntry(MessageDigest d, String name, InputStream in) throws IOException { digestEntry(d,name,in,null); }
    private static void digestEntry(MessageDigest d, String name, InputStream in, java.io.OutputStream out) throws IOException {
        d.update(name.getBytes(StandardCharsets.UTF_8)); d.update((byte)0); byte[] buf=new byte[8192]; int n; while((n=in.read(buf))>=0){if(n==0)continue; d.update(buf,0,n); if(out!=null)out.write(buf,0,n);}
    }
    private static boolean selected(String name) {
        if (name == null || name.isEmpty() || name.startsWith("/") || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0) return false;
        for (String part : name.split("/", -1)) {
            if (part.isEmpty() || ".".equals(part) || "..".equals(part)) return false;
        }
        for (String root : ROOTS) if (name.startsWith(root) || name.equals(root)) return true;
        return false;
    }
    private static final class HttpDownloader implements Downloader {
        private final HttpClient client;
        private final long maxBytes;
        private final Consumer<Progress> progressListener;

        private HttpDownloader(long maxBytes, Consumer<Progress> progressListener) {
            this.maxBytes = maxBytes;
            this.progressListener = progressListener == null ? NO_PROGRESS : progressListener;
            this.client = HttpClient.newBuilder()
                    .connectTimeout(CONNECT_TIMEOUT)
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
        }

        @Override public void download(URI source, Path target) throws IOException {
            try {
                URI current = source;
                HttpResponse<InputStream> response = null;
                for (int redirect = 0; redirect <= 3; redirect++) {
                    HttpRequest request = HttpRequest.newBuilder(current).timeout(DOWNLOAD_TIMEOUT).GET().build();
                    response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                    int status = response.statusCode();
                    if (status < 300 || status >= 400) break;
                    String location = response.headers().firstValue("Location").orElse("");
                    response.body().close();
                    URI next;
                    try { next = current.resolve(location); }
                    catch (IllegalArgumentException invalid) { throw new IOException("invalid download redirect", invalid); }
                    if (!allowedRedirect(next)) throw new IOException("download redirect leaves approved hosts");
                    current = next;
                    if (redirect == 3) throw new IOException("too many download redirects");
                }
                if (response == null) throw new IOException("empty download response");
                if (response.statusCode() != 200) {
                    response.body().close();
                    throw new IOException("HTTP " + response.statusCode());
                }
                long declared = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
                if (declared > this.maxBytes) {
                    response.body().close();
                    throw new IOException("download exceeds byte limit");
                }
                try (InputStream in = response.body(); var out = Files.newOutputStream(
                        target, StandardOpenOption.TRUNCATE_EXISTING)) {
                    byte[] buffer = new byte[8192];
                    long total = 0;
                    long nextProgress = 0;
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        total += read;
                        if (total > this.maxBytes) throw new IOException("download exceeds byte limit");
                        out.write(buffer, 0, read);
                        if (total >= nextProgress) {
                            try {
                                this.progressListener.accept(new Progress(Phase.DOWNLOADING, sourceName(source), total,
                                        declared >= 0 ? declared : this.maxBytes, source.toString()));
                            } catch (RuntimeException ignored) {
                            }
                            nextProgress = total + 256 * 1024;
                        }
                    }
                }
            }
            catch(InterruptedException e){Thread.currentThread().interrupt(); throw new IOException("download interrupted",e);}
        }

        private static boolean allowedRedirect(URI uri) {
            return "https".equalsIgnoreCase(uri.getScheme())
                    && ("piston-data.mojang.com".equalsIgnoreCase(uri.getHost())
                    || "bmclapi2.bangbang93.com".equalsIgnoreCase(uri.getHost()));
        }
    }
}
