package com.learn.harness.agent;

/**
 * 【第四章】子 Agent（Subagent）
 *
 * 本章教的是 Agent 架构中一个重要的模式：任务委派。
 *
 * 问题背景：
 * 当一个任务很复杂时（比如"分析整个项目结构"），如果在主对话中处理，
 * 中间产生的大量工具调用和输出会污染主对话的上下文，让后续对话变得很慢甚至报错。
 *
 * 解决方案：子 Agent
 * - 创建一个全新的对话（空消息列表），给它一个具体小任务
 * - 子 Agent 独立工作，共享文件系统但不共享对话历史
 * - 工作完成后只把摘要返回给父 Agent
 *
 * 这就像"派一个助手去调查某件事，回来给你一份报告"。
 *
 * 核心教学点：
 * - 上下文隔离：新消息列表 = 干净的上下文，不会被父对话干扰
 * - 信息浓缩：子 Agent 做了 10 步操作，但只返回一段摘要
 * - 复用：子 Agent 可以用相同的工具集，不需要额外配置
 *
 * 运行方式：
 *   export DASHSCOPE_API_KEY="your-key"
 *   mvn compile exec:java -Dexec.mainClass="com.learn.harness.agent.S04Subagent"
 */

import com.google.gson.*;
import java.io.*;
import java.util.*;

public class S04Subagent {

    // ==================== 配置 ====================

    private static final DashScopeClient client = new DashScopeClient();

    /** 父 Agent 的系统提示词：知道自己可以委派任务 */
    private static final String PARENT_SYSTEM =
            "你是一个编程助手，工作目录是 " + CommonTools.WORKDIR + "。\n" +
            "对于需要深入探索的子任务，使用 task 工具委派给子 Agent。\n" +
            "子 Agent 会独立完成任务并返回摘要给你。";

    /** 子 Agent 的系统提示词：知道自己是被委派的，完成后要总结 */
    private static final String CHILD_SYSTEM =
            "你是一个编程子助手，工作目录是 " + CommonTools.WORKDIR + "。\n" +
            "完成给定的任务，然后用一段话总结你的发现和操作结果。";

    /** 子 Agent 的最大循环轮次（防止子任务无限运行） */
    private static final int MAX_CHILD_TURNS = 15;

    // ==================== 子 Agent 逻辑 ====================

    /**
     * 运行子 Agent
     * <p>
     * 核心思想：
     * 1. 创建一个全新的消息列表（上下文隔离的关键）
     * 2. 跑一个标准的 Agent 循环（和父 Agent 结构一样）
     * 3. 循环结束后提取最终文本作为"报告"返回给父 Agent
     *
     * @param prompt 委派给子 Agent 的任务描述
     * @return 子 Agent 的最终回答（摘要）
     */
    private static String runSubagent(String prompt) {
        // 关键：全新的消息列表 → 子 Agent 看不到父 Agent 的对话历史
        List<Map<String, Object>> childMessages = new ArrayList<>();
        childMessages.add(DashScopeClient.userMessage(prompt));

        // 子 Agent 只能用基础工具（不能再委派子任务，防止递归爆炸）
        List<Map<String, Object>> childTools = CommonTools.allBasicToolDefs();

        for (int turn = 0; turn < MAX_CHILD_TURNS; turn++) {
            JsonObject response = client.createMessage(CHILD_SYSTEM, childMessages, childTools, 4096);
            childMessages.add(DashScopeClient.assistantMessageToMap(response));

            // 子 Agent 说完了 → 提取摘要返回
            if (!DashScopeClient.hasToolCalls(response)) {
                String summary = DashScopeClient.extractText(response);
                return summary.isEmpty() ? "（子 Agent 未返回摘要）" : summary;
            }

            // 执行工具（复用 CommonTools.dispatch）
            JsonArray toolCalls = DashScopeClient.getToolCalls(response);
            for (int i = 0; i < toolCalls.size(); i++) {
                JsonObject toolCall = toolCalls.get(i).getAsJsonObject();
                String toolName = DashScopeClient.getToolName(toolCall);
                JsonObject arguments = DashScopeClient.getToolArguments(toolCall);
                String toolCallId = DashScopeClient.getToolCallId(toolCall);

                String result = CommonTools.dispatch(toolName, arguments);
                System.out.println("  \033[90m[子Agent/" + toolName + "] " +
                        result.substring(0, Math.min(100, result.length())) + "\033[0m");

                childMessages.add(DashScopeClient.toolResultMessage(toolCallId, result));
            }
        }
        return "（子 Agent 达到最大轮次，强制结束）";
    }

