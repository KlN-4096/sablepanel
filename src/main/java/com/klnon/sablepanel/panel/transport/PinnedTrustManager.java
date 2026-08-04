package com.klnon.sablepanel.panel.transport;

import java.net.Socket;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedTrustManager;

final class PinnedTrustManager extends X509ExtendedTrustManager {
    private final String expected;
    private volatile String candidate;

    PinnedTrustManager(String expected) {
        this.expected = expected == null ? "" : expected;
    }

    String candidate() {
        return this.candidate;
    }

    private void verify(X509Certificate[] chain) throws CertificateException {
        if (chain == null || chain.length == 0) throw new CertificateException("server certificate missing");
        try {
            X509Certificate certificate = chain[0];
            certificate.checkValidity();
            certificate.verify(certificate.getPublicKey());
            this.candidate = TlsIdentity.fingerprint(certificate);
            if (!this.candidate.equalsIgnoreCase(this.expected)) {
                throw new CertificateException("SABLEPANEL_PIN:" + this.candidate);
            }
        } catch (CertificateException error) {
            throw error;
        } catch (Exception error) {
            throw new CertificateException(error);
        }
    }

    @Override public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException { verify(chain); }
    @Override public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException { verify(chain); }
    @Override public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException { verify(chain); }
    @Override public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException { throw new CertificateException("client certificates unsupported"); }
    @Override public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException { checkClientTrusted(chain, authType); }
    @Override public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException { checkClientTrusted(chain, authType); }
    @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
}
