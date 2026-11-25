package storage;

import model.PeerInfo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class LocalLogManager {
    private static final String LOG_SEP = "\u001E";
    private final Path logDir;
    private final Path peersLogFile;
    private final Path chatLogFile;

    public static class LoggedChatRecord {
        public final String targetId;
        public final String sender;
        public final String time;
        public final String text;
        public final boolean isOutgoing;
        public final boolean italic;
        public final boolean underline;
        public final int colorIdx;

        public LoggedChatRecord(String targetId, String sender, String time, String text,
                                boolean isOutgoing, boolean italic, boolean underline, int colorIdx) {
            this.targetId = targetId;
            this.sender = sender;
            this.time = time;
            this.text = text;
            this.isOutgoing = isOutgoing;
            this.italic = italic;
            this.underline = underline;
            this.colorIdx = colorIdx;
        }
    }

    public LocalLogManager() {
        this.logDir = Paths.get(System.getProperty("user.home"), "connect-o-lan-logs");
        this.peersLogFile = logDir.resolve("peers_cache.log");
        this.chatLogFile = logDir.resolve("chat_history.log");
        initLogFiles();
    }

    private void initLogFiles() {
        try {
            if (!Files.exists(logDir)) {
                Files.createDirectories(logDir);
            }
            if (!Files.exists(peersLogFile)) {
                Files.writeString(peersLogFile, "# connect-o-LAN Peer Registry Log (hostname|ip|username|tcpPort|lastSeen)\n", StandardCharsets.UTF_8);
            }
            if (!Files.exists(chatLogFile)) {
                Files.writeString(chatLogFile, "# connect-o-LAN Persistent Message Log\n", StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            System.err.println("[LocalLogManager Init Error] " + e.getMessage());
        }
    }

    public synchronized Map<String, PeerInfo> loadCachedPeers() {
        Map<String, PeerInfo> map = new LinkedHashMap<>();
        if (!Files.exists(peersLogFile)) return map;
        try {
            for (String line : Files.readAllLines(peersLogFile, StandardCharsets.UTF_8)) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] parts = line.split("\\|", 5);
                if (parts.length == 5) {
                    PeerInfo p = new PeerInfo(
                        parts[1].trim(),
                        parts[2].trim(),
                        parts[0].trim(),
                        Integer.parseInt(parts[3].trim()),
                        Long.parseLong(parts[4].trim())
                    );
                    map.put(p.ip(), p);
                }
            }
        } catch (Exception e) {
            System.err.println("[Load Cached Peers Error] " + e.getMessage());
        }
        return map;
    }

    public synchronized void appendOrUpdatePeerLog(PeerInfo peer) {
        Map<String, PeerInfo> existing = loadCachedPeers();
        PeerInfo prev = existing.get(peer.ip());
        boolean changed = (prev == null)
                || !prev.username().equals(peer.username())
                || !prev.hostname().equals(peer.hostname())
                || (peer.lastSeen() - prev.lastSeen() > 60_000L);

        existing.put(peer.ip(), peer);
        if (!changed) return;

        try {
            List<String> lines = new ArrayList<>();
            lines.add("# connect-o-LAN Peer Registry Log (hostname|ip|username|tcpPort|lastSeen)");
            for (PeerInfo p : existing.values()) {
                lines.add(p.hostname() + "|" + p.ip() + "|" + p.username() + "|" + p.tcpPort() + "|" + p.lastSeen());
            }
            Files.write(peersLogFile, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            System.err.println("[Update Peer Log Error] " + e.getMessage());
        }
    }

    public synchronized void appendChatMessage(String targetId, String sender, String time, String text,
                                               boolean isOutgoing, boolean italic, boolean underline, int colorIdx) {
        String safeText = text.replace("\r", "").replace("\n", "\\n");
        String record = String.join(LOG_SEP,
            targetId,
            sender,
            time,
            isOutgoing ? "1" : "0",
            italic ? "1" : "0",
            underline ? "1" : "0",
            String.valueOf(colorIdx),
            safeText
        ) + System.lineSeparator();

        try {
            Files.writeString(chatLogFile, record, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("[Append Chat Log Error] " + e.getMessage());
        }
    }

    public synchronized List<LoggedChatRecord> loadChatHistory() {
        List<LoggedChatRecord> records = new ArrayList<>();
        if (!Files.exists(chatLogFile)) return records;
        try {
            for (String line : Files.readAllLines(chatLogFile, StandardCharsets.UTF_8)) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] parts = line.split(LOG_SEP, 8);
                if (parts.length == 8) {
                    records.add(new LoggedChatRecord(
                        parts[0],
                        parts[1],
                        parts[2],
                        parts[7].replace("\\n", "\n"),
                        "1".equals(parts[3]),
                        "1".equals(parts[4]),
                        "1".equals(parts[5]),
                        Integer.parseInt(parts[6])
                    ));
                }
            }
        } catch (Exception e) {
            System.err.println("[Load Chat Log Error] " + e.getMessage());
        }
        return records;
    }
}