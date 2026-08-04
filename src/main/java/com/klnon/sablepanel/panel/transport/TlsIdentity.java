package com.klnon.sablepanel.panel.transport;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;

public final class TlsIdentity {
    private static final String ALIAS = "sablepanel";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final PrivateKey privateKey;
    private final X509Certificate certificate;
    private final String fingerprint;

    private TlsIdentity(PrivateKey privateKey, X509Certificate certificate) throws Exception {
        this.privateKey = privateKey;
        this.certificate = certificate;
        this.fingerprint = fingerprint(certificate);
    }

    public static TlsIdentity loadOrCreate(Path configDir, String serverId) throws Exception {
        Path dir = configDir.resolve("sablepanel").resolve("tls");
        Path store = dir.resolve("server.p12");
        Path passwordFile = dir.resolve("server.pass");
        Files.createDirectories(dir);
        if (!Files.isRegularFile(store) || !Files.isRegularFile(passwordFile)) {
            create(store, passwordFile, serverId);
        }
        char[] password = Files.readString(passwordFile, StandardCharsets.UTF_8).trim().toCharArray();
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(store)) {
            keyStore.load(input, password);
        }
        PrivateKey key = (PrivateKey) keyStore.getKey(ALIAS, password);
        X509Certificate certificate = (X509Certificate) keyStore.getCertificate(ALIAS);
        if (key == null || certificate == null) throw new IllegalStateException("TLS 密钥库缺少 sablepanel 身份");
        certificate.checkValidity();
        return new TlsIdentity(key, certificate);
    }

    private static void create(Path store, Path passwordFile, String serverId) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"), RANDOM);
        KeyPair pair = generator.generateKeyPair();
        Instant now = Instant.now();
        X500Name name = new X500Name("CN=" + safeName(serverId));
        X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                name, new BigInteger(128, RANDOM).abs(), Date.from(now.minus(1, ChronoUnit.DAYS)),
                Date.from(now.plus(3650, ChronoUnit.DAYS)), name, pair.getPublic());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
        builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature));
        builder.addExtension(Extension.extendedKeyUsage, false,
                new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth));
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withECDSA").build(pair.getPrivate());
        X509Certificate certificate = new JcaX509CertificateConverter().getCertificate(builder.build(signer));
        certificate.verify(pair.getPublic());

        String passwordText = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(32));
        char[] password = passwordText.toCharArray();
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, password);
        keyStore.setKeyEntry(ALIAS, pair.getPrivate(), password, new java.security.cert.Certificate[]{certificate});

        Path storeTemp = store.resolveSibling(store.getFileName() + ".tmp");
        Path passwordTemp = passwordFile.resolveSibling(passwordFile.getFileName() + ".tmp");
        try (var output = Files.newOutputStream(storeTemp)) {
            keyStore.store(output, password);
        }
        Files.writeString(passwordTemp, passwordText, StandardCharsets.UTF_8);
        move(storeTemp, store);
        move(passwordTemp, passwordFile);
    }

    public SslContext serverContext() throws javax.net.ssl.SSLException {
        return SslContextBuilder.forServer(this.privateKey, this.certificate)
                .protocols("TLSv1.3", "TLSv1.2").build();
    }

    public X509Certificate certificate() {
        return this.certificate;
    }

    public String fingerprint() {
        return this.fingerprint;
    }

    public static String fingerprint(X509Certificate certificate) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
        return HexFormat.ofDelimiter(":").withUpperCase().formatHex(digest);
    }

    private static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    private static String safeName(String value) {
        String safe = value == null ? "server" : value.replaceAll("[^A-Za-z0-9._~-]", "_");
        return "SablePanel-" + (safe.isBlank() ? "server" : safe);
    }

    private static void move(Path source, Path target) throws java.io.IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
