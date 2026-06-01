package com.learn.harness.core;

import com.learn.harness.tools.WeatherTool;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Agent 循环控制器（项目核心组件）
 * <p>
 * 整个 Harness 项目对外的“大脑”，所有用户请求最终都会进入这里。
 * <p>
 * 核心职责：
 * 1. 管理多轮对话的消息历史（User / Assistant / ToolResponse）
 * 2. 驱动 Tool Call 的执行循环：模型判断 → 调用工具 → 把工具结果回灌给模型 → 直至模型给出最终答案
 * 3. 控制最大循环次数，防止模型陷入死循环
 * 4. 提供循环开始 / 循环结束 / 工具执行三类状态回调，便于上层观察、埋点、做扩展
 * <p>
 * 后续的 RAG、提示词工程、长短期记忆等能力都会以“插件 / 拦截器”的形式接入本类，
 * 而对外暴露的 {@link #agentLoop(String)} 入口保持不变。
 */
@Service
public class AgentLoopEngine {

    private static final Logger logger = LoggerFactory.getLogger(AgentLoopEngine.class);

    /** 防止模型与工具间陷入无限互调的默认最大循环次数 */
    private static final int DEFAULT_MAX_LOOPS = 10;

    /** Spring AI 高级对话客户端，由 SpringAIConfig 注册 */
    @Resource
    private ChatClient chatClient;

    /** 天气查询工具实例（@Tool 方法会被扫描注册） */
    @Resource
    private WeatherTool weatherTool;

    /** 当前 Agent 可用的工具回调列表，每个 ToolCallback 对应一个 @Tool 注解方法 */
    private List<ToolCallback> toolCallbacks;

    /**
     * Bean 初始化阶段自动注册工具
     * <p>
     * 通过 ToolCallbacks.from() 自动扫描传入实例上所有 @Tool 注解方法，
     * 并把生成的 ToolCallback 加入列表。
     * 注意：返回的是数组，必须用 for 循环逐个 add。
     */
    @PostConstruct
    public void initTools() {
        toolCallbacks = new ArrayList<>();

        // 注册天气查询工具：自动扫描 WeatherTool 上 @Tool 注解的方法
        for (ToolCallback toolCallback : ToolCallbacks.from(weatherTool)) {
            toolCallbacks.add(toolCallback);
        }

        logger.info("已注册 {} 个工具", toolCallbacks.size());
    }

    /** 最大循环次数，可通过 maxLoopCount() 链式方法调整 */
    private int maxLoopCount = DEFAULT_MAX_LOOPS;

    /** 状态回调：每一轮循环开始时触发 */
    private Consumer<LoopStatus> onLoopStart;

    /** 状态回调：每一轮循环结束时触发（包括异常情况） */
    private Consumer<LoopStatus> onLoopEnd;

    /** 状态回调：每次完成一次 Tool 调用后触发 */
    private Consumer<ToolExecutionResult> onToolExecuted;

    /**
     * 执行 Agent 循环（无额外上下文）
     *
     * @param userInput 用户输入的自然语言
     * @return Agent 给出的最终响应文本
     */
    public String agentLoop(String userInput) {
        return agentLoop(userInput, new HashMap<>());
    }

    /**
     * 执行 Agent 循环（带上下文参数）
     * <p>
     * 工作流程：
     * 1. 构造初始消息列表（UserMessage）
     * 2. 调用模型 → 拿到 AssistantMessage
     * 3. 判断是否包含 Tool Call
     *    - 是：执行工具 → 把 ToolResponseMessage 加入历史 → 回到2
     *    - 否：当前响应即为最终答案，结束循环
     * 4. 超过 maxLoopCount 强制退出
     *
     * @param userInput     用户输入
     * @param contextParams 上下文参数（如用户ID、会话ID 等，便于工具或扩展使用）
     * @return Agent 给出的最终响应文本
     */
    public String agentLoop(String userInput, Map<String, Object> contextParams) {
        logger.info("Agent循环开始，用户输入: {}", userInput);

        // 1️⃣ 初始化消息列表，把用户输入放在第一条
        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage(userInput));

        String finalResponse = null;
        int loopCount = 0;

        while (true) {
            loopCount++;
            LoopStatus status = new LoopStatus(loopCount, messages, contextParams);

            // 检查循环次数限制，防止模型死循环
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
                // 2️⃣ 调用模型，让模型基于当前消息历史给出下一步动作
                AssistantMessage assistantMessage = callModel(messages);

                // 3️⃣ 把模型响应加入消息历史，保证下一轮上下文完整
                messages.add(assistantMessage);

                logger.info("模型响应: {}", assistantMessage.getText());

                // 4️⃣ 模型若决定调用工具，则进入工具执行分支
                if (hasToolCalls(assistantMessage)) {
                    logger.info("检测到 Tool Call，准备执行");

                    // 5️⃣ 执行模型请求的所有工具
                    List<AssistantMessage.ToolCall> toolCalls = assistantMessage.getToolCalls();
                    ToolResponseMessage toolResponseMessage = executeTools(toolCalls, contextParams);

                    // 触发工具执行回调
                    if (onToolExecuted != null) {
                        onToolExecuted.accept(new ToolExecutionResult(toolCalls, toolResponseMessage));
                    }

                    // 6️⃣ 把工具结果作为新一轮的输入加入消息历史
                    messages.add(toolResponseMessage);
                    logger.info("Tool 执行完成，结果已加入上下文");

                    // 进入下一轮，让模型基于工具结果继续推理
                    continue;
                }

                // 没有 Tool Call，意味着模型给出了最终答案，结束循环
                finalResponse = assistantMessage.getText();
                logger.info("Agent循环结束，最终响应: {}", finalResponse);
                break;

            } catch (Exception e) {
                finalResponse = "执行过程中发生错误: " + e.getMessage();
                logger.error("第 {} 轮循环执行异常", loopCount, e);
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
     * 调用底层模型获取一次响应
     * <p>
     * 通过 ChatClient 把消息历史和工具列表一并传给模型，
     * 模型会自主决定本轮是直接回答还是请求调用工具。
     *
     * @param messages 完整的消息历史
     * @return 模型生成的 AssistantMessage（可能包含 Tool Call）
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
     * 判断模型响应中是否包含 Tool Call
     *
     * @param message 模型响应消息
     * @return true 表示需要执行工具，false 表示已是最终答案
     */
    private boolean hasToolCalls(AssistantMessage message) {
        return !message.getToolCalls().isEmpty();
    }

    /**
     * 执行模型请求的所有 Tool Call
     * <p>
     * 遍历每个 ToolCall，按工具名匹配本地的 ToolCallback，调用并收集结果，
     * 最后把所有结果统一封装为一条 ToolResponseMessage。
     *
     * @param toolCalls     模型本轮请求调用的工具列表
     * @param contextParams 上下文参数（预留，便于未来工具感知会话信息）
     * @return 包含所有工具响应结果的消息
     */
    private ToolResponseMessage executeTools(List<AssistantMessage.ToolCall> toolCalls, Map<String, Object> contextParams) {

        List<ToolResponseMessage.ToolResponse> toolResponses = new ArrayList<>();

        for (AssistantMessage.ToolCall toolCall : toolCalls) {
            String toolCallId = toolCall.id();
            String toolName = toolCall.name();
            String arguments = toolCall.arguments();

            logger.info("执行 Tool: {}, ID: {}, 参数: {}", toolName, toolCallId, arguments);

            try {
                // 根据工具名找到对应的 ToolCallback 并执行
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
     * 根据工具名直接调用对应的 ToolCallback
     * <p>
     * 在 toolCallbacks 列表中按名称查找匹配的工具，
     * 找到后用模型给出的 JSON 参数字符串调用执行。
     *
     * @param toolName  工具名称（与 @Tool(name = ...) 一致）
     * @param arguments 模型生成的 JSON 参数字符串
     * @return 工具执行结果（字符串形式，通常是 JSON）
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

    // ==================== 链式配置方法 ====================

    /**
     * 设置最大循环次数（链式调用）
     *
     * @param maxLoopCount 最大循环次数
     * @return 当前实例
     */
    public AgentLoopEngine maxLoopCount(int maxLoopCount) {
        this.maxLoopCount = maxLoopCount;
        return this;
    }

    /**
     * 设置循环开始回调
     *
     * @param callback 每轮循环开始时执行的逻辑
     * @return 当前实例
     */
    public AgentLoopEngine onLoopStart(Consumer<LoopStatus> callback) {
        this.onLoopStart = callback;
        return this;
    }

    /**
     * 设置循环结束回调
     *
     * @param callback 每轮循环结束时执行的逻辑
     * @return 当前实例
     */
    public AgentLoopEngine onLoopEnd(Consumer<LoopStatus> callback) {
        this.onLoopEnd = callback;
        return this;
    }

    /**
     * 设置工具执行回调
     *
     * @param callback 工具执行完成后执行的逻辑
     * @return 当前实例
     */
    public AgentLoopEngine onToolExecuted(Consumer<ToolExecutionResult> callback) {
        this.onToolExecuted = callback;
        return this;
    }

    // ==================== 内部数据类 ====================

    /**
     * 单轮循环状态信息
     * <p>
     * 携带当前是第几轮、当前完整消息历史、上下文参数，
     * 用于回调中观察 / 埋点 / 调试。
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
     * 工具执行结果封装
     * <p>
     * 包含本轮模型请求的所有 ToolCall 以及工具执行后的统一响应消息，
     * 用于 onToolExecuted 回调，便于监控工具调用情况。
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
