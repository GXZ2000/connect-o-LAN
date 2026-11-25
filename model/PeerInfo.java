package model;

public record PeerInfo(
    String ip,
    String username,
    String hostname,
    int tcpPort,
    long lastSeen
) {}