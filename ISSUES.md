# 已知不足

各板块当前的缺口与隐患。每条给「问题 → 影响 → 位置」。
**带 ✅ 的是已经修掉的**，留着不删——出错的原因比结论更值得记。
更新日期：2026-10-13

---

## 一、Agent 主循环 / 权限 / Hooks

| # | 问题 | 影响 | 位置 |
|---|------|------|------|
| 1 | `write_file` / `edit_file` 无条件弹窗审批 | 连续写多个文件时每步都要确认一次，很吵。s13 起**队友也走这一条**，会更频繁——用户已明确"正常弹"，先忍着 | `Permission.java` RULES 最后一条 |
| ✅ 2 | 主循环无异常兜底 | **已修**——2026-10-14 真炸了一次。循环体包了 `catch (RuntimeException)`。**但光兜还不够**：兜住之后 history 里可能留着"assistant 带 tool_calls、缺 tool_result"的半成品，下一轮照样 400，于是变成每轮崩一次刷屏——所以 `Agent` 的工具执行也补了兜底，保证每个 tool_call 一定有 result | `Main.java` 的 `while (true)` / `Agent.runAgent` |

> **二-2 事故复盘（2026-10-14，跑三队友 kanban 那次）**
>
> **现象**：窗口还能打字，但发消息没人理；队友全卡着；Lead 的 `[wake:]` 只出现过 2 次，
> 之后 7 条团队消息一条都没被处理。
>
> **根因**：`MemoryExtractor` 的池是 `ThreadPools.create("memory", 1, 1)` ——
> **1 线程 + 容量 1 的队列，满了走 `AbortPolicy` 直接抛**。
> 而 Lead 处理团队事件那条路径**没有 `awaitPending()`**，是直接提交的；
> 抽取本身又要两次 LLM 调用（十几秒）。
> 三个队友密集汇报 → 连调几次 `extractAsync` → 池撑爆 →
> `RejectedExecutionException` 没人接，一路抛穿 `while`。
>
> **为什么看起来像"卡死"而不是"崩了"**：`main` 死了，但 **Swing 的 EDT 是非 daemon**，
> JVM 被它撑着不退——窗口还活着、还能打字，只是再也没人消费那条输入队列。
>
> **为什么排查了半天**：`System.err` **没被改道**到窗口（只改了 `System.out`），
> 异常堆栈全打在启动它的那个终端里，窗口里一个字都没有。三处一起修：
> ① `extractAsync` 上一次没跑完就跳过（池再也不会满）
> ② 主循环兜 `RuntimeException`　③ `System.err` 也接进窗口
| 3 | Hook 参数是 `Object...` 变参 | 全靠调用约定，类型错要到运行时才发现 | `HookRegistry.trigger` |
| 4 | 全程 `System.out.println` | 对话输出和调试信息混在一起，没有级别区分。**部分缓解**：`UiStream` 把 `System.out` 按行分流到窗口，全项目那几十处一行没改；但底层仍是 println，级别 / 来源 / 结构一概没有 | 全局 |
| 5 | `LARGE_OUTPUT_WARN_CHARS = 100_000` | 抬得过高，实际几乎不会触发，形同没有 | `HookConfig.java:14` |
| 6 | hook 回调拿不到调用者身份 | 任何需要区分主 / 子 agent 的横切逻辑都挂不上去（如记忆抽取）。强行塞就得再加一个身份参数，等于把一种耦合换成另一种 | `HookRegistry.trigger` |
| 7 | 事件集只有 4 个，没有"每次调 LLM 前"的位置 | 压缩、todo reminder 这类循环内机制无法 hook 化，`Agent` 只能硬编码知道它们存在——与 `HookConfig` 注释里"Agent 本身不感知"的约定相悖 | `HookRegistry` / `Agent.runAgent` |
| ✅ 8 | 主循环只读一行输入 | **已修**——交互层换成 Swing 窗口：输入框天然多行（回车换行、Ctrl+Enter 或按钮提交），内容先在框里攒着，点"发送"才整段交给程序。主循环不再读 `System.in`（改成 `inputQueue.poll(250ms)`），粘多行不会再被 `Scanner` 缓冲吃掉。**顺带解掉了 s13 那个死结**——现在没有任何线程读 stdin，"队友要提问就得跟主循环抢"根本不存在了 | `Main.java` / `ui/ChatWindow` |
| ✅ 9 | Permission 拒绝时零反馈 | **已修**——拒绝时打印 `[permission] denied: <toolName>`。顺带补了 `System.out.flush()`：`print` 不触发 autoFlush，那行 `Allow? [y/N]` 提示会拖到后面某次 println 才出现 | `Permission.java` |

