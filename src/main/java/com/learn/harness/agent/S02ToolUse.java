package com.learn.harness.agent;

/**
 * 【第二章】工具使用与分发（Tool Use & Dispatch）
 *
 * 本章在 S01 的基础上扩展：
 * - S01 只有一个 bash 工具
 * - 本章注册了完整的工具集（bash + read_file + write_file + edit_file）
 * - 引入"工具分发"概念：模型说要调什么工具，我们根据名字找到对应实现去执行
 *
 * 核心教学点：
 * 1. Agent 循环本身没有变化——变的只是可用工具变多了
 * 2. 工具分发的本质就是一个 switch/map：工具名 → 执行逻辑
 * 3. 消息规范化（normalizeMessages）：在发送前清理消息格式，保证 API 能接受
 *
 * 关键洞察："循环没变，我只是加了工具。"
 * 这说明好的 Agent 架构应该是：循环是固定的骨架，工具是可插拔的能力。
 *
 * 运行方式：
 *   export DASHSCOPE_API_KEY="your-key"
 *   mvn compile exec:java -Dexec.mainClass="com.learn.harness.agent.S02ToolUse"
 */

import com.google.gson.*;
import java.io.*;
import java.util.*;

public class S02ToolUse {

    // ==================== 配置 ====================

    private static final DashScopeClient client = new DashScopeClient();

    /**
     * 系统提示词
     * <p>
     * 和 S01 的区别：这里明确告诉模型"使用工具来解决任务"，
     * 因为现在有了文件读写工具，模型可以做更多事情了。
     */
    private static final String SYSTEM_PROMPT =
            "你是一个编程助手，工作目录是 " + CommonTools.WORKDIR + "。\n" +
            "使用工具来完成任务。优先行动，不要空谈。";

    /**
     * 注册全部基础工具（bash + read_file + write_file + edit_file）
     * <p>
     * 为什么用 CommonTools.allBasicToolDefs()？
     * 因为这四个工具的定义（schema）在 CommonTools 中已经写好了，
     * 各教程文件直接引用，避免重复写 JSON schema。
     */
    private static final List<Map<String, Object>> TOOLS = CommonTools.allBasicToolDefs();

    // ==================== 消息规范化 ====================

    /**
     * 消息规范化：在发送给 API 前，清理对话历史
     * <p>
     * 为什么需要这一步？
     * 在复杂场景下，对话历史中可能混入格式不标准的消息（比如额外字段、null 值等）。
     * 规范化保证发给 API 的消息格式始终正确。
     * <p>
     * 本章的实现是直接透传（教学简化），后续章节（如 S06）会做真正的消息压缩。
     *
     * @param messages 原始消息列表
     * @return 规范化后的消息列表
     */
    private static List<Map<String, Object>> normalizeMessages(List<Map<String, Object>> messages) {
        // 当前版本直接返回副本，不做特殊处理
        // 这个方法的存在是为了展示：好的架构会预留这种扩展点
        return new ArrayList<>(messages);
    }

    // ==================== Agent 循环 ====================

    /**
     * Agent 循环
     * <p>
     * 注意和 S01 的对比——循环结构完全一样，唯一的区别是：
     * 1. 工具从 1 个变成了 4 个
     * 2. 发送前经过 normalizeMessages
     *
     * 这正好验证了本章的核心观点：循环是稳定的骨架，工具是可扩展的插件。
     */
    private static void agentLoop(List<Map<String, Object>> messages) {
        int maxTurns = 30; // 有更多工具后，可能需要更多轮次
        for (int turn = 0; turn < maxTurns; turn++) {
            // 发送规范化后的消息给模型
            JsonObject response = client.createMessage(
                    SYSTEM_PROMPT, normalizeMessages(messages), TOOLS, 4096);

            // 把 assistant 响应加入历史
            messages.add(DashScopeClient.assistantMessageToMap(response));

            // 如果模型不需要工具 → 结束
            if (!DashScopeClient.hasToolCalls(response)) {
                return;
            }

            // 执行工具调用
            JsonArray toolCalls = DashScopeClient.getToolCalls(response);
            for (int i = 0; i < toolCalls.size(); i++) {
                JsonObject toolCall = toolCalls.get(i).getAsJsonObject();

                String toolName = DashScopeClient.getToolName(toolCall);
                JsonObject arguments = DashScopeClient.getToolArguments(toolCall);
                String toolCallId = DashScopeClient.getToolCallId(toolCall);

                // 通过 CommonTools.dispatch 做分发
                // 这就是"工具分发"：名字 → 实现。本质就是一个 switch 表达式。
                String result;
                try {
                    result = CommonTools.dispatch(toolName, arguments);
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

        System.out.println("=== S02 Tool Use 演示 ===");
        System.out.println("现在 Agent 可以: bash执行命令 / read_file读文件 / write_file写文件 / edit_file编辑文件");
        System.out.println("输入任务让 Agent 执行，输入 q 退出\n");

        while (true) {
            System.out.print("\033[36m[S02] >>> \033[0m");
            String input = reader.readLine();
            if (input == null || input.isBlank() ||
                    "q".equalsIgnoreCase(input.trim()) || "exit".equalsIgnoreCase(input.trim())) {
                break;
            }

            history.add(DashScopeClient.userMessage(input));
            agentLoop(history);

            // 打印最终回答
            String text = DashScopeClient.extractText(getLastResponse(history));
            if (text != null && !text.isEmpty()) {
                System.out.println("\n" + text);
            }
            System.out.println();
        }
    }

    /**
     * 找到对话历史中最后一条来自模型的响应
     * <p>
     * 简化处理：重新调用一次获取 assistant 消息中的 content 字段。
     * 实际生产中会用更优雅的方式追踪最终回答。
     */
    private static JsonObject getLastResponse(List<Map<String, Object>> history) {
        // 从最后的 assistant 消息中构建一个 mock response 用于 extractText
        for (int i = history.size() - 1; i >= 0; i--) {
            Map<String, Object> msg = history.get(i);
            if ("assistant".equals(msg.get("role"))) {
                Object content = msg.get("content");
                // 构建一个简化的 response 结构
                JsonObject fakeResponse = new JsonObject();
                JsonArray choices = new JsonArray();
                JsonObject choice = new JsonObject();
                JsonObject message = new JsonObject();
                if (content instanceof String s) {
                    message.addProperty("content", s);
                } else if (content != null) {
                    message.addProperty("content", content.toString());
                } else {
                    message.addProperty("content", "");
                }
                choice.add("message", message);
                choice.addProperty("finish_reason", "stop");
                choices.add(choice);
                fakeResponse.add("choices", choices);
                return fakeResponse;
            }
        }
        return new JsonObject();
    }
}
