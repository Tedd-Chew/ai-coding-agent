# AI Coding Agent

一个从零实现的 AI Agent 运行时（Java）。模型接收自然语言指令后自主规划并调用工具——读写文件、执行 shell 命令、维护任务清单——执行结果回灌模型继续推理。

**不依赖任何 Web / AI 框架**，纯 Java 手写 Agent 核心：ReAct 循环、工具协议、权限闸门、上下文压缩、多 Agent 协作。

## 功能

| 能力 | 说明 |
|---|---|
| **ReAct 主循环** | 模型决策 → 工具执行 → 结果回灌 → 再推理；支持单轮响应多个 `tool_calls`，按 `tool_call_id` 精确回填 |
| **可扩展工具层** | 抽象 `Tool` 接口 + JSON Schema 声明参数，新增工具只需"实现接口 + 注册一行" |
| **三级工具权限** | 硬拒绝清单 / 规则匹配（路径越界、命令链中的破坏性命令）/ 交互式审批 |
| **多 Agent 协作** | 队友线程 + 消息总线 + 共享任务板；队友空闲时自主认领任务，异常退出自动归还 |
| **上下文压缩** | 大体积工具结果转存磁盘只留路径引用；历史超限时摘要替换 |
| **长期记忆** | 会话结束抽取结构化记忆落盘，下次会话按相关度召回注入 |
| **后台任务与调度** | 自定义线程池承接后台调用；cron 表达式调度器，支持持久化任务 |
| **图形界面** | Swing 窗口：多行输入、日志区、权限确认弹窗 |

## 技术栈

Java 21 · Jackson · DeepSeek API（Function Calling）· JDK 原生 HttpClient · Swing

零框架依赖——没有 Spring，没有 LangChain。

## 快速开始

### 环境

JDK 21+ 、Maven

### 配置

在项目根目录创建 `config.properties`：

```properties
api.key=你的 API Key
api.url=https://api.deepseek.com/chat/completions
model=deepseek-chat
```

> API Key 从配置文件读取，不写死在代码里。

### 运行

```bash
mvn compile
mvn exec:java -Dexec.mainClass=org.example.Main
```

或在 IDE 中直接运行 `org.example.Main`。

## 项目结构

```
src/main/java/org/example/
├── Main.java                    入口：主循环、事件处理
├── agent/
│   ├── Agent.java               ReAct 引擎
│   ├── Tool.java                工具接口
│   ├── ToolManager.java         工具注册与按角色组装
│   ├── Permission.java          三级权限闸门
│   ├── HookRegistry.java        Hook 事件机制
│   ├── ContextCompactor.java    上下文压缩
│   ├── MemoryStore.java         ┐ 长期记忆
│   ├── MemoryExtractor.java     ┘
│   ├── TaskStore.java           任务系统（依赖图 + 持久化）
│   ├── CronScheduler.java       定时调度
│   ├── MessageBus.java          ┐
│   ├── Teammate.java            ├ 多 Agent 协作
│   ├── TeamRegistry.java        ┘
│   └── tools/                   内置工具实现
├── service/                     LLM 调用封装
├── entity/                      消息模型
└── ui/                          界面
```

## 设计说明

### 每个 tool_call 必须对应一个 tool_result

LLM API 要求二者一一对应。一旦某轮出现"assistant 带 tool_calls 但没有对应 result"的半成品消息，后续**每一轮请求都会 400 且无法自动恢复**。因此在工具执行外层做了兜底：不管工具内部发生什么，都要生成一条 result 回填。

### 非交互线程的权限失败关闭

定时任务线程无法弹出审批窗口。这种情况下**拒绝执行**而不是放行——否则把危险操作塞进定时任务的 prompt 就能绕过审批，权限系统形同虚设。

### 上下文落盘按 agent 分区

压缩产生的临时文件按 agent 名分目录，避免多个 agent 互相覆盖；启动和退出时统一清理，异常退出也不会留下残留。

### 线程池饱和的防御

记忆抽取等后台任务使用有界队列 + `AbortPolicy`。高频提交时曾出现 `RejectedExecutionException` 击穿主循环、导致界面可交互但无响应的问题，现通过"提交前检查 + 循环兜底 + 错误流重定向"三层防御处理。

## 关于本项目

设计参考了 [learn-claude-code](https://github.com/shareAI-lab/learn-claude-code)——一个从零实现 Claude Code 风格 agent harness 的教程。我将它从 Python 移植到 Java，并在实际运行中调整了若干设计（线程池饱和的处理、权限闸门的失败关闭策略、上下文落盘的目录隔离等）。