> **判据**：hook 只适合**对调用者无差别**的横切关注点（权限、日志、告警、统计——四个默认 hook 没有一个需要知道是谁在调）。
> 回调一旦需要知道调用者身份、或需要循环内的位置，就不该挂 hook。
> 参考：原项目 17 章下来事件集从未扩过，s15（3291 行的集成 harness）也是同样 4 个。

---

## 二、任务系统 / Subagent

> `TodoWrite` / `TodoManager` 已在 s10 移除，本板块随之重写。

| # | 问题 | 影响 | 位置 |
|---|------|------|------|
| 1 | subagent 轮数上限 50 写死 | 不可配置，长任务中途被截断 | `TaskTool.java:77` |
| 2 | subagent 只能回传最终文本 | 中间的探索过程全部丢弃，父 agent 无法复用 | `TaskTool.execute` |
| 3 | subagent 不产生记忆 | 记忆写入由 `Main` 驱动，子 agent 的发现永远进不了记忆库 | 架构层面 |
| 4 | subagent 拿不到父级专用工具（6 个任务 + 3 个定时） | `PARENT_ONLY_TOOLS` 一刀切。有意为之（防状态污染）。**s13 正在放开**：改成 `Scope` 白名单（`LEAD` / `SUBAGENT` / `TEAMMATE`），`claim_task` / `complete_task` 会同时给 `LEAD` 和 `TEAMMATE`——黑名单表达不了"两个角色都要"，这就是必须换白名单的原因 | `ToolManager.java` |
| 5 | `create_task` 只增不减 | 一个任务一个 json，做完文件仍留着，`.tasks/` 只涨不消 | `TaskStore` |
| 6 | 孤立已完成任务无回收 | 见下方，**已决定暂缓** | `TaskStore` |
| ✅ 7 | `TaskStore` 无任何并发保护 | **已修**——`claim` / `complete` / `updateDependencies` 都加了 `ReentrantLock`（这三条都是"读出来 → 校验 → 写回去"，中间没有原子性就会双双通过校验、双双落盘）。`load` / `listAll` 不加（只读，最坏读到略旧的数据）；`create` 不加（用 `CREATE_NEW` 排他创建，撞名会重试） | `TaskStore` |

> **已完成任务的回收（六号）——决定暂缓，三条理由：**
>
> 1. **原项目没有**。`s_full.py:304` 只有手动的 `deleted` 状态（硬 `unlink`），没有自动 GC。这是扩展，不是补课。
> 2. **现在不痛**。`.tasks/` 就几个文件；要解决的"已完成任务占着列表"用**视图过滤**（`list_tasks` 默认隐藏已完成）就够，而且可逆。
> 3. **s13 会改写前提**。原项目在 complete 时**主动把自己从所有人的 `blockedBy` 里摘掉**（`s_full.py:298-303`），所以它「已完成 ⇒ 无人引用」自动成立；我们保留完整历史、读取时懒判定状态，GC 规则要重新推。再加上并发，`complete()` 里做级联扫描会变成竞态。
>
> 真要做，规则取 **(a) 已完成 ∧ 没有任何任务的 `blockedBy` 提到它**，级联回收，触发点放 `complete()`，结果和 `unlocked` 一起返回。
> 不要 (b)「没有*未完成*任务引用它」——那会留下悬空引用，还得回头清理别人的 `blockedBy`。

