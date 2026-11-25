package network;

import model.GroupInfo;
import model.PeerInfo;
import storage.DatabaseManager;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.regex.Pattern;

public class NetworkManager {
    public static final int UDP_PORT = 8888;
    public static final int TCP_PORT = 8889;
    private static final String SEP = "\u001F"; // Non-colliding field delimiter so ':' in timestamps never splits

    private final String localHostname;
    private final String localIp;
    private String localUsername;
    private File defaultDownloadDir;
    private final DatabaseManager db;
    private final boolean isCentralHost;

    private final ConcurrentHashMap<String, PeerInfo> activePeers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, GroupInfo> activeGroups = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    private Runnable onPeersChangedListener;
    private Consumer<GroupInfo> onGroupCreatedListener;
    private MessageListener onMessageReceivedListener;
    private GroupMessageListener onGroupMessageReceivedListener;
    private FileProposalListener onFileProposalListener;

    @FunctionalInterface
    public interface MessageListener {
        void onMessage(String sender, String senderIp, String time, String text);
    }

    @FunctionalInterface
    public interface GroupMessageListener {
        void onGroupMessage(String groupId, String sender, String time, String text);
    }

    @FunctionalInterface
    public interface FileProposalListener {
        File onFileProposal(String targetConversationId, String senderName, String fileName, long fileSize);
    }

    public NetworkManager(String localHostname, DatabaseManager db, boolean isCentralHost) {
        this.localHostname = localHostname;
        this.db = db;
        this.isCentralHost = isCentralHost;
        this.localIp = resolveLocalLanIp();
        this.localUsername = db.fetchSavedUsername(localHostname);
        this.defaultDownloadDir = new File(System.getProperty("user.home"), "Downloads");
        if (!defaultDownloadDir.exists()) defaultDownloadDir.mkdirs();

        db.updateLocalIdentity(localHostname, localIp, localUsername, TCP_PORT);
    }

    public void start() {
        startUdpListener();
        startTcpServer();
        startHeartbeatScheduler();

        broadcastPresence();
        if (!isCentralHost) {
            requestDatabaseSyncFromHost();
        }
    }

