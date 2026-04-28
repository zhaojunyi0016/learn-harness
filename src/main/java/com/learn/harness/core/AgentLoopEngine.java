package com.learn.harness.core;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import com.learn.harness.tools.WeatherTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

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
public class AgentLoopEngine {

    private static final Logger logger = LoggerFactory.getLogger(AgentLoopEngine.class);

    /**
     * 默认最大循环次数
     */
    private static final int DEFAULT_MAX_LOOPS = 10;

    @Resource
    private ChatModel chatModel;

    @Resource
    private ChatClient chatClient;

    @Resource
    private WeatherTool weatherTool;

    /**
     * 工具列表
     */
    private List<ToolCallback> toolCallbacks;

    /**
     * 初始化工具回调
     */
    @PostConstruct
    public void initTools() {
        toolCallbacks = new ArrayList<>();
        
        // 注册天气查询工具 - 自动扫描 @Tool 注解的方法
        for (ToolCallback toolCallback : ToolCallbacks.from(weatherTool)) {
            toolCallbacks.add(toolCallback);
        }
        
        logger.info("已注册 {} 个工具", toolCallbacks.size());
    }

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

            logger.info("第 {} 轮循环开始", loopCount);

            try {
                // 2️⃣ 调用模型
                AssistantMessage assistantMessage = callModel(messages);

                // 3️⃣ 获取模型响应并加入上下文
                messages.add(assistantMessage);

                logger.info("模型响应: {}", assistantMessage.getText());

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
                    logger.info("Tool 执行完成，结果已加入上下文");

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
    private AssistantMessage callModel(List<Message> messages) {
        ChatResponse response = chatClient.prompt()
                .messages(messages)
                .toolCallbacks(toolCallbacks)
                .call()
                .chatResponse();
        
        if (response != null && response.getResult() != null) {
            return response.getResult().getOutput();
        }
        
        return new AssistantMessage("模型未返回有效响应");
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
    private ToolResponseMessage executeTools(List<AssistantMessage.ToolCall> toolCalls, Map<String, Object> contextParams) {

        List<ToolResponseMessage.ToolResponse> toolResponses = new ArrayList<>();

        for (AssistantMessage.ToolCall toolCall : toolCalls) {
            String toolCallId = toolCall.id();
            String toolName = toolCall.name();
            String arguments = toolCall.arguments();

            logger.info("执行 Tool: {}, ID: {}, 参数: {}", toolName, toolCallId, arguments);

            try {
                // 直接从 toolCallbacks 中找到对应的工具并执行
                Object result = invokeToolCallback(toolName, arguments);

                toolResponses.add(new ToolResponseMessage.ToolResponse(
                        toolCallId,
                        toolName,
                        String.valueOf(result)
                ));

                logger.info("Tool {} 执行成功，结果: {}", toolName, result);

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
     * 直接调用 ToolCallback 执行工具
     */
    private String invokeToolCallback(String toolName, String arguments) {
        for (ToolCallback callback : toolCallbacks) {
            if (callback.getToolDefinition().name().equals(toolName)) {
                try {
                    return callback.call(arguments);
                } catch (Exception e) {
                    logger.error("Tool {} 执行异常", toolName, e);
                    return "Tool执行异常: " + e.getMessage();
                }
            }
        }
        logger.warn("未找到工具: {}", toolName);
        return "工具未找到: " + toolName;
    }

    // ==================== 配置方法 ====================

    /**
     * 设置最大循环次数
     */
    public AgentLoopEngine maxLoopCount(int maxLoopCount) {
        this.maxLoopCount = maxLoopCount;
        return this;
    }

    /**
     * 设置循环开始回调
     */
    public AgentLoopEngine onLoopStart(Consumer<LoopStatus> callback) {
        this.onLoopStart = callback;
        return this;
    }

    /**
     * 设置循环结束回调
     */
    public AgentLoopEngine onLoopEnd(Consumer<LoopStatus> callback) {
        this.onLoopEnd = callback;
        return this;
    }

    /**
     * 设置 Tool 执行回调
     */
    public AgentLoopEngine onToolExecuted(Consumer<ToolExecutionResult> callback) {
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