---

## 三、Skill

| # | 问题 | 影响 | 位置 |
|---|------|------|------|
| 1 | frontmatter 解析有两份等价实现 | 改一处忘一处 | `SkillLoader.java:136` 与 `FrontmatterParser.java` |
| 2 | `load_skill` 无去重 / 缓存 | 同一 skill 加载两次，全文在 history 里塞两份 | `LoadSkillTool` |
| 3 | frontmatter 只支持扁平 `key: value` | skill 无法声明参数、依赖、工具白名单等 | 两处解析器 |
| 4 | skill 目录启动时扫一次 | 运行期间新增 / 修改 skill 不生效 | `SkillLoader.scan()` |

---

## 四、上下文压缩（s08）

| # | 问题 | 影响 | 位置 |
|---|------|------|------|
| 1 | transcript 写的是**整个 history** | 每份归档都嵌套着上一次的归档，实测约 77% 重复 | `ContextCompactor.snipCompact` |
| 2 | 归档标记上的条数与文件实际内容对不上 | 模型按这个数去文件里找必然错位 | 同上 |
| 3 | 模型**从不读 transcript** | 实测：读 `.task_outputs` 748 次，读 `.transcripts` **0 次**——归档机制等于没启用 | — |
| 4 | `isArchiveMarker` 守卫已是死代码 | 它要求 `size == 101`，而触发线已抬到 `150` | `ContextCompactor.snipCompact` |
| 5 | ③.5 `fitToolResults` 无 unseen 过滤 | 会把模型还没读的结果也一并收掉 | 同上 |
| 6 | 无清理机制 | `.transcripts` 101 个 / 5.7M，`.task_outputs` 398 个 / 2.3M，只增不减 | — |
| 7 | 未实现 `reactive_compact` | API 报 `prompt_too_long` 时没有兜底重试 | — |
| 8 | 第 ④ 步总结有损 | 压缩后原始细节不可恢复 | `compactHistory` |
| 9 | `snipCompact` 不保留当前请求 | 单轮超过 96 条消息时，本轮的用户输入会落进被归档的中间段——**Agent 自己就看不到用户问的是什么了**。④ `compactHistory` 用 `summaryMessage(..., activeRequest, ...)` 保住了当前请求，② 只插一个 `[N messages archived at <path>]` | `ContextCompactor.snipCompact` |

---

## 五、记忆（s09）

| # | 问题 | 影响 | 位置 |
|---|------|------|------|
| 1 | 抽取输入混着 system / tool / todo 提醒 / 归档标记 | 不纯是对话，噪声很大 | `MemoryExtractor.dialogueText` |
| 2 | 截断保**头部** | 一条大工具结果就能挤掉后面真正的对话（恰好是最新的） | `MemoryExtractor.truncate` |
| 3 | 会话前几轮 system prompt 落在窗口内 | 记忆目录被重新抽成新记忆，形成「记忆 → prompt → 记忆」自反馈 | 同上 |
| 4 | 每轮都抽，窗口高度重叠 | 去重挡的其实是自己的上一轮 | `Main` 主循环 |
| 5 | 去重是字符串级 | 换个说法就绕过，语义重复挡不住 | `MemoryExtractor.shouldStore` |
| 6 | 改名 = 另存一条 | 同一事实起两个名字会并存且互相矛盾 | 同上 |
| 7 | 抽取侧只能新增 | 修正必须等合并触发（≥15 条），期间旧记录一直在 | 设计如此，靠 `consolidate` 兜 |
| 8 | 召回正文截断无提示 | `substring(0, remaining)` 从中间劈开，模型不知道后面还有 | `MemorySelector.recall` |
| 9 | 每轮额外 2 次 LLM 调用 | 一次选记忆、一次抽记忆，闲聊也要花 | `SystemPromptBuilder` / `Main` |
| ✅ 10 | 每轮抽取记忆**同步执行**，卡住主流程 | **已修**——`extractAsync(快照)` 丢后台，主线程立刻回到 `sc.nextLine()` | `MemoryExtractor` / `Main` |

