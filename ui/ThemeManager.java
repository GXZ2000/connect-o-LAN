package ui;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;

import javax.swing.*;
import java.awt.*;

public class ThemeManager {
    public static boolean isDarkMode = true;

    public static Color BG_APP;          // Main viewport & peer list background
    public static Color BG_SIDEBAR;      // Left navigation rail & card surfaces
    public static Color BG_INPUT;        // Search bar & text input field
    public static Color BG_ACTIVE_ITEM;  // Selected peer highlight
    public static Color BORDER_COLOR;    // Subtle 1px dividers
    public static Color TEXT_PRIMARY;    // Main headings & peer names
    public static Color TEXT_SECONDARY;  // IPs, timestamps, offline labels
    public static Color ACCENT_BLUE;     // Primary buttons & active indicators
    public static Color BUBBLE_INCOMING; // Received message bubble background
    public static Color BUBBLE_OUTGOING; // Sent message bubble background

    // Curated Text Colors readable on BOTH Dark & Light bubbles
    public static final String[] FORMAT_COLOR_NAMES = {
        "Default", "Sky Cyan", "Amber Gold", "Emerald Mint", "Coral Rose", "Violet"
    };
    public static final String[] FORMAT_COLOR_HEX_DARK = {
        "#F1F5F9", "#38BDF8", "#FBBF24", "#34D399", "#FB7185", "#C084FC"
    };
    public static final String[] FORMAT_COLOR_HEX_LIGHT = {
        "#0F172A", "#0284C7", "#B45309", "#047857", "#BE123C", "#7E22CE"
    };

    public static void applyTheme(boolean dark) {
        isDarkMode = dark;
        try {
            if (dark) {
                UIManager.setLookAndFeel(new FlatDarkLaf());
                // Exact colors from your HTML/AtlantaFX prototype
                BG_APP          = new Color(0x0F, 0x17, 0x2A); // slate-900
                BG_SIDEBAR      = new Color(0x1E, 0x29, 0x3B); // slate-800
                BG_INPUT        = new Color(0x1E, 0x29, 0x3B); // slate-800
                BG_ACTIVE_ITEM  = new Color(0x33, 0x41, 0x55); // slate-700
                BORDER_COLOR    = new Color(0x33, 0x41, 0x55); // slate-700
                TEXT_PRIMARY    = new Color(0xF1, 0xF5, 0xF9); // slate-100
                TEXT_SECONDARY  = new Color(0x94, 0xA3, 0xB8); // slate-400
                ACCENT_BLUE     = new Color(0x25, 0x63, 0xEB); // blue-600
                BUBBLE_INCOMING = new Color(0x1E, 0x29, 0x3B); // slate-800
                BUBBLE_OUTGOING = new Color(0x25, 0x63, 0xEB); // blue-600
            } else {
                UIManager.setLookAndFeel(new FlatLightLaf());
                // Cohesive Cobalt & Ice Blue Light Mode
                BG_APP          = new Color(0xF4, 0xF8, 0xFD); // Ice Mist Blue
                BG_SIDEBAR      = new Color(0xE6, 0xEF, 0xFA); // Soft Periwinkle Tint
                BG_INPUT        = new Color(0xFF, 0xFF, 0xFF); // Pure White
                BG_ACTIVE_ITEM  = new Color(0xD0, 0xE2, 0xFF); // Sky Selection Tint
                BORDER_COLOR    = new Color(0xBF, 0xD4, 0xEE); // Cool Steel Blue Border
                TEXT_PRIMARY    = new Color(0x0F, 0x17, 0x2A); // Deep Navy Slate
                TEXT_SECONDARY  = new Color(0x47, 0x55, 0x69); // Muted Slate Blue
                ACCENT_BLUE     = new Color(0x1D, 0x4E, 0xD8); // Cobalt Blue-700
                BUBBLE_INCOMING = new Color(0xFF, 0xFF, 0xFF); // Clean White Card
                BUBBLE_OUTGOING = new Color(0x1D, 0x4E, 0xD8); // Cobalt Blue-700
            }

            UIManager.put("Button.arc", 10);
            UIManager.put("Component.arc", 10);
            UIManager.put("TextComponent.arc", 10);
            UIManager.put("ScrollBar.thumbArc", 999);
            UIManager.put("ScrollBar.width", 6);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}