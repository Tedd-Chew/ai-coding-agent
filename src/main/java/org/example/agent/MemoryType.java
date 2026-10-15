package org.example.agent;

/**
 * 记忆类型，对应 SKILL 的风格：写入时校验，读取时宽松。
 * label 是存进 frontmatter 的字符串。
 */
public enum MemoryType {

    /** 用户的长期偏好，如"用 tab 缩进" */
    USER("user"),

    /** 以后仍适用的工作反馈，如"不要 mock 数据库" */
    FEEDBACK("feedback"),

    /** 稳定的项目事实，如"认证重写由合规要求驱动" */
    PROJECT("project"),

    /** 外部资料或查找线索，如"流水线问题记录在 Linear INGEST" */
    REFERENCE("reference");

    private final String label;

    MemoryType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** 解析 frontmatter / LLM 返回的字符串，无法识别返回 null */
    public static MemoryType from(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase();
        for (MemoryType type : values()) {
            if (type.label.equals(normalized)) {
                return type;
            }
        }
        return null;
    }
}