> **当时列的三个约束，实现时都守住了：**
> 1. 等待点在 `runTurn` 之前（也就是 `SystemPromptBuilder.build()` 之前）——`consolidate` 是「先写后删」，
>    中途读 `.memory/` 会看到新旧混在一起的 7 个文件
> 2. 等待点放在 `exit` 判断之前，退出时顺带把最后一段记忆等完
> 3. 单线程池 `ThreadPools.create("memory", 1, 1)`，用 `Future` 持有；退出时 `shutdown()`
>
> **前提是 `Agent.getHistory()` 改成了返回快照**——后台线程读活的 `ArrayList` 会和下一轮对话撞车。
> 快照顺带把锁的范围收回到「只包 `runAgent`」（原来多包了打印和抽取）。
> 这也让「调度回合不抽记忆」变得自然：打印那件事不需要锁，`CronScheduler` 自己一行就够了，回调整个删掉。

| 11 | 抽取的输入是 `history`，而 history 正被压缩持续改写 | 见下方三条症状。**线程安全已解**（改读快照），但下面三条**语义**症状一条都没解 | `MemoryExtractor.dialogueText` |

> **改进方向（待做）· 抽取不该读 `history`**
>
> `history` 是被压缩**持续改写**的，而且抽取窗口在它上面**滑动**（每轮取末尾 12 条）。三个症状：
>
> 1. **窗口重叠**：相邻两轮重合 11 条 → 去重闸门挡的其实是**自己的上一轮**，不是别的会话
> 2. **被总结替换**：④ 触发后抽到的是摘要。而 `SUMMARY_SYSTEM_PROMPT` 只要求保留
>    「当前目标 / 已做的决定 / 涉及的文件 / 剩余工作 / 用户约束」——
>    **用户偏好和项目事实恰恰被丢掉了**，而那两类正是记忆最该抽的
> 3. **残渣入 prompt**：②③ 之后窗口里是 `[N messages archived at ...]`、
>    `[Earlier tool result saved at ...]`、`Full output: <path>` 这些记账文本，
>    可能被抽成记忆（临时措辞黑名单挡不住 `archived` 这类词）
>
> **改法**：完全不碰 `history`，只把「本轮用户输入 + 最终回复」交给抽取——
> `extract(String userInput, String assistantReply)`。一刀同时解掉三条，并且解掉上面第四节第 9 条
> （本轮输入不再需要从 history 里找）。
>
> **代价**：丢掉本轮中间的 assistant 消息。但用户需求在 `input`、结论在 `response`，
> 而中间那些「我要读一下 X 文件」本来就不该进记忆。
> 顺带把抽取 prompt 从 12 条缩到 2 条，**也缓解上面第 10 条那个阻塞**。

---

## 六、工程 / 项目整体

| # | 问题 | 影响 | 位置 |
|---|------|------|------|
| 1 | 测试覆盖仍偏少：58 个有效用例，集中在 `TaskStore` / `CronExpression` / `CronScheduler` / `BackgroundManager` / `BashTool` | `ContextCompactor` / `MemoryStore` / `Permission` 仍是零覆盖，靠手跑；另有 3 个 `@Disabled` 待修（2 个是测试自身缺陷，1 个等编码修好） | `src/test` |
| 2 | `KEEP_RECENT_MESSAGES` 定义了但无人引用 | 死常量，读代码时误导 | `Constants.java:48` |
| 3 | 根目录 `memory.md` 是旧 `Memory` 类的残留 | 五区块格式，已被 `.memory/` 取代，僵尸文件 | 项目根 |
| 4 | 根目录散落 `tmp_x.txt` / `todo.html` / `pomodoro.html` | agent 实验残留，无清理 | 项目根 |

