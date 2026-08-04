package com.klnon.sablepanel.panel.transport;

public final class CertificatePinException extends Exception {
    private final String fingerprint;
    private final boolean changed;

    CertificatePinException(String fingerprint, boolean changed, Throwable cause) {
        super(changed ? "certificate fingerprint changed" : "certificate confirmation required", cause);
        this.fingerprint = fingerprint;
        this.changed = changed;
    }

    public String fingerprint() {
        return this.fingerprint;
    }

    public boolean changed() {
        return this.changed;
    }
}
