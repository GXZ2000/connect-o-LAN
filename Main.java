import network.NetworkManager;
import storage.DatabaseManager;
import ui.MainFrame;
import ui.ThemeManager;

import javax.swing.*;
import java.net.InetAddress;
import java.util.prefs.Preferences;

public class Main {
    public static void main(String[] args) {
        ThemeManager.applyTheme(true);

        String hostname = "Workstation";
        try {
            hostname = InetAddress.getLocalHost().getHostName();
        } catch (Exception ignored) {}

        Preferences prefs = Preferences.userRoot().node("connect_o_lan");
        boolean cliHostFlag = args.length > 0 && "--host".equalsIgnoreCase(args[0]);
        if (cliHostFlag) {
            prefs.putBoolean("is_central_host", true);
        }
        boolean isCentralHost = prefs.getBoolean("is_central_host", false);

        // Only initializes SQLite on disk if this machine is configured as the Central DB Host
        DatabaseManager db = new DatabaseManager(isCentralHost);
        NetworkManager networkManager = new NetworkManager(hostname, db, isCentralHost);

        SwingUtilities.invokeLater(() -> {
            String roleTag = isCentralHost ? " [DB Host]" : "";
            MainFrame frame = new MainFrame("connect-o-LAN - [" + networkManager.getLocalUsername() + "]" + roleTag, networkManager);
            frame.setVisible(true);
            networkManager.start();
        });
    }
}