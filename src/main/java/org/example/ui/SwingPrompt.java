package org.example.ui;

import org.example.agent.UserPrompt;

import javax.swing.JOptionPane;
import java.awt.Component;

/**
 * 用 Swing 模态对话框实现授权询问。
 *
 * 【为什么方法上只要一个 synchronized 就够了】
 * Swing 的模态对话框会【阻塞调用它的那条线程】，直到用户点掉按钮为止。
 * 两条线程同时要权限时：先到的那个进方法、拿到锁、弹窗；后到的卡在锁上，
 * 等前一个窗口关掉才轮到它弹。
 *
 * ——"依次弹出、不会乱弹"是这么来的，不需要自己写队列、写 Future、写轮询。
 *
 * 弹窗本身是投给 EDT 去画的，所以从任何线程调用都安全。
 */
public class SwingPrompt implements UserPrompt {

    /** 对话框的父组件，用来定位窗口 */
    private final Component parent;

    public SwingPrompt(Component parent) {
        this.parent = parent;
    }

    @Override
    public synchronized Boolean ask(String message) {
        int choice = JOptionPane.showConfirmDialog(
            parent,
            message,
            "权限请求",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE);
        return choice == JOptionPane.YES_OPTION;
    }
}
