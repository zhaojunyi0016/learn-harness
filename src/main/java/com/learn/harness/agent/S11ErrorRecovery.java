package com.learn.harness.agent;

/**
 * 【第十一章】错误恢复（Error Recovery）
 *
 * 本章教的核心观点：健壮的 Agent 不会因为一次错误就崩溃，而是尝试恢复。
 *
 * 三种常见错误和对应的恢复策略：
 * 1. max_tokens（输出截断）→ 注入"请继续"消息，让模型接着写
 * 2. prompt_too_long（输入超长）→ 自动压缩对话历史，重试
 * 3. 网络/限流错误 → 指数退避重试（等越来越久再重试）
 *
 * 核心教学点：
 * - "崩溃"和"重试"之间的智能选择
 * - 指数退避：第一次等 1 秒，第二次等 2 秒，第三次等 4 秒...
 * - 恢复次数有上限（防止无限重试）
 * - 不同错误用不同策略处理，不是一刀切
 *
 * 运行方式：
 *   export DASHSCOPE_API_KEY="your-key"
 *   mvn compile exec:java -Dexec.mainClass="com.learn.harness.agent.S11ErrorRecovery"
 */

import com.google.gson.*;
import java.io.*;
import java.util.*;

public class S11ErrorRecovery {

    // ==================== 配置 ====================

    private static final DashScopeClient client = new DashScopeClient();

    /** 每种错误最多重试几次 */
    private static final int MAX_RECOVERY_ATTEMPTS = 3;

    /** 退避重试的基础延迟（秒） */
    private static final double BACKOFF_BASE_DELAY = 1.0;

    /** 退避重试的最大延迟（秒） */
    private static final double BACKOFF_MAX_DELAY = 30.0;

    /** 上下文 token 估算阈值（超过则主动压缩） */
    private static final int TOKEN_THRESHOLD = 50000;

    /** 输出截断时注入的"继续"消息 */
    private static final String CONTINUATION_MESSAGE =
            "输出被截断了。请从你停下来的地方直接继续——不要重复前面的内容。";

    private static final String SYSTEM_PROMPT =
            "你是一个编程助手，工作目录是 " + CommonTools.WORKDIR + "。使用工具来完成任务。";

    // ==================== 错误恢复策略 ====================

    /**
     * 计算指数退避延迟
     * <p>
     * 为什么用指数退避？
     * 如果服务器过载（限流），快速重试只会加重负担。
     * 指数退避让等待时间越来越长，给服务器喘息的机会。
     * 加一点随机抖动（jitter）避免多个客户端同时重试。
     */
    private static double calcBackoffDelay(int attempt) {
        double delay = Math.min(BACKOFF_BASE_DELAY * Math.pow(2, attempt), BACKOFF_MAX_DELAY);
        return delay + Math.random(); // 加随机抖动
    }

    /**
     * 估算对话历史的 token 数
     * <p>
     * 粗略估算：JSON 字符数 / 4 ≈ token 数（英文场景）
     */
    private static int estimateTokens(List<Map<String, Object>> messages) {
        return DashScopeClient.gson().toJson(messages).length() / 4;
    }

    /**
     * 自动压缩对话历史（当上下文过长时调用）
     * <p>
     * 让模型总结当前对话，然后用摘要替代原始历史
     */
    private static void autoCompact(List<Map<String, Object>> messages) {
        String conversation = DashScopeClient.gson().toJson(messages);
        if (conversation.length() > 60000) conversation = conversation.substring(0, 60000);

        String prompt = "请总结以下对话，保留：\n" +
                "1) 任务目标\n2) 已完成的工作和修改的文件\n3) 关键决策和失败的尝试\n4) 接下来要做什么\n\n" + conversation;

        String summary;
        try {
            JsonObject resp = client.createMessage("", List.of(DashScopeClient.userMessage(prompt)), null, 2000);
            summary = DashScopeClient.extractText(resp);
        } catch (Exception e) {
            summary = "（压缩失败: " + e.getMessage() + "）";
        }

        messages.clear();
        messages.add(DashScopeClient.userMessage(
                "[对话已压缩] 以下是之前的摘要：\n\n" + summary + "\n\n请从断点继续工作。"));
    }