---

## 七、后台任务（s11）

| # | 问题 | 影响 | 位置 |
|---|------|------|------|
| 1 | `destroyForcibly()` 只杀 `cmd.exe`，不杀它的子进程 | Windows 上杀父进程**不连带**子进程。`cmd /c npm install` 被"杀掉"之后 `node.exe` 还在跑；打了 `exit` 之后后台命令还活着 | `BashTool.runCommand` |
| 2 | 后台任务同样受 120 秒上限 | README 的动机写的是"装依赖、跑测试可能持续几分钟"，但超时是前后台共用的，后台也跑不过 2 分钟 | 同上 |
| 3 | 池线程是 daemon，退出时被直接杀掉 | 后台任务既没有"等它跑完"也没有"显式终止"的路径，进程一退就断 | `ThreadPools.create` |

> **三条是同一件事的三面：进程树的生命周期没人管。**
>
> 修 1 不必调 `taskkill`——JDK 9+ 的 `ProcessHandle` 就能拿子进程：
> ```java
> process.descendants().forEach(ProcessHandle::destroyForcibly);
> ```
> 和「退出时清理」一起做，别分两次。

---

## 八、编码 / 跨平台

> 由 agent 在 s11 测试中实测发现（`echo 你好世界` / `dir /b` / `mvn -v` 探针）。

| # | 问题 | 影响 | 位置 |
|---|------|------|------|
| 1 | 中文 Windows 的 `cmd` 输出是 GBK，代码却按 UTF-8 硬解 | **崩溃已修，乱码未修**。`Files.readString` 内部是 `newStringNoRepl`（no replacement），撞上非法字节抛 `MalformedInputException`，`getMessage()` 恰好是 `Input length = 1`，被 catch 吞掉——输出和 exit code 全丢。现改成直读字节 + `new String(..., UTF_8)`（REPLACE）：不崩了，但中文仍显示为 U+FFFD | `BashTool.runCommand` / `ReadFileTool` |
| 2 | `EditFileTool` 读非 UTF-8 文件会直接失败 | **故意保留**。这个工具会**写回**文件：一旦用 REPLACE 拿乱码去匹配 `old_text`、再按 UTF-8 写回，原文件的中文就被永久替换成 U+FFFD——而且模型不会察觉。宁可改不了 | `EditFileTool` |

> **改进方向（未做）**：让 `bash` / `read_file` / `edit_file` 共用**一个编码探测器**
> （严格试 UTF-8，失败退 GBK），读写同一个 charset，再加「解出来编不回去就拒绝」的往返校验。
> 一处实现三处收益；但**三个必须联动**——只改一个反而更危险，读到的乱码会被写回去。
> 更简单的备选：只让 bash 跑在 `chcp 65001` 下。

---

## 九、定时任务（s12）

| # | 问题 | 影响 | 位置 |
|---|------|------|------|
| 1 | 新建的任务在**当前分钟内**会立刻触发一次 | `lastFired` 初始为 null，下一次轮询（1 秒后）就匹配上了。所以"每分钟汇报"建完当场就跑，而不是等下一个整分——实测模型告诉用户"首条在下一个整分"，实际立刻跑了。原书行为一致 | `CronScheduler.schedule` |
| 2 | 用户输入可能被调度回合**插队** | 打字时主线程阻塞在 `sc.nextLine()`，**不持有 `turnLock`**；队列线程这时 `tryLock` 会抢到。用户回车后 `runTurn` 用的是阻塞 `lock()`，只能等那一轮跑完——实测被推迟一整轮 | `CronScheduler.deliverDue` / `Agent.runTurn` |
| 3 | 交付失败重投会在 history 里留下**重复的注入消息** | `tryRunTurn` 跑挂时 `restore()` 把任务退回队列、下次重投，但注入的 `[Scheduled] xxx` **已经加进 history 了**。原书用 `scheduled_start` 索引截断 history 来避免，我们简化掉了 | `CronScheduler.deliverDue` |
| 4 | 调度回合不抽记忆 | **设计决定，不是缺陷**——见下方理由 | `CronScheduler.deliverDue` |
| 5 | 单例没有对外清空的口子 | `scheduledJobs` 是私有字段，测试只能靠"建了就记下来、`@AfterEach` 逐个 cancel"来隔离 | `CronSchedulerTest` |

