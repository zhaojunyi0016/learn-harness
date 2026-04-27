package com.learn.harness.loop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Agent 循环控制器
 * <p>
 * 核心功能：
 * 1. 管理多轮对话的消息历史
 * 2. 处理 Tool Call 的执行循环
 * 3. 支持最大循环次数控制，防止无限循环
 * 4. 提供状态回调和日志记录
 */
@Service
public class AgentLoop {

    private static final Logger logger = LoggerFactory.getLogger(AgentLoop.class);

    /**
     * 默认最大循环次数
     */
    private static final int DEFAULT_MAX_LOOPS = 10;

    @Resource
    private ChatClient chatClient;

    /**
     * 最大循环次数，防止无限调用
     */
    private int maxLoopCount = DEFAULT_MAX_LOOPS;

    /**
     * 状态回调：每次循环开始
     */
    private Consumer<LoopStatus> onLoopStart;

    /**
     * 状态回调：每次循环结束
     */
    private Consumer<LoopStatus> onLoopEnd;

    /**
     * Tool 执行结果回调
     */
    private Consumer<ToolExecutionResult> onToolExecuted;

    /**
     * 执行 Agent 循环
     *
     * @param userInput 用户输入
     * @return Agent 最终响应
     */
    public String agentLoop(String userInput) {
        return agentLoop(userInput, new HashMap<>());
    }

    /**
     * 执行 Agent 循环（带上下文参数）
     *
     * @param userInput     用户输入
     * @param contextParams 上下文参数（如用户ID、会话ID等）
     * @return Agent 最终响应
     */
    public String agentLoop(String userInput, Map<String, Object> contextParams) {
        logger.info("Agent循环开始，用户输入: {}", userInput);

        // 1️⃣ 初始化消息列表
        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage(userInput));

        String finalResponse = null;
        int loopCount = 0;

        while (true) {
            loopCount++;
            LoopStatus status = new LoopStatus(loopCount, messages, contextParams);

            // 检查循环次数限制
            if (loopCount > maxLoopCount) {
                logger.warn("达到最大循环次数 {}，强制终止循环", maxLoopCount);
                finalResponse = "已达到最大循环次数限制，请稍后重试。";
                break;
            }

            // 触发循环开始回调
            if (onLoopStart != null) {
                onLoopStart.accept(status);
            }

            logger.debug("第 {} 轮循环开始", loopCount);

            try {
                // 2️⃣ 调用模型
                ChatResponse chatResponse = callModel(messages);

                // 3️⃣ 获取模型响应并加入上下文
                AssistantMessage assistantMessage = chatResponse.getResult().getOutput();
                messages.add(assistantMessage);

                logger.debug("模型响应: {}", assistantMessage.getText());

                // 4️⃣ 判断是否有 Tool Call
                if (hasToolCalls(assistantMessage)) {
                    logger.info("检测到 Tool Call，准备执行");

                    // 5️⃣ 执行 Tool
                    List<AssistantMessage.ToolCall> toolCalls = assistantMessage.getToolCalls();
                    ToolResponseMessage toolResponseMessage = executeTools(toolCalls, contextParams);

                    // 触发 Tool 执行回调
                    if (onToolExecuted != null) {
                        onToolExecuted.accept(new ToolExecutionResult(toolCalls, toolResponseMessage));
                    }

                    // 6️⃣ 把 Tool 结果追加回去
                    messages.add(toolResponseMessage);
                    logger.debug("Tool 执行完成，结果已加入上下文");

                    // 继续下一轮循环
                    continue;
                }

                // 没有 Tool Call，返回最终响应
                finalResponse = assistantMessage.getText();
                logger.info("Agent循环结束，最终响应: {}", finalResponse);
                break;

            } catch (Exception e) {
                logger.error("第 {} 轮循环执行异常", loopCount, e);
                finalResponse = "执行过程中发生错误: " + e.getMessage();
                break;
            } finally {
                // 触发循环结束回调
                if (onLoopEnd != null) {
                    onLoopEnd.accept(status);
                }
            }
        }