    // ==================== Agent 循环（带错误恢复） ====================

    private static void agentLoop(List<Map<String, Object>> messages) {
        int maxOutputRecovery = 0; // 连续 max_tokens 恢复计数

        for (int turn = 0; turn < 40; turn++) {
            // 主动压缩检查
            if (estimateTokens(messages) > TOKEN_THRESHOLD) {
                System.out.println("\033[35m[恢复] token 估算超限，主动压缩...\033[0m");
                autoCompact(messages);
            }

            // 带重试的 API 调用
            JsonObject response = null;
            for (int attempt = 0; attempt <= MAX_RECOVERY_ATTEMPTS; attempt++) {
                try {
                    response = client.createMessage(SYSTEM_PROMPT, messages, CommonTools.allBasicToolDefs(), 4096);
                    break; // 成功了，跳出重试循环
                } catch (RuntimeException e) {
                    String errMsg = e.getMessage().toLowerCase();

                    // 策略 2：输入过长 → 压缩后重试
                    if (errMsg.contains("too long") || errMsg.contains("token") || errMsg.contains("length")) {
                        System.out.printf("\033[35m[恢复] 输入过长，压缩中... (第 %d 次)\033[0m%n", attempt + 1);
                        autoCompact(messages);
                        continue;
                    }

                    // 策略 3：网络/限流错误 → 指数退避
                    if (attempt < MAX_RECOVERY_ATTEMPTS) {
                        double delay = calcBackoffDelay(attempt);
                        System.out.printf("\033[35m[恢复] API错误: %s | %.1f秒后重试 (%d/%d)\033[0m%n",
                                e.getMessage().substring(0, Math.min(50, e.getMessage().length())),
                                delay, attempt + 1, MAX_RECOVERY_ATTEMPTS);
                        try { Thread.sleep((long) (delay * 1000)); } catch (InterruptedException ignored) {}
                        continue;
                    }
                    System.out.println("[错误] 重试 " + MAX_RECOVERY_ATTEMPTS + " 次后仍失败: " + e.getMessage());
                    return;
                }
            }
            if (response == null) { System.out.println("[错误] 未收到响应"); return; }

            messages.add(DashScopeClient.assistantMessageToMap(response));

            // 策略 1：max_tokens 恢复
            String finishReason = DashScopeClient.getFinishReason(response);
            if ("length".equals(finishReason)) {
                maxOutputRecovery++;
                if (maxOutputRecovery <= MAX_RECOVERY_ATTEMPTS) {
                    System.out.printf("\033[35m[恢复] 输出被截断 (%d/%d)，注入继续消息...\033[0m%n",
                            maxOutputRecovery, MAX_RECOVERY_ATTEMPTS);
                    messages.add(DashScopeClient.userMessage(CONTINUATION_MESSAGE));
                    continue;
                } else {
                    System.out.println("[错误] 连续截断超过上限，停止");
                    return;
                }
            }

            maxOutputRecovery = 0; // 重置连续截断计数

            // 正常结束
            if (!DashScopeClient.hasToolCalls(response)) {
                return;
            }

            // 执行工具
            JsonArray toolCalls = DashScopeClient.getToolCalls(response);
            for (int i = 0; i < toolCalls.size(); i++) {
                JsonObject toolCall = toolCalls.get(i).getAsJsonObject();
                String toolName = DashScopeClient.getToolName(toolCall);
                JsonObject arguments = DashScopeClient.getToolArguments(toolCall);
                String toolCallId = DashScopeClient.getToolCallId(toolCall);

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

        System.out.println("=== S11 Error Recovery 演示 ===");
        System.out.println("[已启用恢复策略: 输出截断恢复 / 输入过长压缩 / 网络退避重试]");
        System.out.println("输入 q 退出\n");

        while (true) {
            System.out.print("\033[36m[S11] >>> \033[0m");
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