    private String resolveLocalLanIp() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (iface.isLoopback() || !iface.isUp()) continue;
                String name = iface.getDisplayName().toLowerCase();
                if (name.contains("virtual") || name.contains("vmware") || name.contains("vbox") || name.contains("hyper-v")) {
                    continue;
                }
                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }

    private void startUdpListener() {
        Thread udpThread = new Thread(() -> {
            try (DatagramSocket socket = new DatagramSocket(null)) {
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress(UDP_PORT));
                byte[] buf = new byte[4096];

                while (!Thread.currentThread().isInterrupted()) {
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    socket.receive(packet);
                    String senderIp = packet.getAddress().getHostAddress();
                    String msg = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                    handleUdpPacket(senderIp, msg);
                }
            } catch (Exception e) {
                System.err.println("[UDP Listener Error] " + e.getMessage());
            }
        }, "UDP-Discovery-Listener");
        udpThread.setDaemon(true);
        udpThread.start();
    }

    private void handleUdpPacket(String senderIp, String msg) {
        if (msg.startsWith("ANNOUNCE:")) {
            String[] parts = msg.split(":", 4);
            if (parts.length < 4) return;
            String host = parts[1];
            String user = parts[2];
            int port = Integer.parseInt(parts[3]);

            if (host.equalsIgnoreCase(localHostname) && senderIp.equals(localIp)) return;

            PeerInfo peer = new PeerInfo(senderIp, user, host, port, System.currentTimeMillis());
            boolean isNew = !activePeers.containsKey(senderIp);
            activePeers.put(senderIp, peer);
            db.upsertPeer(peer);

            if (isNew) {
                broadcastPresence();
            }
            if (onPeersChangedListener != null) {
                onPeersChangedListener.run();
            }
        } else if (msg.startsWith("SYNC_REQUEST:")) {
            String[] parts = msg.split(":", 4);
            if (parts.length < 4) return;
            String reqHost = parts[1];
            String reqUser = parts[2];
            int reqPort = Integer.parseInt(parts[3]);

            if (reqHost.equalsIgnoreCase(localHostname) && senderIp.equals(localIp)) return;

            PeerInfo clientPeer = new PeerInfo(senderIp, reqUser, reqHost, reqPort, System.currentTimeMillis());
            activePeers.put(senderIp, clientPeer);
            db.upsertPeer(clientPeer);
            if (onPeersChangedListener != null) onPeersChangedListener.run();

            if (isCentralHost) {
                pushDatabaseRegistryToClient(senderIp, reqPort);
            }
        } else if (msg.startsWith("GROUP_CREATE:")) {
            String[] parts = msg.split(":", 5);
            if (parts.length < 5) return;

            String groupId = parts[1];
            String groupName = parts[2];
            String creatorIp = parts[3];
            List<String> memberIps = new ArrayList<>(Arrays.asList(parts[4].split(",")));

            if (!memberIps.contains(localIp) && !creatorIp.equals(localIp)) {
                return;
            }

            GroupInfo group = new GroupInfo(groupId, groupName, creatorIp, memberIps);
            activeGroups.put(groupId, group);
            if (onGroupCreatedListener != null) {
                onGroupCreatedListener.accept(group);
            }
        }
    }

    public void broadcastPresence() {
        executor.submit(() -> {
            String payload = "ANNOUNCE:" + localHostname + ":" + localUsername + ":" + TCP_PORT;
            sendUdpBroadcast(payload);
        });
    }

    public void requestDatabaseSyncFromHost() {
        executor.submit(() -> {
            String payload = "SYNC_REQUEST:" + localHostname + ":" + localUsername + ":" + TCP_PORT;
            sendUdpBroadcast(payload);
        });
    }

    private void pushDatabaseRegistryToClient(String clientIp, int clientTcpPort) {
        executor.submit(() -> {
            List<PeerInfo> records = db.getAllRegisteredWorkstations();
            StringBuilder sb = new StringBuilder("SYNC_RESPONSE:");
            for (PeerInfo p : records) {
                sb.append(p.hostname()).append("|")
                  .append(p.ip()).append("|")
                  .append(p.username()).append("|")
                  .append(p.tcpPort()).append("|")
                  .append(p.lastSeen()).append(";");
            }
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(clientIp, clientTcpPort), 3000);
                DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                out.writeUTF(sb.toString());
                out.flush();
            } catch (Exception e) {
                System.err.println("[DB Sync Push Error] " + e.getMessage());
            }
        });
    }

    public void createAndBroadcastGroup(String groupName, List<String> selectedMemberIps) {
        String groupId = UUID.randomUUID().toString().substring(0, 8);
        List<String> allMembers = new ArrayList<>(selectedMemberIps);
        if (!allMembers.contains(localIp)) {
            allMembers.add(localIp);
        }

        GroupInfo group = new GroupInfo(groupId, groupName, localIp, allMembers);
        activeGroups.put(groupId, group);

        String packet = "GROUP_CREATE:" + groupId + ":" + groupName + ":" + localIp + ":" + String.join(",", allMembers);
        executor.submit(() -> sendUdpBroadcast(packet));
    }

    private void sendUdpBroadcast(String payload) {
        byte[] data = payload.getBytes(StandardCharsets.UTF_8);
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (iface.isLoopback() || !iface.isUp()) continue;
                String name = iface.getDisplayName().toLowerCase();
                if (name.contains("virtual") || name.contains("vmware") || name.contains("vbox") || name.contains("hyper-v")) {
                    continue;
                }
                for (InterfaceAddress ifaceAddr : iface.getInterfaceAddresses()) {
                    InetAddress broadcast = ifaceAddr.getBroadcast();
                    if (broadcast != null) {
                        DatagramPacket packet = new DatagramPacket(data, data.length, broadcast, UDP_PORT);
                        socket.send(packet);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[UDP Broadcast Error] " + e.getMessage());
        }
    }

    private void startHeartbeatScheduler() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Peer-Heartbeat");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(() -> {
            broadcastPresence();
            long cutoff = System.currentTimeMillis() - 25_000;
            boolean changed = activePeers.entrySet().removeIf(entry -> entry.getValue().lastSeen() < cutoff);
            if (changed && onPeersChangedListener != null) {
                onPeersChangedListener.run();
            }
        }, 10, 10, TimeUnit.SECONDS);
    }

    private void startTcpServer() {
        Thread tcpThread = new Thread(() -> {
            try (ServerSocket serverSocket = new ServerSocket(TCP_PORT)) {
                while (!Thread.currentThread().isInterrupted()) {
                    Socket client = serverSocket.accept();
                    executor.submit(() -> handleTcpClient(client));
                }
            } catch (Exception e) {
                System.err.println("[TCP Server Error] " + e.getMessage());
            }
        }, "TCP-Payload-Server");
        tcpThread.setDaemon(true);
        tcpThread.start();
    }

    private void handleTcpClient(Socket socket) {
        try (
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()))
        ) {
            String header = in.readUTF();
            String senderIp = socket.getInetAddress().getHostAddress();

            if (header.startsWith("SYNC_RESPONSE:")) {
                String body = header.substring("SYNC_RESPONSE:".length());
                if (!body.isEmpty()) {
                    for (String row : body.split(";")) {
                        if (row.isBlank()) continue;
                        String[] cols = row.split("\\|");
                        if (cols.length == 5) {
                            PeerInfo p = new PeerInfo(
                                cols[1],
                                cols[2],
                                cols[0],
                                Integer.parseInt(cols[3]),
                                Long.parseLong(cols[4])
                            );
                            db.upsertPeer(p);
                        }
                    }
                    if (onPeersChangedListener != null) onPeersChangedListener.run();
                }
            } else if (header.startsWith("MSG" + SEP)) {
                String[] parts = header.split(Pattern.quote(SEP), 4);
                if (parts.length == 4 && onMessageReceivedListener != null) {
                    onMessageReceivedListener.onMessage(parts[1], senderIp, parts[2], parts[3]);
                }
            } else if (header.startsWith("GROUP_MSG" + SEP)) {
                String[] parts = header.split(Pattern.quote(SEP), 5);
                if (parts.length == 5) {
                    String groupId = parts[1];
                    GroupInfo group = activeGroups.get(groupId);
                    if (group == null || (!group.memberIps().contains(localIp) && !group.creatorIp().equals(localIp))) {
                        return;
                    }
                    if (onGroupMessageReceivedListener != null) {
                        onGroupMessageReceivedListener.onGroupMessage(groupId, parts[2], parts[3], parts[4]);
                    }
                }
            } else if (header.startsWith("FILE_PROPOSAL" + SEP)) {
                String[] parts = header.split(Pattern.quote(SEP), 5);
                if (parts.length == 5 && onFileProposalListener != null) {
                    String convId = "DIRECT".equals(parts[1]) ? senderIp : parts[1];
                    String sender = parts[2];
                    String fileName = parts[3];
                    long fileSize = Long.parseLong(parts[4]);

                    File destFile = onFileProposalListener.onFileProposal(convId, sender, fileName, fileSize);
                    if (destFile != null) {
                        out.writeUTF("ACCEPT");
                        out.flush();
                        try (FileOutputStream fos = new FileOutputStream(destFile)) {
                            byte[] buffer = new byte[8192];
                            long remaining = fileSize;
                            int read;
                            while (remaining > 0 && (read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining))) != -1) {
                                fos.write(buffer, 0, read);
                                remaining -= read;
                            }
                        }
                    } else {
                        out.writeUTF("DECLINE");
                        out.flush();
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[TCP Client Error] " + e.getMessage());
        } finally {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    public void sendMessageAsync(String targetIp, String wireText, Consumer<String> onError) {
        executor.submit(() -> {
            String time = LocalTime.now().format(DateTimeFormatter.ofPattern("hh:mm a"));
            String payload = "MSG" + SEP + localUsername + SEP + time + SEP + wireText;
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(targetIp, TCP_PORT), 3000);
                DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                out.writeUTF(payload);
                out.flush();
            } catch (Exception e) {
                if (onError != null) onError.accept(e.getMessage());
            }
        });
    }

    public void sendGroupMessageAsync(String groupId, String wireText, Consumer<String> onError) {
        GroupInfo group = activeGroups.get(groupId);
        if (group == null) return;

        String time = LocalTime.now().format(DateTimeFormatter.ofPattern("hh:mm a"));
        String payload = "GROUP_MSG" + SEP + groupId + SEP + localUsername + SEP + time + SEP + wireText;

        for (String memberIp : group.memberIps()) {
            if (memberIp.equals(localIp)) continue;
            executor.submit(() -> {
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress(memberIp, TCP_PORT), 3000);
                    DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                    out.writeUTF(payload);
                    out.flush();
                } catch (Exception ignored) {}
            });
        }
    }

    public void sendFileAsync(String targetIp, String targetConvId, File file, Consumer<String> onSuccess, Consumer<String> onError) {
        executor.submit(() -> {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(targetIp, TCP_PORT), 5000);
                DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
                DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));

                out.writeUTF("FILE_PROPOSAL" + SEP + targetConvId + SEP + localUsername + SEP + file.getName() + SEP + file.length());
                out.flush();

                String response = in.readUTF();
                if (!"ACCEPT".equals(response)) {
                    if (onError != null) onError.accept("Recipient declined the file transfer.");
                    return;
                }

                try (FileInputStream fis = new FileInputStream(file)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = fis.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                    out.flush();
                }
                if (onSuccess != null) onSuccess.accept("Successfully sent " + file.getName());
            } catch (Exception e) {
                if (onError != null) onError.accept(e.getMessage());
            }
        });
    }

    public void sendGroupFileAsync(String groupId, File file, Consumer<String> onSuccess, Consumer<String> onError) {
        GroupInfo group = activeGroups.get(groupId);
        if (group == null) return;

        int recipientCount = 0;
        for (String memberIp : group.memberIps()) {
            if (memberIp.equals(localIp)) continue;
            recipientCount++;
            sendFileAsync(memberIp, "GROUP:" + groupId, file, null, null);
        }
        if (recipientCount > 0 && onSuccess != null) {
            onSuccess.accept("Dispatched " + file.getName() + " to " + recipientCount + " group member(s).");
        } else if (onError != null) {
            onError.accept("No other members in this group to receive the file.");
        }
    }

    public String getLocalHostname() { return localHostname; }
    public String getLocalIp() { return localIp; }
    public String getLocalUsername() { return localUsername; }
    public boolean isCentralHost() { return isCentralHost; }
    public void setLocalUsername(String username) {
        this.localUsername = username;
        db.updateLocalIdentity(localHostname, localIp, username, TCP_PORT);
        broadcastPresence();
    }
    public File getDefaultDownloadDir() { return defaultDownloadDir; }
    public void setDefaultDownloadDir(File dir) { this.defaultDownloadDir = dir; }
    public DatabaseManager getDb() { return db; }
    public Map<String, PeerInfo> getActivePeers() { return activePeers; }
    public Map<String, GroupInfo> getActiveGroups() { return activeGroups; }

    public void setOnPeersChangedListener(Runnable listener) { this.onPeersChangedListener = listener; }
    public void setOnGroupCreatedListener(Consumer<GroupInfo> listener) { this.onGroupCreatedListener = listener; }
    public void setOnMessageReceivedListener(MessageListener listener) { this.onMessageReceivedListener = listener; }
    public void setOnGroupMessageReceivedListener(GroupMessageListener listener) { this.onGroupMessageReceivedListener = listener; }
    public void setOnFileProposalListener(FileProposalListener listener) { this.onFileProposalListener = listener; }
}