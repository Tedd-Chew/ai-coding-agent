package org.example.agent;

/**
 * 一个队友的「计划闸门」——他在【当前这个任务】上的写权限通行证。
 *
 * 【它是什么】不是一条消息，是一个活的开关。三方读写它：
 *   - SubmitPlanTool 写：提交计划 → awaiting = true
 *   - ReviewPlanTool 写：Lead 批准/驳回 → approved
 *   - GatedTool      读：每次 bash / write_file / edit_file 前问一句
 *
 * 【为什么必须是「状态」而不是「回一条消息」】
 * 如果 submit_plan 只是返回一句 "Plan approved."，那这句话只活在【对话历史】里。
 * 模型下一轮看到它，于是去 write_file —— 但没有任何东西拦着它。
 * 模型记错了、或者顺手又写一个文件，没人管。
 * 做成外部开关之后，每次写操作都要查一遍：**模型说什么不算数，开关说了算**。
 *
 * 【为什么原书那套 requestId / work_version 我们一个都不要】
 * 原书有两张表：pending_requests（Lead 侧，按 request_id 索引）和 plan_gates（队友侧，
 * 按名字索引）。真正拦人的是后者，而后者【只有收到响应消息时才会更新】。所以它必须
 * 用 request_id 配对、用 work_version 防顺延——那都是在解决「两张表靠消息同步」的麻烦。
 *
 * 我们只有一张表（就是这个对象），两边直接读写同一个，没有同步就没有配对：
 *   - request_id  → 不需要，Lead 按队友名就能找到这个对象
 *   - work_version → 不需要，等审批时队友换不了任务（见下），这个时序不存在
 *   - required / pending / rejected 三状态 → 不需要，它们对「拦不拦」是同一个结果，
 *     只差一句报错文案；一个 awaiting boolean 就够了
 *
 * 【为什么等审批时换不了任务】三处一起钉死的：
 *   ① 写操作被 GatedTool 拦    ② complete_task 也拦（任务完不成，assignment 不释放）
 *   ③ claim_next_task 有 assignment 就返回 None（抢不到新的）
 * 所以 bind() 只会在「干完一个任务、又抢到下一个」时被调一次。
 */
public class PlanGate {

    /** spawn 时定的，之后不变。false = 这个队友不用审批，开局就放行 */
    private final boolean requirePlan;

    /** 写操作通不通 */
    private volatile boolean approved;

    /** 有没有人交了计划、正在等审批 */
    private volatile boolean awaiting;

    public PlanGate(boolean requirePlan) {
        this.requirePlan = requirePlan;
        this.approved = !requirePlan;
    }

    // ==================== ① 读 ====================

    /** GatedTool 每次执行写操作前问这一句 */
    public boolean allowsWrites() {
        return approved;
    }

    /** 有没有人交了计划在等审批。Teammate.report() 用它判断"这段活算不算干完" */
    public boolean awaiting() {
        return awaiting;
    }

    // ==================== ② 换任务 ====================

    /**
     * 队友抢到任务时调。通行证归零——**这就是「干完一个任务要重新申请」的全部实现**。
     *
     * 不存 taskId：不需要一个 id 来事后「检测」换没换任务。
     * 换任务这个事件是有人的（队友自己知道），在那个时刻直接重置就行，那是当场处理。
     */
    public synchronized void bind() {
        approved = !requirePlan;
        awaiting = false;
    }

    // ==================== ③ 队友提交计划 ====================

    /**
     * @return false = 已经有一条在等审批了，别重复交
     */
    public synchronized boolean submit() {
        if (awaiting) {
            return false;
        }
        awaiting = true;
        return true;
    }

    // ==================== ④ Lead 审批 ====================

    /**
     * @return false = 没人在申请，忽略。
     *         这一条防的是 Lead【幻觉调用】review_plan（它没看到任何申请，但模型就是调了）——
     *         不校验的话，队友没交计划就拿到了写权限。
     */
    public synchronized boolean decide(boolean ok) {
        if (!awaiting) {
            return false;
        }
        approved = ok;
        awaiting = false;
        return true;
    }
}
