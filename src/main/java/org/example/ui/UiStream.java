package org.example.ui;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * 把 System.out 的字节流按【行】分流出去。
 *
 * 用法（Main 里）：
 *   PrintStream 老out = System.out;
 *   System.setOut(new PrintStream(new UiStream(老out, window::appendLine), true, UTF_8));
 *
 * 于是全项目那几十处 System.out.println 一行都不用改，窗口里就能看到。
 * 同时【终端还留一份】——调试的时候终端还是满的，出问题好查。
 *
 * 【为什么攒到 '\n' 才发】
 * 一个字节发一次的话，UI 会碎成一地（每写一个字符就刷一次）。
 * flush() 会把"还没等到换行的半截"也发出去，对应 System.out.print("...") 这种不换行的用法。
 */
public class UiStream extends OutputStream {

    /** 原来的 System.out。为 null 表示不往终端转发 */
    private final OutputStream tee;

    /** 每攒够一行就交给它 */
    private final Consumer<String> sink;

    /** 当前这一行里、还没见着 '\n' 的字节 */
    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

    public UiStream(OutputStream tee, Consumer<String> sink) {
        this.tee = tee;
        this.sink = sink;
    }

    @Override
    public synchronized void write(int b) {
        if (tee != null) {
            try {
                tee.write(b);
            } catch (IOException ignored) {
                // 终端写不进去（比如被关了）不该拖垮窗口，忽略
            }
        }
        if (b == '\n') {
            emitLine();
        } else {
            pending.write(b);
        }
    }

    @Override
    public synchronized void flush() {
        if (pending.size() > 0) {
            emitLine();
        }
        if (tee != null) {
            try {
                tee.flush();
            } catch (IOException ignored) {
                // 同上
            }
        }
    }

    /**
     * 把攒下的一行交出去。
     * 末尾的 '\r' 是 Windows 换行符（\r\n）留下的，去掉。
     */
    private void emitLine() {
        String line = pending.toString(StandardCharsets.UTF_8);
        pending.reset();
        if (line.endsWith("\r")) {
            line = line.substring(0, line.length() - 1);
        }
        sink.accept(line);
    }
}
