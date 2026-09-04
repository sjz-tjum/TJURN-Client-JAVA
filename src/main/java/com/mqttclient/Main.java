package com.mqttclient;

import com.mqttclient.ui.MainWindow;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * Program entry point.
 *
 * <p>Mirrors main.py in the Python version: starts the Swing event loop and shows the main window.
 * The Qt-specific platform environment variables (xcb/windows) used by the Python version are not
 * needed under Swing.
 */
public class Main {

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignore) {
           
        }
        SwingUtilities.invokeLater(() -> new MainWindow().setVisible(true));
    }
}
