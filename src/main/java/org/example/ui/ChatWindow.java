package org.example.ui;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.basic.BasicButtonUI;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.concurrent.BlockingQueue;

/**
 * 聊天窗口：上面是消息区，下面是多行输入框。
 *
 * 【为什么换成窗口，而不是继续用终端】
 * 终端是个行缓冲设备，程序只能"主动去问"用户敲了没有（select / available 轮询），
 * 而且同一时刻只允许一条线程去读 System.in。窗口不一样：用户点"发送"是个事件，
 * 内容自己送到队列里来 —— 没有任何线程需要"等"。
 *
 * 【多行输入】回车是换行（JTextArea 的默认行为），发送用 Ctrl+Enter 或右下角按钮。
 *
 * 【输入怎么交给主循环】不直接回调，而是塞进 inputQueue，主循环 poll 它。
 * 这样窗口完全不知道 Agent 的存在，换掉窗口不影响 Agent。
 */
public class ChatWindow {

    /** 关窗时塞进输入队列的指令，让主循环走正常收尾 */
    public static final String EXIT_COMMAND = "exit";

    private static final int WINDOW_WIDTH = 980;
    private static final int WINDOW_HEIGHT = 720;
    private static final int LOG_ROWS = 28;
    private static final int INPUT_ROWS = 3;
    private static final int GAP = 6;
    private static final int LOG_FONT_SIZE = 13;

    // ==================== 配色（深色，参考 VS Code Dark+） ====================
    /** 窗口底色 */
    private static final Color BG = new Color(0x1e1e1e);
    /** 日志区底色，比窗口稍亮一档，让两块区域分得开 */
    private static final Color SURFACE = new Color(0x252526);
    /** 输入框底色，再亮一档 */
    private static final Color INPUT_BG = new Color(0x2d2d30);
    /** 正文 */
    private static final Color TEXT = new Color(0xd4d4d4);
    /** 输入框和按钮上的文字，比正文亮一点 */
    private static final Color TEXT_STRONG = new Color(0xe8e8e8);
    /** 分隔线 / 边框 */
    private static final Color BORDER = new Color(0x3c3c3c);
    /** 强调色，只用在发送按钮和光标上 */
    private static final Color ACCENT = new Color(0x0e639c);
    private static final Color ACCENT_TEXT = new Color(0xffffff);
    /** 选中文字的背景 */
    private static final Color SELECTION = new Color(0x094771);
    private static final Color SCROLL_THUMB = new Color(0x424242);
    private static final Color SCROLL_TRACK = new Color(0x1e1e1e);

    private final BlockingQueue<String> inputQueue;

    private final JFrame frame = new JFrame("Resume Assistant");
    private final JTextArea logArea = new JTextArea(LOG_ROWS, 80);
    private final JTextArea inputArea = new JTextArea(INPUT_ROWS, 80);

    public ChatWindow(BlockingQueue<String> inputQueue) {
        this.inputQueue = inputQueue;
        buildUi();
    }

    /** 给 JOptionPane 当父组件定位用 */
    public Component component() {
        return frame;
    }

    public void show() {
        SwingUtilities.invokeLater(() -> {
            frame.setVisible(true);
            inputArea.requestFocusInWindow();
        });
    }

    /**
     * 往消息区追加一行。
     * 可能被任何线程调用（主 agent 线程、cron 线程、s13 的队友线程），所以统一转到 EDT 上做。
     */
    public void appendLine(String line) {
        if (SwingUtilities.isEventDispatchThread()) {
            doAppend(line);
        } else {
            SwingUtilities.invokeLater(() -> doAppend(line));
        }
    }

    // ==================== 内部 ====================

