package org.example.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.agent.SkillLoader;
import org.example.agent.Tool;

import java.util.Map;

/**
 * 按名称加载 skill 的完整 SKILL.md 内容。
 * 目录（catalog）只在 system prompt 里，完整内容靠本工具按需读取。
 */
public class LoadSkillTool implements Tool {

    @Override
    public String name() {
        return "load_skill";
    }

    @Override
    public String description() {
        return "按名称加载某个 skill 的完整 SKILL.md 内容。"
            + "当某个 skill 适用当前任务时，先调用本工具读取完整说明。";
    }

    @Override
    public ObjectNode parametersSchema(ObjectMapper mapper) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = schema.putObject("properties");
        ObjectNode name = properties.putObject("name");
        name.put("type", "string");
        name.put("description", "skill 名称（来自 system prompt 里的目录）");

        schema.putArray("required").add("name");
        return schema;
    }

    @Override
    public String execute(String input) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> args = mapper.readValue(input, Map.class);
            String name = (String) args.get("name");
            return SkillLoader.getInstance().load(name);
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
}
