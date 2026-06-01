package com.learn.harness.agent;

/**
 * 【第六章】上下文压缩（Context Compact）
 *
 * 本章解决一个实际问题：对话太长了怎么办？
 *
 * 问题背景：
 * 模型有上下文窗口限制（比如 8K/32K tokens）。当 Agent 执行很多轮工具调用后，
 * 对话历史会越来越长，最终超出限制导致 API 报错。
 *
 * 解决方案：上下文压缩（三个层次）
 * 1. 大输出持久化：工具输出超过阈值时，存到磁盘，只保留预览
 * 2. 旧结果微压缩：只保留最近几轮的完整工具输出，旧的替换为摘要
 * 3. 整体摘要：当整个对话超长时，让模型总结后从摘要继续
 *
 * 核心教学点：
 * - 不是所有信息都需要留在上下文中——模型只需要"当前还有用"的信息
 * - 持久化到磁盘是一种"卸载记忆"的方式
 * - compact 可以是自动的（超限时触发）或手动的（模型主动触发）
 *
 * 运行方式：
 *   export DASHSCOPE_API_KEY="your-key"
 *   mvn compile exec:java -Dexec.mainClass="com.learn.harness.agent.S06ContextCompact"
 */

import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public class S06ContextCompact {

    // ==================== 配置 ====================

    private static final DashScopeClient client = new DashScopeClient();

    /** 上下文大小上限（字符数估算），超过则自动压缩 */
    private static final int CONTEXT_LIMIT = 50000;

    /** 工具输出超过此长度时，持久化到磁盘 */
    private static final int PERSIST_THRESHOLD = 30000;

    /** 持久化文件存放目录 */
    private static final Path TOOL_RESULTS_DIR = Path.of(CommonTools.WORKDIR, ".task_outputs", "tool-results");

    private static final String SYSTEM_PROMPT =
            "你是一个编程助手，工作目录是 " + CommonTools.WORKDIR + "。\n" +
            "持续工作直到完成任务。如果对话变得太长，使用 compact 工具来压缩上下文。";

    // ==================== 压缩状态 ====================

    /**
     * 记录压缩相关的状态
     */
    static class CompactState {
        boolean hasCompacted = false;   // 是否已经压缩过
        String lastSummary = "";        // 最后一次压缩的摘要
    }

    // ==================== 上下文大小估算 ====================

    /**
     * 估算对话历史的大小（字符数）
     * <p>
     * 为什么用字符数而不是 token 数？
     * 因为精确的 token 计算需要 tokenizer，而字符数足够做粗略估算。
     * 实际上 1 个中文字符约 1-2 个 token，英文约 0.3 个 token。
     */
    private static int estimateSize(List<Map<String, Object>> messages) {
        return DashScopeClient.gson().toJson(messages).length();
    }

    // ==================== 大输出持久化 ====================

    /**
     * 如果工具输出太大，存到磁盘，只返回预览
     * <p>
     * 为什么要这么做？
     * 比如 bash 输出了一个 100KB 的日志文件内容，全放进对话会立刻挤爆上下文。
     * 存到磁盘后，如果模型后面需要看完整内容，可以用 read_file 读取。
     *
     * @param toolCallId 工具调用 ID（用作文件名）
     * @param output     工具原始输出
     * @return 原始输出或持久化后的预览
     */
    private static String persistIfLarge(String toolCallId, String output) {
        if (output.length() <= PERSIST_THRESHOLD) return output;
        try {
            Files.createDirectories(TOOL_RESULTS_DIR);
            Path stored = TOOL_RESULTS_DIR.resolve(toolCallId + ".txt");
            Files.writeString(stored, output);
            String preview = output.substring(0, Math.min(2000, output.length()));
            return "[输出过长已持久化] 完整内容保存在: " + stored + "\n预览:\n" + preview + "\n...";
        } catch (Exception e) {
            return output; // 持久化失败就保留原文
        }
    }

    // ==================== 对话压缩 ====================

    /**
     * 压缩整个对话历史
     * <p>
     * 做法：把当前所有消息发给模型做摘要，然后用摘要替代原始历史。
     * 压缩后对话只剩一条 user 消息（摘要），大幅减少上下文占用。
     *
     * @param messages 当前对话历史（会被清空并替换为摘要）
     * @param state    压缩状态
     * @param focus    当前聚焦的任务（可选，用于引导摘要）
     */
    private static void compactHistory(List<Map<String, Object>> messages, CompactState state, String focus) {
        // 序列化当前对话（截断到合理长度，防止摘要请求本身太长）
        String conversation = DashScopeClient.gson().toJson(messages);
        if (conversation.length() > 60000) {
            conversation = conversation.substring(0, 60000);
        }

        // 让模型生成摘要
        String summaryPrompt = "请总结以下 Agent 对话，保留关键信息以便继续工作：\n" +
                "1. 当前目标是什么\n2. 已做的操作和发现\n3. 修改了哪些文件\n4. 还剩什么工作\n\n" +
                conversation;

        String summary;
        try {
            JsonObject resp = client.createMessage("", List.of(DashScopeClient.userMessage(summaryPrompt)), null, 2000);
            summary = DashScopeClient.extractText(resp);
        } catch (Exception e) {
            summary = "（压缩失败: " + e.getMessage() + "）";
        }

        if (focus != null && !focus.isEmpty()) {
            summary += "\n\n当前聚焦: " + focus;
        }

        // 替换原始历史
        state.hasCompacted = true;
        state.lastSummary = summary;
        messages.clear();
        messages.add(DashScopeClient.userMessage(
                "[上下文已压缩，以下是之前对话的摘要]\n\n" + summary + "\n\n请继续工作。"));
    }

    // ==================== 工具定义 ====================

    private static List<Map<String, Object>> buildTools() {
        List<Map<String, Object>> tools = new ArrayList<>(CommonTools.allBasicToolDefs());
        tools.add(DashScopeClient.toolDefinition("compact",
                "压缩对话上下文（当对话太长或需要释放空间时使用）",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "focus", Map.of("type", "string", "description", "当前聚焦的任务描述（帮助生成更好的摘要）")
                        )
                )));
        return tools;
    }

    private static final List<Map<String, Object>> TOOLS = buildTools();

    // ==================== Agent 循环（带自动压缩） ====================

    private static void agentLoop(List<Map<String, Object>> messages, CompactState state) {
        int maxTurns = 40; // 有压缩后可以跑更多轮
        for (int turn = 0; turn < maxTurns; turn++) {
            // 自动压缩检查：如果对话太长，先压缩再继续
            if (estimateSize(messages) > CONTEXT_LIMIT) {
                System.out.println("\033[35m[自动压缩] 对话超长，正在压缩...\033[0m");
                compactHistory(messages, state, null);
            }

            JsonObject response = client.createMessage(SYSTEM_PROMPT, messages, TOOLS, 4096);
            messages.add(DashScopeClient.assistantMessageToMap(response));

            if (!DashScopeClient.hasToolCalls(response)) {
                return;
            }

            boolean manualCompact = false;
            String compactFocus = null;

            JsonArray toolCalls = DashScopeClient.getToolCalls(response);
            for (int i = 0; i < toolCalls.size(); i++) {
                JsonObject toolCall = toolCalls.get(i).getAsJsonObject();
                String toolName = DashScopeClient.getToolName(toolCall);
                JsonObject arguments = DashScopeClient.getToolArguments(toolCall);
                String toolCallId = DashScopeClient.getToolCallId(toolCall);

                String result;
                if ("compact".equals(toolName)) {
                    // 手动压缩：模型主动决定压缩
                    manualCompact = true;
                    compactFocus = arguments.has("focus") ? arguments.get("focus").getAsString() : null;
                    result = "正在压缩...";
                } else {
                    try {
                        result = CommonTools.dispatch(toolName, arguments);
                    } catch (Exception e) {
                        result = "错误: " + e.getMessage();
                    }
                    // 对大输出做持久化
                    result = persistIfLarge(toolCallId, result);
                }

                System.out.println("\033[33m[" + toolName + "] " +
                        result.substring(0, Math.min(200, result.length())) + "\033[0m");
                messages.add(DashScopeClient.toolResultMessage(toolCallId, result));
            }

            // 如果模型请求了手动压缩，在本轮结束时执行
            if (manualCompact) {
                System.out.println("\033[35m[手动压缩] 模型请求压缩上下文\033[0m");
                compactHistory(messages, state, compactFocus);
            }
        }
        System.out.println("[警告] 达到最大轮次限制");
    }

    // ==================== REPL ====================

    public static void main(String[] args) throws Exception {
        List<Map<String, Object>> history = new ArrayList<>();
        CompactState state = new CompactState();
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));

        System.out.println("=== S06 Context Compact 演示 ===");
        System.out.println("给 Agent 一个需要很多步骤的任务，观察上下文压缩何时触发");
        System.out.println("输入 q 退出\n");

        while (true) {
            System.out.print("\033[36m[S06] >>> \033[0m");
            String input = reader.readLine();
            if (input == null || input.isBlank() ||
                    "q".equalsIgnoreCase(input.trim()) || "exit".equalsIgnoreCase(input.trim())) {
                break;
            }

            history.add(DashScopeClient.userMessage(input));
            agentLoop(history, state);
            System.out.println();
        }
    }
}