    // ==================== 父 Agent 工具定义 ====================

    /**
     * 父 Agent 的工具列表：基础工具 + task 委派工具
     */
    private static List<Map<String, Object>> buildParentTools() {
        List<Map<String, Object>> tools = new ArrayList<>(CommonTools.allBasicToolDefs());
        // task 工具：让模型能够委派子任务
        tools.add(DashScopeClient.toolDefinition("task",
                "委派子任务给一个独立的子 Agent（它有自己的上下文，共享文件系统，完成后返回摘要）",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "prompt", Map.of("type", "string", "description", "给子 Agent 的具体任务描述"),
                                "description", Map.of("type", "string", "description", "简短描述这个子任务是什么（用于日志）")
                        ),
                        "required", List.of("prompt")
                )));
        return tools;
    }

    private static final List<Map<String, Object>> PARENT_TOOLS = buildParentTools();

    // ==================== 父 Agent 循环 ====================

    /**
     * 父 Agent 的分发逻辑：遇到 task 工具就启动子 Agent
     */
    private static String dispatchParentTool(String name, JsonObject arguments) {
        if ("task".equals(name)) {
            String prompt = arguments.get("prompt").getAsString();
            String description = arguments.has("description")
                    ? arguments.get("description").getAsString() : "子任务";
            System.out.println("\033[35m[委派] " + description + "\033[0m");
            return runSubagent(prompt);
        }
        return CommonTools.dispatch(name, arguments);
    }

    /**
     * 父 Agent 循环
     */
    private static void agentLoop(List<Map<String, Object>> messages) {
        int maxTurns = 20;
        for (int turn = 0; turn < maxTurns; turn++) {
            JsonObject response = client.createMessage(PARENT_SYSTEM, messages, PARENT_TOOLS, 4096);
            messages.add(DashScopeClient.assistantMessageToMap(response));

            if (!DashScopeClient.hasToolCalls(response)) {
                return;
            }

            JsonArray toolCalls = DashScopeClient.getToolCalls(response);
            for (int i = 0; i < toolCalls.size(); i++) {
                JsonObject toolCall = toolCalls.get(i).getAsJsonObject();
                String toolName = DashScopeClient.getToolName(toolCall);
                JsonObject arguments = DashScopeClient.getToolArguments(toolCall);
                String toolCallId = DashScopeClient.getToolCallId(toolCall);

                String result;
                try {
                    result = dispatchParentTool(toolName, arguments);
                } catch (Exception e) {
                    result = "错误: " + e.getMessage();
                }

                System.out.println("\033[33m[" + toolName + "] " +
                        result.substring(0, Math.min(200, result.length())) + "\033[0m");
                messages.add(DashScopeClient.toolResultMessage(toolCallId, result));
            }
        }
        System.out.println("[警告] 达到最大轮次限制");
    }

    // ==================== REPL ====================

    public static void main(String[] args) throws Exception {
        List<Map<String, Object>> history = new ArrayList<>();
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));

        System.out.println("=== S04 Subagent 演示 ===");
        System.out.println("试试给 Agent 一个需要深入探索的任务，它会委派子 Agent 去处理");
        System.out.println("例如: \"分析 src 目录的代码结构，告诉我有哪些模块\"");
        System.out.println("输入 q 退出\n");

        while (true) {
            System.out.print("\033[36m[S04] >>> \033[0m");
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