> **调度回合为什么不抽记忆（四号）：**
> 1. **内容重复**。`*/1` 的任务每分钟产出"⏱ 12:50:35"这种程序生成的文本，抽进库就是永久噪音
> 2. **无人值守地烧钱**。每轮抽取 + 可能的合并 = 2 次 LLM 调用；用户聊天有上限，定时任务是通宵跑
> 3. **不需要特殊处理**。消息留在 history 末尾（`extract` 取末尾 12 条），用户下次说话时会被顺带抽走，
>    而且那时候模型还多看到"用户怎么反应"，判断得更准
>
> **两条经确认不算问题：**
> - 进程退出后调度停止、不补跑停机期间错过的时间点。与原书一致（README 明写要守时得用系统 crontab）
> - 非交互线程的权限**失败关闭**：调度回合问不了用户（会跟主终端抢 `System.in`），所以"问不了 = 拒绝"。
>   方向绝不能反——反过来就是"把危险操作塞进 `schedule_cron` 的 prompt 即可免审批执行"的权限后门

---

## 十、多 agent（s13）

| # | 问题 | 影响 | 位置 |
|---|------|------|------|
| 1 | `BackgroundManager` 是全局单例，且 `collect()` **取走即移除** | 队友和 Lead 会**互相串台**，两个方向都会坏：<br>① 队友的 Agent 每轮开头都调 `collect()`，会把 **Lead 还没收走的通知吃掉**——Lead 永远等不到那些结果；<br>② 队友若用 `bash(run_in_background=true)`，任务注册进**同一张表**，Lead 下一轮会收到一条**它从没发起过的任务**的完成通知，然后拿着它去推理。<br>根因不是"谁有资格拿"，是它按"全局只有一台主循环"写的 | `BackgroundManager` / `Agent.chatWithNotifications` |
| 2 | plan gate 是 Lead 批的，用户插不上手 | Lead 也是模型。它批计划的时候**用户既看不到、也没机会否决**。所以"怕队友乱改项目文件"这件事 **plan gate 挡不住**——真正拦住的是 plan 批准之后、每次写操作弹的那一下。两层各有各的用处，别以为有 plan gate 就安全了 | `Teammate` / plan gate |

> **一号的处置：队友干脆不碰后台能力（已定，选 A）。**
>
> 队友的工具列表里放一个 `new BashTool(allowBackground = false)`——它的 `parametersSchema()`
> **不报** `run_in_background` 这个参数，模型根本不知道有这回事，也就起不了后台任务。
> 两条串台路径同时断掉。
>
> 理由：**队友自己就活在后台线程里**，不需要再往下铺一层。原书的队友也没有这个能力。
> 代价：队友跑长命令只能同步等，仍然受 `BashTool` 那 120 秒上限。
>
> **真要做（未做）**：`BackgroundManager` 从单例改成"每个 agent 一个"（像 history 那样），
> `BashTool` 构造时注入自己那个。但 `BashTool` 现在是 `new BashTool()` 无参构造、内部走 `getInstance()`，
> 改动要串到 `Main` 和工具注册那一层——为一个队友基本用不到的能力，不值。
>
> **别改成"只有 Lead 能拿后台结果"**：那个方向是错的。
> 该说的是"**每个 agent 拿自己的**"，不是"Lead 有特权"。

