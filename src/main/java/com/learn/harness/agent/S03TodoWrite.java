package com.learn.harness.agent;

/**
 * 【第三章】计划管理（Todo / Session Planning）
 *
 * 本章在 S02 的基础上增加一个能力：让模型管理自己的工作计划。
 *
 * 为什么需要计划？
 * 当任务比较复杂（比如"重构这个模块"），模型需要把大任务拆成小步骤，
 * 然后一步步执行。如果没有显式的计划，模型容易忘记自己做到哪了。
 *
 * 实现方式：
 * 1. 提供一个 "todo" 工具，模型可以随时更新自己的计划
 * 2. TodoManager 管理计划项（状态流转：pending → in_progress → completed）
 * 3. 如果模型连续几轮没更新计划，自动提醒它刷新计划
 *
 * 核心教学点：
 * - 计划不是给人看的，是给模型自己用的（让它保持方向感）
 * - 一次只能有一个任务处于 in_progress 状态（强制聚焦）
 * - 提醒机制防止模型"忘记"更新计划
 *
 * 运行方式：
 *   export DASHSCOPE_API_KEY="your-key"
 *   mvn compile exec:java -Dexec.mainClass="com.learn.harness.agent.S03TodoWrite"
 */

import com.google.gson.*;
import java.io.*;
import java.util.*;

public class S03TodoWrite {

    // ==================== 配置 ====================

    private static final DashScopeClient client = new DashScopeClient();

    /** 如果模型连续这么多轮没更新计划，就提醒它 */
    private static final int PLAN_REMINDER_INTERVAL = 3;

    private static final String SYSTEM_PROMPT =
            "你是一个编程助手，工作目录是 " + CommonTools.WORKDIR + "。\n" +
            "对于多步骤任务，使用 todo 工具管理你的计划。\n" +
            "保持恰好一个步骤处于 in_progress 状态。\n" +
            "随着工作推进，及时更新计划状态。优先使用工具，少说废话。";

    // ==================== 计划管理器（TodoManager） ====================

    /**
     * 计划项：代表一个待办事项
     * <p>
     * 为什么用枚举表示状态？
     * 因为状态是有限的（只有三种），枚举比字符串更安全，IDE 能帮你检查拼写。
     */
    enum TodoStatus {
        PENDING("pending", "[ ]"),
        IN_PROGRESS("in_progress", "[>]"),
        COMPLETED("completed", "[x]");

        final String value;   // API 中使用的字符串值
        final String marker;  // 显示时的标记符号

        TodoStatus(String value, String marker) {
            this.value = value;
            this.marker = marker;
        }

        /** 从字符串解析状态（忽略大小写） */
        static TodoStatus fromString(String s) {
            for (TodoStatus status : values()) {
                if (status.value.equalsIgnoreCase(s)) return status;
            }
            throw new IllegalArgumentException("无效状态: " + s + "（允许: pending/in_progress/completed）");
        }
    }

    /** 单个计划项 */
    static class TodoItem {
        String content;      // 任务 内容
        TodoStatus status;   // 当前 状态
        String activeForm;   // 进行中时的描述（可选）

        TodoItem(String content, TodoStatus status, String activeForm) {
            this.content = content;
            this.status = status;
            this.activeForm = activeForm;
        }
    }

    /**
     * 计划管理器：维护计划列表 + 提醒逻辑
     * <p>
     * 为什么单独做一个类？
     * 因为计划管理有自己的规则（最多 12 项、只能有一个 in_progress），
     * 把这些规则封装在一个类里，比散落在循环中更清晰。
     */
    static class TodoManager {
        private List<TodoItem> items = new ArrayList<>();
        private int roundsSinceUpdate = 0;

        /**
         * 更新计划（模型调用 todo 工具时触发）
         * <p>
         * 规则：
         * 1. 最多 12 项（防止计划过于庞大失去聚焦作用）
         * 2. 同时只能有一个 in_progress（强制一次做一件事）
         */
        String update(JsonArray itemsArray) {
            if (itemsArray.size() > 12) {
                throw new IllegalArgumentException("计划最多 12 项（保持简洁）");
            }

            List<TodoItem> newItems = new ArrayList<>();
            int inProgressCount = 0;

            for (int i = 0; i < itemsArray.size(); i++) {
                JsonObject raw = itemsArray.get(i).getAsJsonObject();

                String content = raw.has("content") ? raw.get("content").getAsString().trim() : "";
                if (content.isEmpty()) {
                    throw new IllegalArgumentException("第 " + i + " 项缺少 content");
                }

                String statusStr = raw.has("status") ? raw.get("status").getAsString() : "pending";
                TodoStatus status = TodoStatus.fromString(statusStr);

                String activeForm = raw.has("activeForm") ? raw.get("activeForm").getAsString().trim() : "";

                if (status == TodoStatus.IN_PROGRESS) inProgressCount++;

                newItems.add(new TodoItem(content, status, activeForm));
            }

            if (inProgressCount > 1) {
                throw new IllegalArgumentException("同时只能有一个任务处于 in_progress 状态（强制聚焦）");
            }

            this.items = newItems;
            this.roundsSinceUpdate = 0;
            return render();
        }

