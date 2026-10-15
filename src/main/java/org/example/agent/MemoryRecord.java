package org.example.agent;

/**
 * 一条记忆。
 *
 * 文件名由 name 派生（slug 化），所以不存字段——见 MemoryStore.filenameOf。
 * type 允许为 null：手写或外部产生的文件可能有缺失/非法 type，读取时宽松处理，
 * 校验只在写入前做。
 */
public record MemoryRecord(String name, MemoryType type, String description, String body) {}