| ✅ 3 | daemon 线程的 `finally` 在进程退出时**不会执行** | **已修**（两个候选都落地了）。队友线程是 daemon，而 `Main` 最后是 `System.exit(0)`——**它只在【调用它的那条线程】上展开还没跑的 `finally`，别的线程的栈根本不动**。所以 `Teammate` 的 `handBackWork()` / `cleanupTempFiles()` 在"点 ×"以外的退出方式（任务管理器 / IDE 停止 / 崩溃 / 断电）里根本不会跑：任务永远卡在 `in_progress`，压缩落盘目录永远留在磁盘上。原书同样是 `daemon=True`，同样有这个问题 | `Teammate.run` 的 finally / `Main` |

> **三号怎么修的 —— 两条路一起上，谁也不是谁的替代。**
>
> **① 退出时清一次** —— `Main` 的 `finally` 里调 `ContextCompactor.cleanupAll()`。
> 和 `CronScheduler.stop()` / `memoryExtractor.shutdown()` 并列。
> 覆盖"点 × / 窗口里输 exit"这条路，好处是**立刻干净**，不用等下次启动。
> （清完之后队友线程可能又写回来一点——它们要等 `System.exit(0)` 才被收掉。无所谓，下次启动还会扫。）
>
> **② 启动时兜底（真正管用的那条）** —— `Main` 起来时两行：
> ```java
> TaskStore.getInstance().recoverAbandoned();   // in_progress 全部退回任务板
> ContextCompactor.cleanupAll();                // 清空两个压缩落盘目录
> ```
> **为什么它才是可靠的**：退出这件事有太多绕过方式——任务管理器结束任务、IDE 点停止、崩溃、断电，
> 那些情况下 `Main` 的 `finally` 根本不会跑。而"启动时收拾"是 100% 覆盖的。
>
> **为什么"全退 / 全删"是安全的**：进程刚起来，不可能有任何活跃队友，而且 `Agent` 的 history 不落盘——
> 所以那一刻磁盘上残留的一切**一定是上一轮的**，没有一个需要保留。
>
> **为什么不能用"退出时停干净"（`interrupt()` + `join()`）代替 ②**：
> 它只能保证"试图停"，不保证来得及——队友正卡在 `agent.runTurn`（一次 LLM 调用十几秒）时
> `interrupt` 对它无效，`join` 只能超时放弃。**花了复杂度换不来正确性。**
>
> **顺带**：`recoverAbandoned()` 走的是 `Task.released()`，它**状态和 owner 一起改**。
> 只改状态不清 owner 的话任务看着是 `pending`，但 `pickUpWork` 那边 `owner != null` 一样会跳过它——
> 比原来的 `in_progress` 还隐蔽。

---

## 优先处理

1. **压缩的 transcript 归档**（四-1/2/3）——机制本来就没人用，先决定是修还是砍
2. **记忆抽取的输入**（五-1/2/3）——`dialogueText` 改两个小地方能同时解掉三条
3. **补测试**（六-1）——`TaskStore`（9）/ `CronExpression`（20）/ `CronScheduler`（16）已覆盖；接着补 `ContextCompactor` / `MemoryStore` / `Permission`，它们的边界条件最多
4. **中文输出编码**（八-1）——目前只是"不崩"，中文仍是乱码，日常用 `dir` / `mvn` / `git status` 都会撞上
5. **主循环多行输入**（一-8）——触发频率低，但一旦撞上会静默毁掉整整一轮工具调用，且毫无提示

---

## 不算问题的

- `config.properties` 里的 API key：已被 `.gitignore` 排除，没进版本库
- subagent 关闭压缩：有意为之，它的 history 用完即弃
- 合并是唯一能修正记忆的途径：与原项目设计一致，抽取和修正是分开的
