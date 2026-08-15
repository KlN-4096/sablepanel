package com.klnon.sablepanel.panel.transport;

public record PanelEndpoint(String host, int port) {
    /**
     * 刻意手写,别换 {@code java.net.URI}(2026-08-15 审计试过并翻案):URI 对
     * registry-based authority(下划线主机名 {@code panel_host}、裸 IPv6)的 getHost()
     * 返回 null 把合法地址拒掉,还会悄悄吞掉 user@ 前缀连去别的主机。
     */
    public static PanelEndpoint parse(String value, int defaultPort) {
        String input = value == null ? "" : value.trim();
        if (input.isEmpty()) throw new IllegalArgumentException("服务器地址为空");
        if (input.contains("://") || input.contains("/") || input.contains("?") || input.contains("#")) {
            throw new IllegalArgumentException("请输入 host:port,不要包含协议或路径");
        }
        String host = input;
        int port = defaultPort;
        if (input.startsWith("[")) {
            int end = input.indexOf(']');
            if (end < 0) throw new IllegalArgumentException("IPv6 地址格式无效");
            host = input.substring(1, end);
            if (end + 1 < input.length()) {
                if (input.charAt(end + 1) != ':') throw new IllegalArgumentException("服务器地址格式无效");
                port = Integer.parseInt(input.substring(end + 2));
            }
        } else {
            int colon = input.lastIndexOf(':');
            if (colon > 0 && input.indexOf(':') == colon) {
                host = input.substring(0, colon);
                port = Integer.parseInt(input.substring(colon + 1));
            }
        }
        if (host.isBlank() || port < 1 || port > 65535) throw new IllegalArgumentException("服务器地址无效");
        return new PanelEndpoint(host, port);
    }

    @Override
    public String toString() {
        return this.host.contains(":") ? "[" + this.host + "]:" + this.port : this.host + ":" + this.port;
    }
}
