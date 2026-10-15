package org.example.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Skill 加载器（单例）
 * 启动时扫描 skills/<name>/SKILL.md，解析 frontmatter，
 * 只把 name + description 注入 system prompt（目录），
 * 完整内容由 load_skill 工具按需读取。
 * frontmatter 手写解析（只识别扁平 key: value），不引 snakeyaml。
 * fallback：name 取目录名，description 取 body 首行去掉 "# "。
 * 安全检查：解析后的路径必须仍在 skills 根下，防符号链接越狱。
 */
public class SkillLoader {
    private static volatile SkillLoader instance;

    private final Path skillsDir;
    private final Map<String,Skill> skills = new LinkedHashMap<>();//保插入序，让 catalog 输出稳定

    private SkillLoader(Path skillsDir)
    {
        this.skillsDir = skillsDir;
        scan();
    }

    public static SkillLoader init(Path skillsDir)
    {
        if (instance == null)
        {
            synchronized (SkillLoader.class)
            {
                if (instance == null)
                {
                    instance = new SkillLoader(skillsDir);
                }
            }
        }
        return instance;
    }

    public static SkillLoader getInstance()
    {
        if (instance == null)
        {
            throw new IllegalStateException("SkillLoader 未初始化，请先调用 init()");
        }
        return instance;
    }


    public record Skill(String name, String description, String content)
    {}
    /** 扫描 目标目录下的所有skills */
    public void scan()
    {
        skills.clear();//清空Map里的所有元素，每次扫描都重新填充元素
        if(!Files.isDirectory(skillsDir))
        {
            return;//判断这个路径是不是一个真实存在的文件夹
        }
        Path root = skillsDir.toAbsolutePath().normalize();
        try(Stream<Path> stream = Files.list(skillsDir))
        {
            //把skills目录下的目录都提取出来。
            List<Path> dirs = stream.filter(Files::isDirectory).sorted().toList();
            for(Path dir : dirs)
            {
                Path mdFile = dir.resolve("SKILL.md");//拼接出完整的skill.md的路径
                if(!Files.isRegularFile(mdFile))
                {
                    continue;
                }
                String text = Files.readString(mdFile);
                Parsed parsed = parse(text);

                String name = parsed.meta.get("name");
                if(name == null || name.isBlank())
                {
                    name = dir.getFileName().toString();
                }

                String description = parsed.meta.get("description");
                if(description == null || description.isBlank())
                {
                    description = firstLine(parsed.body);
                }
                description = description.replaceAll("\\s+", " ").trim();
                skills.put(name, new Skill(name,description,text));
            }
        }catch (IOException e)
        {
            throw new RuntimeException("scanning skills directory failed: "+e.getMessage());
        }
    }
    /** 拼成 system prompt 的目录列表：- name: description */
    public String catalog()
    {
        if(skills.isEmpty())
        {
            return "(no skills found)";
        }
        StringBuilder sb = new StringBuilder();
        for(Skill skill : skills.values())
        {
            sb.append("- ").append(skill.name())
                    .append(": ").append(skill.description())
                    .append('\n');
        }
        return sb.toString();
    }
    public String load(String name)
    {
        Skill skill = skills.get(name);
        if(skill!=null)
        {
            return skill.content();
        }
        String available = skills.isEmpty() ? "none" : String.join(", ", skills.keySet());
        return "Error: Unknown skill '" + name + "'. Available: " + available;
    }

    /**
     * 用来解析markdown内容的中间格式
     * @param meta 用于存放 name、description 键值对
     * @param body 存放 skill 正文内容
     */
    private record Parsed(Map<String, String> meta, String body){}
    private Parsed parse(String text)
    {
        if(!text.startsWith("---"))//不符合skill格式，返回空内容
        {
            return new Parsed(Map.of(),text);
        }
        String[] lines = text.split("\\r?\\n", -1);//把文本每一行分割
        int end = -1;
        for(int i = 1;i< lines.length;i++)
        {
            if("---".equals(lines[i].trim()))
            {
                end = i;
                break;
            }
        }
        if(end == -1)
        {
            return new Parsed(Map.of(),text);
        }

        Map<String, String> meta = new LinkedHashMap<>();//确保后面遍历转为Skill的顺序
        for(int i = 1;i<end;i++)
        {
            String line = lines[i].trim();
            int colon = line.indexOf(':');//获取冒号的下标
            if(line.isEmpty()||line.startsWith("#")||colon == -1)
            {
                continue;
            }
            String key = line.substring(0,colon).trim();
            String value = unquote(line.substring(colon+1).trim());
            meta.put(key,value);
        }
        StringBuilder body = new StringBuilder();
        for(int i = end+1;i<lines.length;i++)
        {
            body.append(lines[i]).append('\n');
        }
        return new Parsed(meta,body.toString().strip());
    }
    private String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }
    private String firstLine(String body) {
        if (body.isEmpty()) {
            return "";
        }
        int newline = body.indexOf('\n');
        String first = newline == -1 ? body : body.substring(0, newline);
        return first.replaceFirst("^#+\\s*", "").trim();
    }

}
