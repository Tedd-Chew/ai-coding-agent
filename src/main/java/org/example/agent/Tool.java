package org.example.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;

public interface Tool {
    String name();
    String description();
    default ObjectNode parametersSchema(ObjectMapper mapper)//默认方法，无参tool
    {
        //字段: type
        //  是什么: 整个参数的结构类型
        //  例子: object（最常见）
        //  ────────────────────────────────────────
        //  字段: properties
        //  是什么: 工具需要哪些参数
        //  例子: {city: {type: string, description:
        //  "城市名"}}
        //  ────────────────────────────────────────
        //  字段: required
        //  是什么: 哪些参数必传
        //  例子: ["city"]（空 = 全部可选）p
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type","object");
        schema.putObject("properties");// 空ObjectNode = 序列化成 {}
        schema.putArray("required"); // 空ArrayNode = 序列化成 []
        return schema;//ObjectNode在序列化过程中是比Map更好用，更加严谨的数据结构
    }
    //执行的方法：输入字符串，输出字符串，因为用的是纯文字模型
    String execute(String input);
}