        /** 记录一轮没有更新计划 */
        void noteRound() {
            roundsSinceUpdate++;
        }

        /**
         * 检查是否需要提醒模型更新计划
         *
         * @return 提醒文本，不需要提醒则返回 null
         */
        String getReminder() {
            if (items.isEmpty()) return null;
            if (roundsSinceUpdate < PLAN_REMINDER_INTERVAL) return null;
            return "[系统提醒] 你已经 " + roundsSinceUpdate + " 轮没有更新计划了，请刷新 todo 状态。";
        }

        /** 渲染计划为可读文本 */
        String render() {
            if (items.isEmpty()) return "（暂无计划）";
            StringBuilder sb = new StringBuilder("当前计划:\n");
            for (TodoItem item : items) {
                sb.append(item.status.marker).append(" ").append(item.content);
                if (item.status == TodoStatus.IN_PROGRESS && !item.activeForm.isEmpty()) {
                    sb.append(" (").append(item.activeForm).append(")");
                }
                sb.append("\n");
            }
            long completed = items.stream().filter(i -> i.status == TodoStatus.COMPLETED).count();
            sb.append("\n进度: ").append(completed).append("/").append(items.size());
            return sb.toString();
        }
    }

    private static final TodoManager todoManager = new TodoManager();

    // ==================== 工具定义 ====================

    /**
     * 构建工具列表：基础工具 + todo 工具
     */
    private static List<Map<String, Object>> buildTools() {
        List<Map<String, Object>> tools = new ArrayList<>(CommonTools.allBasicToolDefs());
        // 添加 todo 工具
        tools.add(DashScopeClient.toolDefinition("todo",
                "更新当前工作计划（用于多步骤任务的进度管理）",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "items", Map.of(
                                        "type", "array",
                                        "description", "计划项列表，每项包含 content(内容) 和 status(pending/in_progress/completed)",
                                        "items", Map.of("type", "object")
                                )
                        ),
                        "required", List.of("items")
                )));
        return tools;
    }

    private static final List<Map<String, Object>> TOOLS = buildTools();

    // ==================== Agent 循环（增强版） ====================

    /**
     * 工具分发：在 CommonTools 基础上增加 todo 工具
     */
    private static String dispatchTool(String name, JsonObject arguments) {
        if ("todo".equals(name)) {
            return todoManager.update(arguments.getAsJsonArray("items"));
        }
        return CommonTools.dispatch(name, arguments);
    }

    /**
     * Agent 循环
     * <p>
     * 和 S02 的区别：
     * 1. 多了 todo 工具的分发
     * 2. 每轮结束后检查是否需要提醒更新计划
     */
    private static void agentLoop(List<Map<String, Object>> messages) {
        int maxTurns = 30;
        for (int turn = 0; turn < maxTurns; turn++) {
            JsonObject response = client.createMessage(SYSTEM_PROMPT, messages, TOOLS, 4096);
            messages.add(DashScopeClient.assistantMessageToMap(response));

            if (!DashScopeClient.hasToolCalls(response)) {
                return;
            }

            // 执行工具
            boolean usedTodo = false;
            JsonArray toolCalls = DashScopeClient.getToolCalls(response);
            for (int i = 0; i < toolCalls.size(); i++) {
                JsonObject toolCall = toolCalls.get(i).getAsJsonObject();
                String toolName = DashScopeClient.getToolName(toolCall);
                JsonObject arguments = DashScopeClient.getToolArguments(toolCall);
                String toolCallId = DashScopeClient.getToolCallId(toolCall);

                String result;
                try {
                    result = dispatchTool(toolName, arguments);
                } catch (Exception e) {
                    result = "错误: " + e.getMessage();
                }

                System.out.println("\033[33m[" + toolName + "] " +
                        result.substring(0, Math.min(200, result.length())) + "\033[0m");
                messages.add(DashScopeClient.toolResultMessage(toolCallId, result));

                if ("todo".equals(toolName)) usedTodo = true;
            }

            // 计划提醒逻辑
            if (!usedTodo) {
                todoManager.noteRound();
                String reminder = todoManager.getReminder();
                if (reminder != null) {
                    // 把提醒作为系统信息注入（用 user 消息模拟）
                    System.out.println("\033[35m" + reminder + "\033[0m");
                    messages.add(DashScopeClient.userMessage(reminder));
                }
            }
        }
        System.out.println("[警告] 达到最大轮次限制");
    }

    // ==================== REPL ====================

    public static void main(String[] args) throws Exception {
        List<Map<String, Object>> history = new ArrayList<>();
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));

        System.out.println("=== S03 Todo / 计划管理 演示 ===");
        System.out.println("给 Agent 一个多步骤任务，观察它如何管理计划");
        System.out.println("输入 q 退出\n");

        while (true) {
            System.out.print("\033[36m[S03] >>> \033[0m");
            String input = reader.readLine();
            if (input == null || input.isBlank() ||
                    "q".equalsIgnoreCase(input.trim()) || "exit".equalsIgnoreCase(input.trim())) {
                break;
            }

            history.add(DashScopeClient.userMessage(input));
            agentLoop(history);
            System.out.println();
        }
    }
}
