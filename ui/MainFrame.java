package ui;

import model.GroupInfo;
import model.PeerInfo;
import network.NetworkManager;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.border.MatteBorder;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.Path2D;
import java.io.File;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.prefs.Preferences;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainFrame extends JFrame {
    private final NetworkManager networkManager;
    private Image appLogo;

    private String searchFilter = "";
    private String activeTargetId = null;
    private boolean activeIsGroup = false;

    // Translucent Tinted Button Palettes (Positive = Emerald, Negative = Crimson)
    private static final Color POS_BG_FILL   = new Color(0x0F, 0x3D, 0x34);
    private static final Color POS_BORDER    = new Color(0x10, 0xB9, 0x81);
    private static final Color POS_TEXT      = new Color(0x10, 0xB9, 0x81);

    private static final Color NEG_BG_FILL   = new Color(0x3F, 0x1A, 0x22);
    private static final Color NEG_BORDER    = new Color(0xEF, 0x44, 0x44);
    private static final Color NEG_TEXT      = new Color(0xEF, 0x44, 0x44);

    // Core Panels for Live Theme Refresh
    private JPanel leftNavPanel;
    private JPanel middleListPanel;
    private JPanel peerCardsContainer;
    private JPanel rightChatPanel;
    private JPanel chatHeaderPanel;
    private JPanel chatMessagesContainer;
    private JScrollPane chatScrollPane;
    private JLabel headerNameLabel;
    private JLabel headerSubLabel;
    private JButton btnGroupMembers;
    private JLabel myNameLabel;
    private JLabel myIpLabel;
    private JTextField searchField;

    // Rich Text Input & Formatting Toolbar State
    private JTextPane messageInputPane;
    private JToggleButton btnItalic;
    private JToggleButton btnUnderline;
    private int selectedColorIndex = 0;
    private JButton btnColorPicker;

    private static class ChatEntry {
        final String sender;
        final String time;
        final String text;
        final boolean isOutgoing;
        final boolean italic;
        final boolean underline;
        final int colorIdx;
        final boolean isFileCard;
        final long fileSize;
        final CompletableFuture<File> fileDecisionFuture;
        String fileStatusText;

        ChatEntry(String sender, String time, String text, boolean isOutgoing, boolean italic, boolean underline, int colorIdx) {
            this(sender, time, text, isOutgoing, italic, underline, colorIdx, false, 0, null);
        }

        ChatEntry(String sender, String time, String fileName, boolean isOutgoing, boolean italic, boolean underline, int colorIdx,
                  boolean isFileCard, long fileSize, CompletableFuture<File> fileDecisionFuture) {
            this.sender = sender;
            this.time = time;
            this.text = fileName;
            this.isOutgoing = isOutgoing;
            this.italic = italic;
            this.underline = underline;
            this.colorIdx = colorIdx;
            this.isFileCard = isFileCard;
            this.fileSize = fileSize;
            this.fileDecisionFuture = fileDecisionFuture;
            this.fileStatusText = "Waiting for accept";
        }
    }

    private final ConcurrentHashMap<String, List<ChatEntry>> conversations = new ConcurrentHashMap<>();

    public MainFrame(String title, NetworkManager networkManager) {
        super(title);
        this.networkManager = networkManager;

        loadLogo();
        if (appLogo != null) setIconImage(appLogo);

        setSize(1080, 700);
        setMinimumSize(new Dimension(900, 560));
        setLocationRelativeTo(null);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        buildThreePaneLayout();
        applyComponentColors();
        bindNetworkListeners();
        refreshPeerList();
    }

    private void buildThreePaneLayout() {
        // 1. LEFT NAVIGATION RAIL
        leftNavPanel = new JPanel(new BorderLayout());
        leftNavPanel.setPreferredSize(new Dimension(240, 0));

        JPanel profileBox = new JPanel(new BorderLayout(12, 0));
        profileBox.setOpaque(false);
        profileBox.setBorder(new EmptyBorder(18, 18, 18, 18));

        String uname = networkManager.getLocalUsername();
        String initials = uname.length() >= 2 ? uname.substring(0, 2).toUpperCase() : "ME";
        JLabel avatarBadge = new JLabel(initials, SwingConstants.CENTER) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(ThemeManager.ACCENT_BLUE);
                g2.fillOval(0, 0, getWidth(), getHeight());
                super.paintComponent(g2);
                g2.dispose();
            }
        };
        avatarBadge.setPreferredSize(new Dimension(42, 42));
        avatarBadge.setForeground(Color.WHITE);
        avatarBadge.setFont(new Font("Segoe UI", Font.BOLD, 14));

        JPanel nameStack = new JPanel(new GridLayout(2, 1));
        nameStack.setOpaque(false);
        myNameLabel = new JLabel(uname);
        myNameLabel.setFont(new Font("Segoe UI", Font.BOLD, 14));
        String modeSuffix = networkManager.isCentralHost() ? " • DB Host" : " • Client";
        myIpLabel = new JLabel(networkManager.getLocalIp() + modeSuffix);
        myIpLabel.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        nameStack.add(myNameLabel);
        nameStack.add(myIpLabel);

        profileBox.add(avatarBadge, BorderLayout.WEST);
        profileBox.add(nameStack, BorderLayout.CENTER);
        leftNavPanel.add(profileBox, BorderLayout.NORTH);

        JPanel navMenu = new JPanel();
        navMenu.setLayout(new BoxLayout(navMenu, BoxLayout.Y_AXIS));
        navMenu.setOpaque(false);
        navMenu.setBorder(new EmptyBorder(14, 12, 14, 12));

        JButton navChats = createNavButton("Active Chats", createVectorIcon("CHAT", 15, ThemeManager.ACCENT_BLUE));
        JButton navDiscovery = createNavButton("Rescan & Sync LAN", createVectorIcon("SYNC", 15, ThemeManager.ACCENT_BLUE));
        JButton navNewGroup = createNavButton("Create Private Group", createVectorIcon("GROUP", 15, ThemeManager.ACCENT_BLUE));

        navChats.addActionListener(e -> refreshPeerList());
        navDiscovery.addActionListener(e -> {
            networkManager.broadcastPresence();
            if (!networkManager.isCentralHost()) {
                networkManager.requestDatabaseSyncFromHost();
            }
            refreshPeerList();
        });
        navNewGroup.addActionListener(e -> openCreateGroupDialog());

        navMenu.add(navChats);
        navMenu.add(Box.createVerticalStrut(8));
        navMenu.add(navDiscovery);
        navMenu.add(Box.createVerticalStrut(8));
        navMenu.add(navNewGroup);
        leftNavPanel.add(navMenu, BorderLayout.CENTER);

        JPanel bottomNav = new JPanel(new GridLayout(2, 1, 0, 8));
        bottomNav.setOpaque(false);
        bottomNav.setBorder(new EmptyBorder(12, 12, 16, 12));

        JButton btnThemeSwitch = new JButton(ThemeManager.isDarkMode ? "Switch to Light Mode" : "Switch to Dark Mode");
        btnThemeSwitch.setFont(new Font("Segoe UI", Font.BOLD, 12));
        btnThemeSwitch.setFocusPainted(false);
        btnThemeSwitch.setCursor(new Cursor(Cursor.HAND_CURSOR));
        btnThemeSwitch.addActionListener(e -> {
            ThemeManager.applyTheme(!ThemeManager.isDarkMode);
            btnThemeSwitch.setText(ThemeManager.isDarkMode ? "Switch to Light Mode" : "Switch to Dark Mode");
            SwingUtilities.updateComponentTreeUI(this);
            applyComponentColors();
            refreshPeerList();
            renderActiveConversation();
        });

        JButton btnSettings = new JButton("Settings & Host Role");
        btnSettings.setFont(new Font("Segoe UI", Font.BOLD, 12));
        btnSettings.setFocusPainted(false);
        btnSettings.setCursor(new Cursor(Cursor.HAND_CURSOR));
        btnSettings.addActionListener(e -> openSettingsDialog());

        bottomNav.add(btnThemeSwitch);
        bottomNav.add(btnSettings);
        leftNavPanel.add(bottomNav, BorderLayout.SOUTH);

        add(leftNavPanel, BorderLayout.WEST);

        // 2. MIDDLE SIDEBAR: SEARCH & PEERS / PRIVATE GROUPS LIST
        middleListPanel = new JPanel(new BorderLayout());
        middleListPanel.setPreferredSize(new Dimension(295, 0));

        JPanel searchWrap = new JPanel(new BorderLayout());
        searchWrap.setOpaque(false);
        searchWrap.setBorder(new EmptyBorder(14, 14, 14, 14));

        searchField = new JTextField();
        searchField.putClientProperty("JTextField.placeholderText", "Search LAN peers...");
        searchField.setPreferredSize(new Dimension(0, 36));
        searchField.addKeyListener(new KeyAdapter() {
            @Override
            public void keyReleased(KeyEvent e) {
                searchFilter = searchField.getText().trim().toLowerCase();
                refreshPeerList();
            }
        });
        searchWrap.add(searchField, BorderLayout.CENTER);
        middleListPanel.add(searchWrap, BorderLayout.NORTH);

        peerCardsContainer = new JPanel();
        peerCardsContainer.setLayout(new BoxLayout(peerCardsContainer, BoxLayout.Y_AXIS));
        JScrollPane peerScroll = new JScrollPane(peerCardsContainer);
        peerScroll.setBorder(BorderFactory.createEmptyBorder());
        peerScroll.getVerticalScrollBar().setUnitIncrement(14);
        middleListPanel.add(peerScroll, BorderLayout.CENTER);

        // 3. RIGHT ACTIVE CHAT VIEWPORT
        rightChatPanel = new JPanel(new BorderLayout());

        chatHeaderPanel = new JPanel(new BorderLayout());
        chatHeaderPanel.setPreferredSize(new Dimension(0, 66));
        chatHeaderPanel.setBorder(new EmptyBorder(10, 20, 10, 20));
        chatHeaderPanel.setOpaque(false);

        JPanel titleStack = new JPanel(new GridLayout(2, 1));
        titleStack.setOpaque(false);
        headerNameLabel = new JLabel("Select a Peer or Group");
        headerNameLabel.setFont(new Font("Segoe UI", Font.BOLD, 16));
        headerSubLabel = new JLabel("Ready for zero-cloud LAN messaging");
        headerSubLabel.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        titleStack.add(headerNameLabel);
        titleStack.add(headerSubLabel);

        // Header Right Actions: Group Members + Send File
        JPanel headerActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 6));
        headerActions.setOpaque(false);

        btnGroupMembers = new JButton("Group Members", createVectorIcon("GROUP", 14, ThemeManager.ACCENT_BLUE));
        btnGroupMembers.setIconTextGap(7);
        btnGroupMembers.setFont(new Font("Segoe UI", Font.BOLD, 12));
        btnGroupMembers.setFocusPainted(false);
        btnGroupMembers.setCursor(new Cursor(Cursor.HAND_CURSOR));
        btnGroupMembers.setVisible(false);
        btnGroupMembers.addActionListener(e -> openGroupMembersDialog());

        JButton btnHeaderSendFile = new JButton("Send File");
        btnHeaderSendFile.setFont(new Font("Segoe UI", Font.BOLD, 12));
        btnHeaderSendFile.setFocusPainted(false);
        btnHeaderSendFile.setCursor(new Cursor(Cursor.HAND_CURSOR));
        btnHeaderSendFile.addActionListener(e -> onSendFileClicked());

        headerActions.add(btnGroupMembers);
        headerActions.add(btnHeaderSendFile);

        chatHeaderPanel.add(titleStack, BorderLayout.CENTER);
        chatHeaderPanel.add(headerActions, BorderLayout.EAST);
        rightChatPanel.add(chatHeaderPanel, BorderLayout.NORTH);

        chatMessagesContainer = new JPanel();
        chatMessagesContainer.setLayout(new BoxLayout(chatMessagesContainer, BoxLayout.Y_AXIS));
        chatMessagesContainer.setBorder(new EmptyBorder(16, 20, 16, 20));

        chatScrollPane = new JScrollPane(chatMessagesContainer);
        chatScrollPane.setBorder(BorderFactory.createEmptyBorder());
        chatScrollPane.getVerticalScrollBar().setUnitIncrement(16);
        rightChatPanel.add(chatScrollPane, BorderLayout.CENTER);

        // Bottom Composer: Rich Text Toolbar + Message Input Box
        JPanel bottomComposer = new JPanel(new BorderLayout(0, 6));
        bottomComposer.setBorder(new EmptyBorder(10, 18, 14, 18));
        bottomComposer.setOpaque(false);

        JPanel formatBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        formatBar.setOpaque(false);

        btnItalic = new JToggleButton("I");
        btnItalic.setFont(new Font("Serif", Font.ITALIC | Font.BOLD, 13));
        btnItalic.setToolTipText("Italics");
        btnItalic.setFocusPainted(false);
        btnItalic.addActionListener(e -> updateInputLiveFormatting());

        btnUnderline = new JToggleButton("U");
        btnUnderline.setFont(new Font("Segoe UI", Font.BOLD, 12));
        btnUnderline.setToolTipText("Underline");
        btnUnderline.setFocusPainted(false);
        btnUnderline.addActionListener(e -> updateInputLiveFormatting());

        btnColorPicker = new JButton("Color: Default");
        btnColorPicker.setFont(new Font("Segoe UI", Font.BOLD, 11));
        btnColorPicker.setToolTipText("Select Message Text Color");
        btnColorPicker.setFocusPainted(false);
        btnColorPicker.addActionListener(e -> showColorPalettePopup());

        formatBar.add(btnItalic);
        formatBar.add(btnUnderline);
        formatBar.add(btnColorPicker);

        JPanel inputRow = new JPanel(new BorderLayout(10, 0));
        inputRow.setOpaque(false);

        JButton btnAttach = new JButton("+ File");
        btnAttach.setToolTipText("Attach & Send File");
        btnAttach.setFocusPainted(false);
        btnAttach.addActionListener(e -> onSendFileClicked());

        messageInputPane = new JTextPane();
        messageInputPane.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        messageInputPane.setBorder(new EmptyBorder(8, 10, 8, 10));
        JScrollPane inputScroll = new JScrollPane(messageInputPane);
        inputScroll.setPreferredSize(new Dimension(0, 44));

        messageInputPane.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER && !e.isShiftDown()) {
                    e.consume();
                    dispatchCurrentMessage();
                }
            }
        });

        JButton btnSend = new JButton("Send", createVectorIcon("SEND", 14, Color.WHITE));
        btnSend.setIconTextGap(8);
        btnSend.setFont(new Font("Segoe UI", Font.BOLD, 13));
        btnSend.setForeground(Color.WHITE);
        btnSend.setBackground(ThemeManager.ACCENT_BLUE);
        btnSend.setFocusPainted(false);
        btnSend.setCursor(new Cursor(Cursor.HAND_CURSOR));
        btnSend.setPreferredSize(new Dimension(102, 44));
        btnSend.addActionListener(e -> dispatchCurrentMessage());

        inputRow.add(btnAttach, BorderLayout.WEST);
        inputRow.add(inputScroll, BorderLayout.CENTER);
        inputRow.add(btnSend, BorderLayout.EAST);

        bottomComposer.add(formatBar, BorderLayout.NORTH);
        bottomComposer.add(inputRow, BorderLayout.CENTER);
        rightChatPanel.add(bottomComposer, BorderLayout.SOUTH);

        JPanel centerSplit = new JPanel(new BorderLayout());
        centerSplit.add(middleListPanel, BorderLayout.WEST);
        centerSplit.add(rightChatPanel, BorderLayout.CENTER);
        add(centerSplit, BorderLayout.CENTER);
    }

    private JButton createNavButton(String label, Icon icon) {
        JButton btn = new JButton(label, icon);
        btn.setHorizontalAlignment(SwingConstants.LEFT);
        btn.setIconTextGap(10);
        btn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        btn.setFont(new Font("Segoe UI", Font.BOLD, 13));
        btn.setFocusPainted(false);
        btn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        return btn;
    }

    // Crisp Vector Icons rendered via Graphics2D (no font/emoji box artifacts)
    private Icon createVectorIcon(String type, int size, Color color) {
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(color);
                g2.setStroke(new BasicStroke(1.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.translate(x, y);

                switch (type) {
                    case "CHAT" -> {
                        g2.drawRoundRect(1, 2, size - 2, size - 5, 5, 5);
                        Path2D tail = new Path2D.Double();
                        tail.moveTo(4, size - 3);
                        tail.lineTo(2, size);
                        tail.lineTo(7, size - 3);
                        g2.draw(tail);
                    }
                    case "SYNC" -> {
                        g2.drawArc(2, 2, size - 4, size - 4, 30, 125);
                        g2.drawArc(2, 2, size - 4, size - 4, 210, 125);
                        g2.drawLine(size - 3, 2, size - 3, 6);
                        g2.drawLine(size - 7, 5, size - 3, 6);
                        g2.drawLine(3, size - 2, 3, size - 6);
                        g2.drawLine(3, size - 6, 7, size - 5);
                    }
                    case "GROUP" -> {
                        g2.drawOval(3, 2, 5, 5);
                        g2.drawArc(1, 8, 9, 6, 0, 180);
                        g2.drawOval(9, 3, 4, 4);
                        g2.drawArc(8, 8, 7, 5, 0, 160);
                    }
                    case "SEND" -> {
                        Path2D plane = new Path2D.Double();
                        plane.moveTo(1, size / 2.0);
                        plane.lineTo(size - 1, 2);
                        plane.lineTo(size - 4, size - 2);
                        plane.lineTo(size / 2.0, size / 2.0 + 2);
                        plane.closePath();
                        g2.draw(plane);
                        g2.drawLine(size - 1, 2, (int) (size / 2.0), (int) (size / 2.0 + 2));
                    }
                }
                g2.dispose();
            }

            @Override
            public int getIconWidth() { return size; }

            @Override
            public int getIconHeight() { return size; }
        };
    }

    private void applyComponentColors() {
        leftNavPanel.setBackground(ThemeManager.BG_SIDEBAR);
        leftNavPanel.setBorder(new MatteBorder(0, 0, 0, 1, ThemeManager.BORDER_COLOR));

        middleListPanel.setBackground(ThemeManager.BG_APP);
        middleListPanel.setBorder(new MatteBorder(0, 0, 0, 1, ThemeManager.BORDER_COLOR));
        peerCardsContainer.setBackground(ThemeManager.BG_APP);

        rightChatPanel.setBackground(ThemeManager.BG_APP);
        chatHeaderPanel.setBorder(BorderFactory.createCompoundBorder(
            new MatteBorder(0, 0, 1, 0, ThemeManager.BORDER_COLOR),
            new EmptyBorder(10, 20, 10, 20)
        ));
        chatMessagesContainer.setBackground(ThemeManager.BG_APP);
        chatScrollPane.getViewport().setBackground(ThemeManager.BG_APP);

        myNameLabel.setForeground(ThemeManager.TEXT_PRIMARY);
        myIpLabel.setForeground(ThemeManager.TEXT_SECONDARY);
        headerNameLabel.setForeground(ThemeManager.TEXT_PRIMARY);
        headerSubLabel.setForeground(ThemeManager.TEXT_SECONDARY);

        searchField.setBackground(ThemeManager.BG_INPUT);
        searchField.setForeground(ThemeManager.TEXT_PRIMARY);

        messageInputPane.setBackground(ThemeManager.BG_INPUT);
        String[] activeHexes = ThemeManager.isDarkMode
                ? ThemeManager.FORMAT_COLOR_HEX_DARK
                : ThemeManager.FORMAT_COLOR_HEX_LIGHT;
        btnColorPicker.setForeground(Color.decode(activeHexes[selectedColorIndex]));
        updateInputLiveFormatting();
    }

    private void showColorPalettePopup() {
        JPopupMenu menu = new JPopupMenu();
        String[] activeHexes = ThemeManager.isDarkMode
                ? ThemeManager.FORMAT_COLOR_HEX_DARK
                : ThemeManager.FORMAT_COLOR_HEX_LIGHT;

        for (int i = 0; i < ThemeManager.FORMAT_COLOR_NAMES.length; i++) {
            final int idx = i;
            Color swatchColor = Color.decode(activeHexes[i]);
            JMenuItem item = new JMenuItem(ThemeManager.FORMAT_COLOR_NAMES[i]);
            item.setForeground(swatchColor);
            item.setFont(new Font("Segoe UI", Font.BOLD, 12));
            item.addActionListener(e -> {
                selectedColorIndex = idx;
                btnColorPicker.setText("Color: " + ThemeManager.FORMAT_COLOR_NAMES[idx]);
                btnColorPicker.setForeground(swatchColor);
                updateInputLiveFormatting();
            });
            menu.add(item);
        }
        menu.show(btnColorPicker, 0, -menu.getPreferredSize().height);
    }

    private void updateInputLiveFormatting() {
        StyledDocument doc = messageInputPane.getStyledDocument();
        SimpleAttributeSet attrs = new SimpleAttributeSet();
        StyleConstants.setItalic(attrs, btnItalic.isSelected());
        StyleConstants.setUnderline(attrs, btnUnderline.isSelected());

        String[] activeHexes = ThemeManager.isDarkMode
                ? ThemeManager.FORMAT_COLOR_HEX_DARK
                : ThemeManager.FORMAT_COLOR_HEX_LIGHT;
        StyleConstants.setForeground(attrs, Color.decode(activeHexes[selectedColorIndex]));

        doc.setCharacterAttributes(0, doc.getLength(), attrs, false);
        messageInputPane.setCharacterAttributes(attrs, false);
    }

    private void refreshPeerList() {
        peerCardsContainer.removeAll();

        // 1. Private Groups
        for (GroupInfo g : networkManager.getActiveGroups().values()) {
            if (!searchFilter.isEmpty() && !g.groupName().toLowerCase().contains(searchFilter)) {
                continue;
            }
            String targetKey = "GROUP:" + g.groupId();
            boolean isSelected = targetKey.equals(activeTargetId);
            peerCardsContainer.add(createPeerCard(
                g.groupName(),
                g.memberIps().size() + " members • Private Group",
                true,
                isSelected,
                () -> switchActiveConversation(targetKey, g.groupName(), true)
            ));
        }

        // 2. All Registered Workstations (Both ONLINE and OFFLINE persisted/synced from DB)
        List<PeerInfo> allPeers = networkManager.getDb().getAllRegisteredWorkstations();
        for (PeerInfo p : allPeers) {
            if (p.hostname().equalsIgnoreCase(networkManager.getLocalHostname()) && p.ip().equals(networkManager.getLocalIp())) continue;
            if (!searchFilter.isEmpty()
                    && !p.username().toLowerCase().contains(searchFilter)
                    && !p.ip().contains(searchFilter)) {
                continue;
            }
            boolean online = networkManager.getActivePeers().containsKey(p.ip());
            boolean isSelected = p.ip().equals(activeTargetId);
            peerCardsContainer.add(createPeerCard(
                p.username(),
                p.ip() + (online ? " • ONLINE" : " • OFFLINE"),
                online,
                isSelected,
                () -> switchActiveConversation(p.ip(), p.username(), false)
            ));
        }

        peerCardsContainer.revalidate();
        peerCardsContainer.repaint();
    }

    private JPanel createPeerCard(String title, String subtitle, boolean isOnline, boolean isSelected, Runnable onClick) {
        JPanel card = new JPanel(new BorderLayout(10, 2));
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 64));
        card.setPreferredSize(new Dimension(275, 64));
        card.setCursor(new Cursor(Cursor.HAND_CURSOR));
        card.setBackground(isSelected ? ThemeManager.BG_ACTIVE_ITEM : ThemeManager.BG_APP);
        card.setBorder(BorderFactory.createCompoundBorder(
            new MatteBorder(0, isSelected ? 4 : 0, 1, 0, isSelected ? ThemeManager.ACCENT_BLUE : ThemeManager.BORDER_COLOR),
            new EmptyBorder(10, 14, 10, 14)
        ));

        JLabel dot = new JLabel("•");
        dot.setFont(new Font("Segoe UI", Font.BOLD, 18));
        dot.setForeground(isOnline ? new Color(0x10, 0xB9, 0x81) : ThemeManager.TEXT_SECONDARY);

        JPanel textCol = new JPanel(new GridLayout(2, 1));
        textCol.setOpaque(false);
        JLabel nameLbl = new JLabel(title);
        nameLbl.setFont(new Font("Segoe UI", Font.BOLD, 13));
        nameLbl.setForeground(ThemeManager.TEXT_PRIMARY);

        JLabel subLbl = new JLabel(subtitle);
        subLbl.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        subLbl.setForeground(ThemeManager.TEXT_SECONDARY);

        textCol.add(nameLbl);
        textCol.add(subLbl);

        card.add(dot, BorderLayout.WEST);
        card.add(textCol, BorderLayout.CENTER);

        card.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                onClick.run();
            }
        });
        return card;
    }

    private void switchActiveConversation(String targetId, String displayName, boolean isGroup) {
        this.activeTargetId = targetId;
        this.activeIsGroup = isGroup;

        headerNameLabel.setText(displayName);
        headerSubLabel.setText(isGroup ? "Private LAN Group Channel" : targetId + " • Direct TCP Socket");
        btnGroupMembers.setVisible(isGroup);

        refreshPeerList();
        renderActiveConversation();
    }

    private void renderActiveConversation() {
        chatMessagesContainer.removeAll();
        if (activeTargetId == null) {
            chatMessagesContainer.revalidate();
            chatMessagesContainer.repaint();
            return;
        }

        List<ChatEntry> history = conversations.computeIfAbsent(activeTargetId, k -> new ArrayList<>());
        for (ChatEntry entry : history) {
            if (entry.isFileCard) {
                chatMessagesContainer.add(buildIncomingFileCardRow(entry));
            } else {
                chatMessagesContainer.add(buildMessageBubbleRow(entry));
            }
            chatMessagesContainer.add(Box.createVerticalStrut(10));
        }

        chatMessagesContainer.revalidate();
        chatMessagesContainer.repaint();
        SwingUtilities.invokeLater(() -> {
            JScrollBar vertical = chatScrollPane.getVerticalScrollBar();
            vertical.setValue(vertical.getMaximum());
        });
    }

    private JPanel buildMessageBubbleRow(ChatEntry entry) {
        JPanel row = new JPanel(new FlowLayout(entry.isOutgoing ? FlowLayout.RIGHT : FlowLayout.LEFT, 0, 0));
        row.setOpaque(false);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 190));

        String[] activeHexes = ThemeManager.isDarkMode
                ? ThemeManager.FORMAT_COLOR_HEX_DARK
                : ThemeManager.FORMAT_COLOR_HEX_LIGHT;
        int safeIdx = (entry.colorIdx >= 0 && entry.colorIdx < activeHexes.length) ? entry.colorIdx : 0;
        String hexColor = (entry.isOutgoing && safeIdx == 0) ? "#FFFFFF" : activeHexes[safeIdx];
        String metaColor = entry.isOutgoing ? "#DBEAFE" : (ThemeManager.isDarkMode ? "#94A3B8" : "#64748B");

        String styledBody = escapeHtml(entry.text).replace("\n", "<br>");
        if (entry.underline) styledBody = "<u>" + styledBody + "</u>";
        if (entry.italic) styledBody = "<i>" + styledBody + "</i>";

        String formattedHtml = "<html><body style='width: 290px; font-family: Segoe UI; font-size: 13px;'>"
                + "<span style='color: " + hexColor + ";'>" + styledBody + "</span>"
                + "<div style='font-size: 10px; margin-top: 5px; color: " + metaColor + ";'>"
                + escapeHtml(entry.sender) + " • " + entry.time
                + "</div></body></html>";

        JLabel bubbleLabel = new JLabel(formattedHtml);
        bubbleLabel.setBorder(new EmptyBorder(10, 14, 10, 14));

        JPanel bubbleCard = new JPanel(new BorderLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(entry.isOutgoing ? ThemeManager.BUBBLE_OUTGOING : ThemeManager.BUBBLE_INCOMING);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 16, 16);
                g2.dispose();
            }
        };
        bubbleCard.setOpaque(false);
        bubbleCard.add(bubbleLabel, BorderLayout.CENTER);

        row.add(bubbleCard);
        return row;
    }

    // Translucent Tinted Accept (Emerald) & Decline (Crimson) File Transfer Card
    private JPanel buildIncomingFileCardRow(ChatEntry entry) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        row.setOpaque(false);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 145));

        JPanel card = new JPanel(new BorderLayout(10, 10));
        card.setPreferredSize(new Dimension(340, 125));
        card.setBackground(ThemeManager.BUBBLE_INCOMING);
        card.setBorder(BorderFactory.createCompoundBorder(
            new LineBorder(ThemeManager.BORDER_COLOR, 1, true),
            new EmptyBorder(12, 14, 12, 14)
        ));

        JPanel infoStack = new JPanel(new GridLayout(2, 1, 0, 2));
        infoStack.setOpaque(false);

        JLabel lblFile = new JLabel(entry.text);
        lblFile.setFont(new Font("Segoe UI", Font.BOLD, 13));
        lblFile.setForeground(ThemeManager.TEXT_PRIMARY);

        String sizeStr = entry.fileSize >= 1024 * 1024
                ? String.format("%.1f MB", entry.fileSize / (1024.0 * 1024.0))
                : Math.max(1, entry.fileSize / 1024) + " KB";
        JLabel lblMeta = new JLabel("From " + entry.sender + " • " + sizeStr + " • " + entry.fileStatusText);
        lblMeta.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        lblMeta.setForeground(ThemeManager.TEXT_SECONDARY);

        infoStack.add(lblFile);
        infoStack.add(lblMeta);
        card.add(infoStack, BorderLayout.CENTER);

        JPanel btnRow = new JPanel(new GridLayout(1, 2, 10, 0));
        btnRow.setOpaque(false);

        JButton btnAccept = createPositiveTintedButton("ACCEPT");
        JButton btnDecline = createNegativeTintedButton("DECLINE");

        boolean pending = entry.fileDecisionFuture != null && !entry.fileDecisionFuture.isDone();
        btnAccept.setEnabled(pending);
        btnDecline.setEnabled(pending);

        btnAccept.addActionListener(e -> {
            JFileChooser chooser = createStyledFileChooser(networkManager.getDefaultDownloadDir());
            chooser.setSelectedFile(new File(networkManager.getDefaultDownloadDir(), entry.text));
            if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
                File dest = chooser.getSelectedFile();
                entry.fileStatusText = "Accepted";
                entry.fileDecisionFuture.complete(dest);
                renderActiveConversation();
            }
        });

        btnDecline.addActionListener(e -> {
            entry.fileStatusText = "Declined";
            entry.fileDecisionFuture.complete(null);
            renderActiveConversation();
        });

        btnRow.add(btnAccept);
        btnRow.add(btnDecline);
        card.add(btnRow, BorderLayout.SOUTH);

        row.add(card);
        return row;
    }

    private JButton createPositiveTintedButton(String text) {
        return createTintedActionButton(text, POS_BG_FILL, POS_BORDER, POS_TEXT);
    }

    private JButton createNegativeTintedButton(String text) {
        return createTintedActionButton(text, NEG_BG_FILL, NEG_BORDER, NEG_TEXT);
    }

    private JButton createTintedActionButton(String text, Color bgFill, Color borderCol, Color textCol) {
        JButton btn = new JButton(text) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(isEnabled() ? bgFill : new Color(0x28, 0x30, 0x3D));
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
                g2.setColor(isEnabled() ? borderCol : ThemeManager.BORDER_COLOR);
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 8, 8);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        btn.setFont(new Font("Segoe UI", Font.BOLD, 11));
        btn.setForeground(textCol);
        btn.setContentAreaFilled(false);
        btn.setBorderPainted(false);
        btn.setFocusPainted(false);
        btn.setPreferredSize(new Dimension(Math.max(92, text.length() * 9 + 24), 32));
        btn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        return btn;
    }

    private void styleAsPositiveButton(JButton btn) {
        btn.setBackground(POS_BG_FILL);
        btn.setForeground(POS_TEXT);
        btn.setFont(new Font("Segoe UI", Font.BOLD, 12));
        btn.setBorder(BorderFactory.createCompoundBorder(
            new LineBorder(POS_BORDER, 1, true),
            new EmptyBorder(6, 16, 6, 16)
        ));
        btn.setOpaque(true);
        btn.setFocusPainted(false);
        btn.setCursor(new Cursor(Cursor.HAND_CURSOR));
    }

    private void styleAsNegativeButton(JButton btn) {
        btn.setBackground(NEG_BG_FILL);
        btn.setForeground(NEG_TEXT);
        btn.setFont(new Font("Segoe UI", Font.BOLD, 12));
        btn.setBorder(BorderFactory.createCompoundBorder(
            new LineBorder(NEG_BORDER, 1, true),
            new EmptyBorder(6, 16, 6, 16)
        ));
        btn.setOpaque(true);
        btn.setFocusPainted(false);
        btn.setCursor(new Cursor(Cursor.HAND_CURSOR));
    }

    // Configures JFileChooser with Positive/Negative button palette and Right-Side Vertical Scrollbar
    private JFileChooser createStyledFileChooser(File initialDir) {
        JFileChooser chooser = initialDir != null ? new JFileChooser(initialDir) : new JFileChooser();
        chooser.setPreferredSize(new Dimension(680, 440));
        configureFileChooserLayout(chooser);
        return chooser;
    }

    private void configureFileChooserLayout(Container container) {
        for (Component c : container.getComponents()) {
            if (c instanceof JScrollPane sp) {
                sp.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
                sp.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
            } else if (c instanceof JList<?> list) {
                list.setLayoutOrientation(JList.VERTICAL);
            } else if (c instanceof JButton btn) {
                String txt = btn.getText();
                if ("Open".equalsIgnoreCase(txt) || "Save".equalsIgnoreCase(txt)) {
                    styleAsPositiveButton(btn);
                } else if ("Cancel".equalsIgnoreCase(txt)) {
                    styleAsNegativeButton(btn);
                }
            }
            if (c instanceof Container child) {
                configureFileChooserLayout(child);
            }
        }
    }

    private void dispatchCurrentMessage() {
        String rawText = messageInputPane.getText().trim();
        if (rawText.isEmpty() || activeTargetId == null) return;

        boolean italic = btnItalic.isSelected();
        boolean underline = btnUnderline.isSelected();
        int colorIdx = selectedColorIndex;
        String time = LocalTime.now().format(DateTimeFormatter.ofPattern("hh:mm a"));

        messageInputPane.setText("");
        updateInputLiveFormatting();

        conversations.computeIfAbsent(activeTargetId, k -> new ArrayList<>())
                .add(new ChatEntry("Me", time, rawText, true, italic, underline, colorIdx));
        renderActiveConversation();

        String wirePayload = "[FMT:i=" + (italic ? 1 : 0) + ",u=" + (underline ? 1 : 0) + ",c=" + colorIdx + "]" + rawText;

        if (activeIsGroup) {
            String gId = activeTargetId.substring(6);
            networkManager.sendGroupMessageAsync(gId, wirePayload, err ->
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, err, "Group Notice", JOptionPane.WARNING_MESSAGE))
            );
        } else {
            networkManager.sendMessageAsync(activeTargetId, wirePayload, err ->
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, "Peer is currently offline.", "Offline", JOptionPane.INFORMATION_MESSAGE))
            );
        }
    }

    private void bindNetworkListeners() {
        networkManager.setOnPeersChangedListener(() -> SwingUtilities.invokeLater(this::refreshPeerList));
        networkManager.setOnGroupCreatedListener(g -> SwingUtilities.invokeLater(this::refreshPeerList));

        networkManager.setOnMessageReceivedListener((sender, senderIp, time, wireText) ->
            SwingUtilities.invokeLater(() -> handleIncomingMessage(senderIp, sender, sender, time, wireText, false))
        );

        networkManager.setOnGroupMessageReceivedListener((groupId, sender, time, wireText) ->
            SwingUtilities.invokeLater(() -> {
                GroupInfo g = networkManager.getActiveGroups().get(groupId);
                if (g == null) return;
                handleIncomingMessage("GROUP:" + groupId, g.groupName(), sender, time, wireText, true);
            })
        );

        networkManager.setOnFileProposalListener((convId, sender, fileName, size) -> {
            CompletableFuture<File> decisionFuture = new CompletableFuture<>();
            SwingUtilities.invokeLater(() -> {
                String time = LocalTime.now().format(DateTimeFormatter.ofPattern("hh:mm a"));
                ChatEntry fileEntry = new ChatEntry(sender, time, fileName, false, false, false, 0, true, size, decisionFuture);
                conversations.computeIfAbsent(convId, k -> new ArrayList<>()).add(fileEntry);

                String title = sender;
                boolean isGrp = convId.startsWith("GROUP:");
                if (isGrp) {
                    GroupInfo g = networkManager.getActiveGroups().get(convId.substring(6));
                    if (g != null) title = g.groupName();
                }
                showIncomingPopupNotification(convId, title, sender, "Incoming file: " + fileName, isGrp);
                if (convId.equals(activeTargetId)) {
                    renderActiveConversation();
                }
            });
            try {
                return decisionFuture.get();
            } catch (Exception e) {
                return null;
            }
        });
    }

    private void handleIncomingMessage(String targetId, String conversationTitle, String senderName, String time, String wireText, boolean isGroup) {
        Toolkit.getDefaultToolkit().beep();

        boolean italic = false;
        boolean underline = false;
        int colorIdx = 0;
        String cleanText = wireText;

        Matcher m = Pattern.compile("^\\[FMT:i=(\\d),u=(\\d),c=(\\d+)\\](.*)$", Pattern.DOTALL).matcher(wireText);
        if (m.matches()) {
            italic = "1".equals(m.group(1));
            underline = "1".equals(m.group(2));
            colorIdx = Integer.parseInt(m.group(3));
            cleanText = m.group(4);
        }

        conversations.computeIfAbsent(targetId, k -> new ArrayList<>())
                .add(new ChatEntry(senderName, time, cleanText, false, italic, underline, colorIdx));

        if (targetId.equals(activeTargetId)) {
            renderActiveConversation();
        }
        showIncomingPopupNotification(targetId, conversationTitle, senderName, cleanText, isGroup);
    }

    // Incoming Message Toast Notification with Positive "Reply" & Negative "Dismiss" Buttons
    private void showIncomingPopupNotification(String targetId, String conversationTitle, String senderName, String previewText, boolean isGroup) {
        JWindow toast = new JWindow(this);
        toast.setAlwaysOnTop(true);

        JPanel root = new JPanel(new BorderLayout(10, 8));
        root.setBackground(ThemeManager.BG_SIDEBAR);
        root.setBorder(BorderFactory.createCompoundBorder(
            new LineBorder(ThemeManager.ACCENT_BLUE, 1, true),
            new EmptyBorder(12, 14, 12, 14)
        ));

        JPanel textCol = new JPanel(new GridLayout(2, 1, 0, 3));
        textCol.setOpaque(false);

        String headerStr = isGroup ? (senderName + " in " + conversationTitle) : senderName;
        JLabel lblTitle = new JLabel(headerStr);
        lblTitle.setFont(new Font("Segoe UI", Font.BOLD, 13));
        lblTitle.setForeground(ThemeManager.TEXT_PRIMARY);

        String clipped = previewText.length() > 54 ? previewText.substring(0, 51) + "..." : previewText;
        JLabel lblBody = new JLabel(clipped);
        lblBody.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        lblBody.setForeground(ThemeManager.TEXT_SECONDARY);

        textCol.add(lblTitle);
        textCol.add(lblBody);

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        btnPanel.setOpaque(false);

        JButton btnReply = createPositiveTintedButton("Reply");
        JButton btnDismiss = createNegativeTintedButton("Dismiss");

        btnReply.addActionListener(e -> {
            toast.dispose();
            setExtendedState(JFrame.NORMAL);
            toFront();
            requestFocus();
            switchActiveConversation(targetId, conversationTitle, isGroup);
            messageInputPane.requestFocusInWindow();
        });

        btnDismiss.addActionListener(e -> toast.dispose());

        btnPanel.add(btnReply);
        btnPanel.add(btnDismiss);

        root.add(textCol, BorderLayout.CENTER);
        root.add(btnPanel, BorderLayout.SOUTH);

        toast.setContentPane(root);
        toast.setSize(345, 114);

        Rectangle screenBounds = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        toast.setLocation(screenBounds.width - 365, screenBounds.height - 134);
        toast.setVisible(true);
    }

    // Dedicated Group Members Viewer Dialog
    private void openGroupMembersDialog() {
        if (!activeIsGroup || activeTargetId == null) return;
        String groupId = activeTargetId.substring(6);
        GroupInfo group = networkManager.getActiveGroups().get(groupId);
        if (group == null) return;

        JDialog dialog = new JDialog(this, "Group Members • " + group.groupName(), true);
        dialog.setSize(380, 340);
        dialog.setLocationRelativeTo(this);
        dialog.setLayout(new BorderLayout(10, 10));

        JPanel listPanel = new JPanel();
        listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
        listPanel.setBorder(new EmptyBorder(12, 14, 12, 14));

        for (String memberIp : group.memberIps()) {
            String displayName = memberIp;
            boolean online = false;
            if (memberIp.equals(networkManager.getLocalIp())) {
                displayName = networkManager.getLocalUsername() + " (You)";
                online = true;
            } else if (networkManager.getActivePeers().containsKey(memberIp)) {
                displayName = networkManager.getActivePeers().get(memberIp).username();
                online = true;
            } else {
                for (PeerInfo dbPeer : networkManager.getDb().getAllRegisteredWorkstations()) {
                    if (dbPeer.ip().equals(memberIp)) {
                        displayName = dbPeer.username();
                        break;
                    }
                }
            }

            JPanel memberRow = new JPanel(new BorderLayout());
            memberRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
            memberRow.setBorder(new EmptyBorder(6, 4, 6, 4));
            JLabel lblName = new JLabel(displayName + " (" + memberIp + ")");
            lblName.setFont(new Font("Segoe UI", Font.BOLD, 12));
            JLabel lblStatus = new JLabel(online ? "ONLINE" : "OFFLINE");
            lblStatus.setFont(new Font("Segoe UI", Font.BOLD, 11));
            lblStatus.setForeground(online ? POS_TEXT : ThemeManager.TEXT_SECONDARY);

            memberRow.add(lblName, BorderLayout.WEST);
            memberRow.add(lblStatus, BorderLayout.EAST);
            listPanel.add(memberRow);
        }

        JButton btnClose = createNegativeTintedButton("Close");
        btnClose.addActionListener(e -> dialog.dispose());
        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        footer.add(btnClose);

        dialog.add(new JScrollPane(listPanel), BorderLayout.CENTER);
        dialog.add(footer, BorderLayout.SOUTH);
        dialog.setVisible(true);
    }

    private void openCreateGroupDialog() {
        JDialog dialog = new JDialog(this, "Create Private LAN Group", true);
        dialog.setSize(390, 410);
        dialog.setLocationRelativeTo(this);
        dialog.setLayout(new BorderLayout(10, 10));

        JPanel top = new JPanel(new BorderLayout(6, 6));
        top.setBorder(new EmptyBorder(12, 14, 4, 14));
        top.add(new JLabel("Private Group Name:"), BorderLayout.NORTH);
        JTextField nameFld = new JTextField();
        top.add(nameFld, BorderLayout.CENTER);

        JPanel listPanel = new JPanel();
        listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
        List<JCheckBox> boxes = new ArrayList<>();
        for (PeerInfo p : networkManager.getActivePeers().values()) {
            JCheckBox cb = new JCheckBox(p.username() + " (" + p.ip() + ")");
            cb.setActionCommand(p.ip());
            boxes.add(cb);
            listPanel.add(cb);
        }

        JButton btnCancel = createNegativeTintedButton("Cancel");
        btnCancel.addActionListener(e -> dialog.dispose());

        JButton btnCreate = createPositiveTintedButton("Create Group");
        btnCreate.addActionListener(e -> {
            String gName = nameFld.getText().trim();
            List<String> selectedIps = new ArrayList<>();
            for (JCheckBox cb : boxes) {
                if (cb.isSelected()) selectedIps.add(cb.getActionCommand());
            }
            if (gName.isEmpty() || selectedIps.isEmpty()) {
                JOptionPane.showMessageDialog(dialog, "Enter a group name and select at least one member.");
                return;
            }
            networkManager.createAndBroadcastGroup(gName, selectedIps);
            dialog.dispose();
            refreshPeerList();
        });

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        bottom.add(btnCancel);
        bottom.add(btnCreate);

        dialog.add(top, BorderLayout.NORTH);
        dialog.add(new JScrollPane(listPanel), BorderLayout.CENTER);
        dialog.add(bottom, BorderLayout.SOUTH);
        dialog.setVisible(true);
    }

    private void openSettingsDialog() {
        JDialog settings = new JDialog(this, "Settings & Central Host Role", true);
        settings.setSize(450, 310);
        settings.setLocationRelativeTo(this);
        settings.setLayout(new BorderLayout(10, 10));

        JPanel form = new JPanel(new GridLayout(5, 1, 6, 6));
        form.setBorder(new EmptyBorder(14, 16, 8, 16));
        form.add(new JLabel("Display Username:"));
        JTextField nameFld = new JTextField(networkManager.getLocalUsername());
        form.add(nameFld);

        form.add(new JLabel("Default Download Folder:"));
        JTextField dirFld = new JTextField(networkManager.getDefaultDownloadDir().getAbsolutePath());
        dirFld.setEditable(false);
        form.add(dirFld);

        JCheckBox chkCentralHost = new JCheckBox("Act as Central SQLite Database Server (Restart required to switch)");
        chkCentralHost.setSelected(networkManager.isCentralHost());
        form.add(chkCentralHost);

        JButton btnBrowse = new JButton("Browse...");
        btnBrowse.addActionListener(e -> {
            JFileChooser fc = createStyledFileChooser(networkManager.getDefaultDownloadDir());
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (fc.showOpenDialog(settings) == JFileChooser.APPROVE_OPTION) {
                dirFld.setText(fc.getSelectedFile().getAbsolutePath());
            }
        });

        JButton btnCancel = createNegativeTintedButton("Cancel");
        btnCancel.addActionListener(e -> settings.dispose());

        JButton btnSave = createPositiveTintedButton("Save Changes");
        btnSave.addActionListener(e -> {
            String updated = nameFld.getText().trim();
            if (!updated.isEmpty()) {
                networkManager.setLocalUsername(updated);
                myNameLabel.setText(updated);
                setTitle("connect-o-LAN - [" + updated + "]");
            }
            networkManager.setDefaultDownloadDir(new File(dirFld.getText()));

            Preferences prefs = Preferences.userRoot().node("connect_o_lan");
            boolean prevRole = prefs.getBoolean("is_central_host", false);
            boolean newRole = chkCentralHost.isSelected();
            prefs.putBoolean("is_central_host", newRole);

            settings.dispose();
            if (prevRole != newRole) {
                JOptionPane.showMessageDialog(this, "Database role updated. Please restart connect-o-LAN for the change to take effect.");
            }
        });

        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        footer.add(btnBrowse);
        footer.add(btnCancel);
        footer.add(btnSave);

        settings.add(form, BorderLayout.CENTER);
        settings.add(footer, BorderLayout.SOUTH);
        settings.setVisible(true);
    }

    private void onSendFileClicked() {
        if (activeTargetId == null) {
            JOptionPane.showMessageDialog(this, "Select a peer or private group first to transfer a file.");
            return;
        }
        JFileChooser chooser = createStyledFileChooser(null);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File file = chooser.getSelectedFile();
            if (activeIsGroup) {
                String groupId = activeTargetId.substring(6);
                networkManager.sendGroupFileAsync(groupId, file,
                    ok -> SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, ok, "Group Transfer", JOptionPane.INFORMATION_MESSAGE)),
                    err -> SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, err, "Group Notice", JOptionPane.WARNING_MESSAGE))
                );
            } else {
                networkManager.sendFileAsync(activeTargetId, "DIRECT", file,
                    ok -> SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, ok, "Transfer Complete", JOptionPane.INFORMATION_MESSAGE)),
                    err -> SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, "Transfer Failed: " + err, "Error", JOptionPane.ERROR_MESSAGE))
                );
            }
        }
    }

    private String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private void loadLogo() {
        try {
            java.net.URL imgUrl = getClass().getResource("/assets/logo.png");
            if (imgUrl != null) {
                appLogo = new ImageIcon(imgUrl).getImage();
                return;
            }
            File file = new File("assets/logo.png");
            if (file.exists()) appLogo = new ImageIcon(file.getAbsolutePath()).getImage();
        } catch (Exception ignored) {}
    }
}