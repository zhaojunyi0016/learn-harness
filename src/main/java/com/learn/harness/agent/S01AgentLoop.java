package com.learn.harness.agent;

/**
 * 【第一章】Agent 循环（Agent Loop）
 *
 * 这是整个教程系列的起点。本章教的是 AI Agent 最核心的运行模式：
 *
 *   用户输入 → 模型思考 → 需要工具？→ 执行工具 → 结果返回给模型 → 继续思考...
 *
 * 这个循环看起来简单，但它是所有复杂 Agent 的骨架。
 * 后续章节的所有能力（子 Agent、计划、记忆等）都是在这个循环上叠加的。
 *
 * 本章只用一个工具（bash），把循环本身讲清楚。
 *
 * 运行方式：
 *   export DASHSCOPE_API_KEY="your-key"
 *   mvn compile exec:java -Dexec.mainClass="com.learn.harness.agent.S01AgentLoop"
 */

import com.google.gson.*;
import java.io.*;
import java.util.*;

public class S01AgentLoop {

    // ==================== 配置 ====================

    /** 模型客户端（调用 DashScope API） */
    private static final DashScopeClient client = new DashScopeClient();

    /**
     * 系统提示词：告诉模型它是什么角色、能做什么
     * <p>
     * 为什么要有系统提示词？
     * 它是模型行为的"总纲"，没有它模型不知道自己该干什么。
     * 这里告诉模型：你是个编程助手，可以执行命令，先做事再汇报。
     */
    private static final String SYSTEM_PROMPT =
            "你是一个编程助手，工作目录是 " + CommonTools.WORKDIR + "。\n" +
            "使用 bash 工具来检查和修改工作区。先行动，再简洁汇报结果。";

    /**
     * 工具列表：本章只注册一个 bash 工具
     * <p>
     * 为什么只用一个工具？
     * 因为本章重点是循环机制本身，不是工具种类。
     * 一个 bash 就足以演示"模型请求工具→执行→返回结果"的完整流程。
     */
    private static final List<Map<String, Object>> TOOLS = List.of(
            CommonTools.bashToolDef()
    );

    // ==================== Agent 循环核心 ====================

    /**
     * 执行一轮对话（模型调用 + 可能的工具执行）
     * <p>
     * 返回 true 表示还需要继续循环（模型请求了工具调用），
     * 返回 false 表示循环结束（模型给出了最终回答）。
     *
     * 一轮的流程：
     * 1. 把当前对话历史发给模型
     * 2. 模型返回响应（可能是文本回答，也可能是工具调用请求）
     * 3. 把 assistant 的响应加入对话历史
     * 4. 如果模型不需要调工具 → 结束
     * 5. 如果模型请求调工具 → 执行 → 把结果加入对话历史 → 返回"继续"
     *
     * @param messages 对话历史（会被修改，新消息会追加进去）
     * @return true=继续循环，false=结束
     */
    private static boolean runOneTurn(List<Map<String, Object>> messages) {
        // 第一步：调用模型
        JsonObject response = client.createMessage(SYSTEM_PROMPT, messages, TOOLS, 4096);

        // 第二步：把 assistant 的回复加入历史
        // 为什么要加入历史？因为下一轮模型需要看到自己之前说了什么（包括工具调用请求）
        messages.add(DashScopeClient.assistantMessageToMap(response));

        // 第三步：判断模型是否要调用工具
        if (!DashScopeClient.hasToolCalls(response)) {
            // 模型直接给出了文本回答，循环结束
            return false;
        }

        // 第四步：执行模型请求的工具调用
        JsonArray toolCalls = DashScopeClient.getToolCalls(response);
        for (int i = 0; i < toolCalls.size(); i++) {
            JsonObject toolCall = toolCalls.get(i).getAsJsonObject();

            String toolName = DashScopeClient.getToolName(toolCall);
            JsonObject arguments = DashScopeClient.getToolArguments(toolCall);
            String toolCallId = DashScopeClient.getToolCallId(toolCall);

            // 执行工具（这里所有工具都通过 CommonTools.dispatch 分发）
            String result = CommonTools.dispatch(toolName, arguments);

            // 打印工具执行信息（方便调试观察）
            System.out.println("\033[33m[工具] " + toolName + ": " +
                    result.substring(0, Math.min(200, result.length())) + "\033[0m");

            // 第五步：把工具执行结果加入对话历史
            // 为什么用 tool 角色？因为这是 OpenAI 格式的约定，模型通过这个角色识别工具返回
            messages.add(DashScopeClient.toolResultMessage(toolCallId, result));
        }

        // 还需要继续循环——模型需要看到工具结果后再决定下一步
        return true;
    }

    /**
     * Agent 主循环
     * <p>
     * 为什么是 while(true) + break？
     * 因为我们不知道模型会调用多少次工具。可能一次就回答了，
     * 也可能需要调用好几次工具才能完成任务。循环直到模型不再请求工具为止。
     *
     * 设置最大轮次是为了安全——防止模型陷入死循环。
     */
    private static void agentLoop(List<Map<String, Object>> messages) {
        int maxTurns = 20; // 安全上限：防止无限循环
        for (int turn = 0; turn < maxTurns; turn++) {
            if (!runOneTurn(messages)) {
                return; // 模型给出了最终回答
            }
        }
        System.out.println("[警告] 达到最大轮次限制（" + maxTurns + "），强制停止");
    }

    // ==================== REPL 交互界面 ====================

    /**
     * 主入口：简单的命令行交互循环（Read-Eval-Print Loop）
     * <p>
     * 用户输入一句话 → Agent 跑循环处理 → 打印模型最终回答 → 等待下一句
     */
    public static void main(String[] args) throws Exception {
        // 对话历史在整个会话中持续保留（多轮对话共享上下文）
        List<Map<String, Object>> history = new ArrayList<>();
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));

        System.out.println("=== S01 Agent Loop 演示 ===");
        System.out.println("输入任务让 Agent 执行，输入 q 退出\n");

        while (true) {
            System.out.print("\033[36m[S01] >>> \033[0m");
            String input = reader.readLine();
            if (input == null || input.isBlank() ||
                    "q".equalsIgnoreCase(input.trim()) || "exit".equalsIgnoreCase(input.trim())) {
                break;
            }

            // 用户输入作为 user 消息加入历史
            history.add(DashScopeClient.userMessage(input));

            // 运行 Agent 循环
            agentLoop(history);

            // 提取并打印模型的最终文本回答
            String finalText = extractLastAssistantText(history);
            if (!finalText.isEmpty()) {
                System.out.println("\n" + finalText);
            }
            System.out.println();
        }
    }

    /**
     * 从对话历史中提取最后一条 assistant 消息的文本内容
     */
    private static String extractLastAssistantText(List<Map<String, Object>> history) {
        // 从后往前找最后一条 assistant 消息
        for (int i = history.size() - 1; i >= 0; i--) {
            Map<String, Object> msg = history.get(i);
            if ("assistant".equals(msg.get("role"))) {
                Object content = msg.get("content");
                if (content instanceof String s) {
                    return s;
                }
                // 如果 content 是 null（模型只发了 tool_calls 没有文本），继续往前找
                if (content == null) continue;
                return content.toString();
            }
        }
        return "";
    }
}
