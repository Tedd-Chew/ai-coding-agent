package org.example.agent.tools;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 文件工具共享：路径解析
 *
 * 重要：本类不做安全检查。
 * 路径是否允许访问由 Permission 层决定（s03 单一入口原则）。
 */
public class FileToolUtils {

    /**
     * 把路径解析成绝对路径。
     * 不做越界检查——是否允许访问由 Permission 决定。
     */
    public static Path resolvePath(String input) {
        Path workdir = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        if (Paths.get(input).isAbsolute()) {
            return Paths.get(input).normalize();
        }
        return workdir.resolve(input).normalize();
    }
    public static Path getWorkdir() {
        return Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
    }
}