        return finalResponse;
    }

    /**
     * 调用模型
     */
    private ChatResponse callModel(List<Message> messages) {
        return chatClient.prompt()
                .messages(messages)
                .tools()
                .call()
                .entity(ChatResponse.class);
    }

    /**
     * 判断响应中是否包含 Tool Call
     */
    private boolean hasToolCalls(AssistantMessage message) {
        return !message.getToolCalls().isEmpty();
    }

    /**
     * 执行 Tool Calls
     * 使用 MethodToolCallback 执行每个工具调用
     */
    private ToolResponseMessage executeTools(
            List<AssistantMessage.ToolCall> toolCalls,
            Map<String, Object> contextParams) {

        List<ToolResponseMessage.ToolResponse> toolResponses = new ArrayList<>();

        for (AssistantMessage.ToolCall toolCall : toolCalls) {
            String toolCallId = toolCall.id();
            String toolName = toolCall.name();
            String arguments = toolCall.arguments();

            logger.info("执行 Tool: {}, ID: {}", toolName, toolCallId);

            try {
                // 查找对应的 ToolCallback 并执行
                Object result = executeToolByName(toolName, arguments);

                toolResponses.add(new ToolResponseMessage.ToolResponse(
                        toolCallId,
                        toolName,
                        String.valueOf(result)
                ));

                logger.debug("Tool {} 执行成功，结果: {}", toolName, result);

            } catch (Exception e) {
                logger.error("Tool {} 执行失败", toolName, e);
                toolResponses.add(new ToolResponseMessage.ToolResponse(
                        toolCallId,
                        toolName,
                        "Tool执行失败: " + e.getMessage()
                ));
            }
        }

        return ToolResponseMessage.builder()
                .responses(toolResponses)
                .build();
    }

    /**
     * 根据工具名称执行工具
     * 子类可覆盖此方法实现自定义的工具执行逻辑
     */
    protected Object executeToolByName(String toolName, String arguments) {
        // 这里是默认实现，子类或外部可通过注入 ToolCallbackRegistry 来管理工具
        logger.warn("未找到工具 {} 的执行器，请确保已注册该工具", toolName);
        return "工具未找到: " + toolName;
    }

    // ==================== 配置方法 ====================

    /**
     * 设置最大循环次数
     */
    public AgentLoop maxLoopCount(int maxLoopCount) {
        this.maxLoopCount = maxLoopCount;
        return this;
    }

    /**
     * 设置循环开始回调
     */
    public AgentLoop onLoopStart(Consumer<LoopStatus> callback) {
        this.onLoopStart = callback;
        return this;
    }

    /**
     * 设置循环结束回调
     */
    public AgentLoop onLoopEnd(Consumer<LoopStatus> callback) {
        this.onLoopEnd = callback;
        return this;
    }

    /**
     * 设置 Tool 执行回调
     */
    public AgentLoop onToolExecuted(Consumer<ToolExecutionResult> callback) {
        this.onToolExecuted = callback;
        return this;
    }

    // ==================== 内部类 ====================

    /**
     * 循环状态信息
     */
    public static class LoopStatus {
        private final int loopCount;
        private final List<Message> messages;
        private final Map<String, Object> contextParams;

        public LoopStatus(int loopCount, List<Message> messages, Map<String, Object> contextParams) {
            this.loopCount = loopCount;
            this.messages = messages;
            this.contextParams = contextParams;
        }

        public int getLoopCount() {
            return loopCount;
        }

        public List<Message> getMessages() {
            return messages;
        }

        public Map<String, Object> getContextParams() {
            return contextParams;
        }
    }

    /**
     * Tool 执行结果
     */
    public static class ToolExecutionResult {
        private final List<AssistantMessage.ToolCall> toolCalls;
        private final ToolResponseMessage toolResponseMessage;

        public ToolExecutionResult(List<AssistantMessage.ToolCall> toolCalls,
                                   ToolResponseMessage toolResponseMessage) {
            this.toolCalls = toolCalls;
            this.toolResponseMessage = toolResponseMessage;
        }

        public List<AssistantMessage.ToolCall> getToolCalls() {
            return toolCalls;
        }

        public ToolResponseMessage getToolResponseMessage() {
            return toolResponseMessage;
        }
    }
}
