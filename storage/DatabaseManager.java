package storage;

import model.PeerInfo;

import java.io.File;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.prefs.Preferences;

public class DatabaseManager {
    private final boolean hostMode;
    private String dbUrl;
    private final Preferences prefs = Preferences.userNodeForPackage(DatabaseManager.class);
    private final ConcurrentHashMap<String, PeerInfo> memoryRegistry = new ConcurrentHashMap<>();

    static {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException ignored) {}
    }

    public DatabaseManager(boolean hostMode) {
        this.hostMode = hostMode;
        if (hostMode) {
            String appData = System.getenv("APPDATA");
            if (appData == null || appData.isEmpty()) {
                appData = System.getProperty("user.home");
            }
            File appDir = new File(appData, "connect-o-LAN");
            if (!appDir.exists()) appDir.mkdirs();

            File dbFile = new File(appDir, "connect_o_lan.db");
            this.dbUrl = "jdbc:sqlite:" + dbFile.getAbsolutePath();
            System.out.println("[Central DB Host Active] " + dbFile.getAbsolutePath());
            initDatabase();
        } else {
            System.out.println("[Zero-Disk Client Mode] No local .db file created; syncing registry over LAN.");
        }
    }

    public boolean isHostMode() {
        return hostMode;
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(dbUrl);
    }

    private void initDatabase() {
        String sql = """
            CREATE TABLE IF NOT EXISTS workstations (
                hostname TEXT PRIMARY KEY,
                ip TEXT NOT NULL DEFAULT '127.0.0.1',
                username TEXT NOT NULL,
                tcp_port INTEGER DEFAULT 8889,
                last_seen INTEGER
            );
            """;
        try (Connection conn = connect(); Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
            try { stmt.execute("ALTER TABLE workstations ADD COLUMN ip TEXT NOT NULL DEFAULT '127.0.0.1';"); } catch (SQLException ignored) {}
            try { stmt.execute("ALTER TABLE workstations ADD COLUMN tcp_port INTEGER DEFAULT 8889;"); } catch (SQLException ignored) {}
        } catch (SQLException e) {
            System.err.println("[SQLite Init Error] " + e.getMessage());
        }
    }

    public String fetchSavedUsername(String hostname) {
        // Check Windows Registry first (works on both Host and Client without disk DB)
        String regName = prefs.get("display_username", null);
        if (regName != null && !regName.isBlank()) {
            return regName;
        }
        if (hostMode) {
            String sql = "SELECT username FROM workstations WHERE hostname = ?";
            try (Connection conn = connect(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, hostname);
                ResultSet rs = pstmt.executeQuery();
                if (rs.next()) return rs.getString("username");
            } catch (SQLException ignored) {}
        }
        return System.getProperty("user.name", "User");
    }

    public void saveLocalUsername(String username) {
        prefs.put("display_username", username);
    }

    public void updateLocalIdentity(String hostname, String ip, String username, int tcpPort) {
        saveLocalUsername(username);
        upsertPeer(new PeerInfo(ip, username, hostname, tcpPort, System.currentTimeMillis()));
    }

    public void upsertPeer(PeerInfo peer) {
        memoryRegistry.put(peer.hostname(), peer);
        if (!hostMode) return; // Clients never write a .db file to disk

        String sql = """
            INSERT INTO workstations (hostname, ip, username, tcp_port, last_seen)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(hostname) DO UPDATE SET
                ip = excluded.ip,
                username = excluded.username,
                tcp_port = excluded.tcp_port,
                last_seen = excluded.last_seen;
            """;
        try (Connection conn = connect(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, peer.hostname());
            pstmt.setString(2, peer.ip());
            pstmt.setString(3, peer.username());
            pstmt.setInt(4, peer.tcpPort());
            pstmt.setLong(5, peer.lastSeen());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[SQLite Upsert Error] " + e.getMessage());
        }
    }

    public List<PeerInfo> getAllRegisteredWorkstations() {
        if (!hostMode) {
            return new ArrayList<>(memoryRegistry.values());
        }
        List<PeerInfo> list = new ArrayList<>();
        String sql = "SELECT hostname, ip, username, tcp_port, last_seen FROM workstations ORDER BY username ASC";
        try (Connection conn = connect(); Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                PeerInfo p = new PeerInfo(
                    rs.getString("ip"),
                    rs.getString("username"),
                    rs.getString("hostname"),
                    rs.getInt("tcp_port"),
                    rs.getLong("last_seen")
                );
                memoryRegistry.put(p.hostname(), p);
                list.add(p);
            }
        } catch (SQLException e) {
            System.err.println("[SQLite Query Error] " + e.getMessage());
        }
        return list;
    }
}