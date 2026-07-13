package com.mqttclient;

import com.mqttclient.ui.MainWindow;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * 程序入口。
 *
 * <p>对应 Python 版 main.py：启动 Swing 事件循环并显示主窗口。
 * Python 版针对 Qt 的平台环境变量设置 (xcb/windows) 在 Swing 下不需要。
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