    private void buildUi() {
        // 滚动条藏在 JScrollPane 内部拿不到引用，只能改全局默认值。
        // 必须在【创建 JScrollPane 之前】调，之后创建的组件才读得到。
        installDarkDefaults();

        logArea.setEditable(false);
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, LOG_FONT_SIZE));
        logArea.setBackground(SURFACE);
        logArea.setForeground(TEXT);
        logArea.setCaretColor(TEXT);
        logArea.setSelectionColor(SELECTION);
        logArea.setSelectedTextColor(TEXT_STRONG);
        logArea.setMargin(new Insets(10, 12, 10, 12));

        inputArea.setLineWrap(true);
        inputArea.setWrapStyleWord(true);
        inputArea.setBackground(INPUT_BG);
        inputArea.setForeground(TEXT_STRONG);
        inputArea.setCaretColor(ACCENT);
        inputArea.setSelectionColor(SELECTION);
        inputArea.setSelectedTextColor(TEXT_STRONG);
        inputArea.setMargin(new Insets(8, 10, 8, 10));
        inputArea.setBorder(BorderFactory.createLineBorder(BORDER));

        JButton sendButton = new JButton("发送 (Ctrl+Enter)");
        // 默认的 Metal 按钮带渐变和立体边框，深色下很脏。换 BasicUI + 纯色填充
        sendButton.setUI(new BasicButtonUI());
        sendButton.setBackground(ACCENT);
        sendButton.setForeground(ACCENT_TEXT);
        sendButton.setFont(sendButton.getFont().deriveFont(Font.BOLD));
        sendButton.setFocusPainted(false);
        sendButton.setOpaque(true);
        sendButton.setBorder(new EmptyBorder(8, 18, 8, 18));
        sendButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        sendButton.addActionListener(e -> send());

        // 按钮外面包一层：直接放 BorderLayout.EAST 会被拉满输入框的三行高度
        JPanel sendWrap = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        sendWrap.setBackground(BG);
        sendWrap.setBorder(new EmptyBorder(0, GAP, 0, 0));
        sendWrap.add(sendButton);

        JPanel bottom = new JPanel(new BorderLayout(GAP, GAP));
        bottom.setBackground(BG);
        bottom.setBorder(new EmptyBorder(0, GAP, GAP, GAP));
        bottom.add(scrollOf(inputArea, INPUT_BG), BorderLayout.CENTER);
        bottom.add(sendWrap, BorderLayout.EAST);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
            scrollOf(logArea, SURFACE), bottom);
        split.setResizeWeight(0.82);
        split.setBackground(BORDER);
        split.setDividerSize(2);
        split.setBorder(null);

        // 回车留给 JTextArea 自己（换行 → 多行输入），另绑一个 Ctrl+Enter 来发送
        inputArea.getInputMap().put(
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK), "send");
        inputArea.getActionMap().put("send", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                send();
            }
        });

        // 关窗不直接杀进程，塞个 exit 让主循环走 finally 里的收尾
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                inputQueue.offer(EXIT_COMMAND);
            }
        });

        frame.setLayout(new BorderLayout());
        frame.add(split, BorderLayout.CENTER);
        frame.getContentPane().setBackground(BG);
        frame.setSize(WINDOW_WIDTH, WINDOW_HEIGHT);
        frame.setLocationRelativeTo(null);
    }

    /** 包一层 JScrollPane，并把 viewport 底色刷成和内容一致——不刷的话滚动条边缘会露白 */
    private static JScrollPane scrollOf(JTextArea area, Color background) {
        JScrollPane scroll = new JScrollPane(area);
        scroll.setBorder(null);
        scroll.getViewport().setBackground(background);
        return scroll;
    }

    /**
     * 全局默认色。只对【之后创建】的组件生效，所以必须在 new JScrollPane 之前调。
     * 走这条路是因为滚动条藏在 JScrollPane 内部，拿不到引用逐个 set。
     */
    private static void installDarkDefaults() {
        UIManager.put("ScrollBar.background", SCROLL_TRACK);
        UIManager.put("ScrollBar.track", SCROLL_TRACK);
        UIManager.put("ScrollBar.trackHighlight", SCROLL_TRACK);
        UIManager.put("ScrollBar.thumb", SCROLL_THUMB);
        UIManager.put("ScrollBar.thumbDarkShadow", SCROLL_THUMB);
        UIManager.put("ScrollBar.thumbHighlight", SCROLL_THUMB);
        UIManager.put("ScrollBar.thumbShadow", SCROLL_THUMB);
        UIManager.put("ScrollPane.background", BG);
        UIManager.put("Viewport.background", BG);
        UIManager.put("Panel.background", BG);
    }

    /** 发送：内容丢进队列，清空输入框 */
    private void send() {
        String text = inputArea.getText();
        if (text == null || text.isBlank()) {
            return;
        }
        inputArea.setText("");
        // 窗口里留一份自己的话，不然点完发送内容就凭空消失了
        appendLine("");
        appendLine("── You ─────────────────────────");
        appendLine(text);
        inputQueue.offer(text);
        inputArea.requestFocusInWindow();
    }

    private void doAppend(String line) {
        logArea.append(line);
        logArea.append("\n");
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }
}
