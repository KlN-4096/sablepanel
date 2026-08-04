package com.klnon.sablepanel.panel.transport;

import com.google.gson.JsonObject;

record PanelFrame(byte type, long requestId, JsonObject meta, byte[] body) {
    static final byte REQUEST = 1;
    static final byte RESPONSE = 2;
    static final byte PEER_REGISTER = 3;
    static final byte PEER_REGISTERED = 4;
    static final byte TOKEN_UPDATE = 5;
    static final byte PING = 6;
    static final byte PONG = 7;
    static final byte ERROR = 8;
}
