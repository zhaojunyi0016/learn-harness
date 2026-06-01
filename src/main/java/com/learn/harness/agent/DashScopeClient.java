package com.learn.harness.agent;

import com.google.gson.*;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * DashScope 模型调用客户端（共享基础设施）
 * <p>
 * 这个类是所有教程文件（S01~S19）共用的模型调用工具。
 * 它封装了与 DashScope OpenAI 兼容接口的 HTTP 通信细节，
 * 让教程代码只需关注业务逻辑（循环、工具调用等），不用关心 HTTP 和 JSON 拼装。
 * <p>
 * 为什么用 OpenAI 兼容接口而不是 DashScope 原生接口？
 * 因为兼容接口的消息格式（role/content/tool_calls）是行业标准，
 * 学会了这套格式，换任何模型供应商都能用。
 * <p>
 * 使用前需设置环境变量：
 *   export DASHSCOPE_API_KEY="sk-xxx"
 */
public class DashScopeClient {

    /** DashScope OpenAI 兼容接口地址 */
    private static final String BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";

    /** API Key，从环境变量读取 */
    private final String apiKey;

    /** 模型名称，默认 qwen-plus */
    private final String model;

    /** Java 11+ 内置的 HTTP 客户端 */
    private final HttpClient httpClient;

    /** JSON 工具，带格式化输出方便调试 */
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * 构造客户端，从环境变量读取配置
     * <p>
     * 为什么从环境变量读？
     * 因为教程文件是独立运行的（不走 Spring Boot），无法读取 application.yml，
     * 环境变量是最简单的跨文件共享配置方式。
     */
    public DashScopeClient() {
        this.apiKey = System.getenv("DASHSCOPE_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            throw new RuntimeException("请设置环境变量 DASHSCOPE_API_KEY（在 application.yml 中可以找到你的 key）");
        }

        // 允许通过环境变量覆盖模型，默认用 qwen-plus
        String envModel = System.getenv("DASHSCOPE_MODEL");
        this.model = (envModel != null && !envModel.isEmpty()) ? envModel : "qwen-plus";

        this.httpClient = HttpClient.newHttpClient();
    }

    /**
     * 向模型发送一次对话请求
     * <p>
     * 这是最核心的方法：把系统提示词、对话历史、工具定义打包发给模型，
     * 模型返回要么是文本回答，要么是"我想调用某个工具"的指令。
     *
     * @param system   系统提示词（告诉模型它的角色和规则）
     * @param messages 对话历史（user/assistant/tool 消息列表）
     * @param tools    工具定义列表（可以为 null 表示不提供工具）
     * @param maxTokens 最大生成 token 数
     * @return 模型响应的 JSON 对象
     */
    public JsonObject createMessage(String system, List<Map<String, Object>> messages,
                                    List<Map<String, Object>> tools, int maxTokens) {
        // 构建请求体
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("max_tokens", maxTokens);

        // 构建消息列表：先放 system 消息，再放对话历史
        JsonArray messagesArray = new JsonArray();

        // system 消息单独作为第一条
        if (system != null && !system.isEmpty()) {
            JsonObject systemMsg = new JsonObject();
            systemMsg.addProperty("role", "system");
            systemMsg.addProperty("content", system);
            messagesArray.add(systemMsg);
        }

        // 对话历史
        for (Map<String, Object> msg : messages) {
            messagesArray.add(GSON.toJsonTree(msg));
        }
        body.add("messages", messagesArray);

        // 工具定义（如果有的话）
        if (tools != null && !tools.isEmpty()) {
            JsonArray toolsArray = new JsonArray();
            for (Map<String, Object> tool : tools) {
                toolsArray.add(GSON.toJsonTree(tool));
            }
            body.add("tools", toolsArray);
        }

        // 发送 HTTP 请求
        String jsonBody = GSON.toJson(body);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new RuntimeException("DashScope API 错误 (" + response.statusCode() + "): " + response.body());
            }

            return JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("HTTP 请求失败: " + e.getMessage(), e);
        }
    }

    // ==================== 响应解析工具方法 ====================
    // 以下方法帮助从模型响应中提取关键信息，避免每个教程文件都写一遍解析逻辑。

    /**
     * 获取模型的停止原因
     * <p>
     * "stop" 表示模型说完了（最终回答）
     * "tool_calls" 表示模型想调用工具
     *
     * @param response API 响应
     * @return 停止原因字符串
     */
    public static String getFinishReason(JsonObject response) {
        try {
            return response.getAsJsonArray("choices")
                    .get(0).getAsJsonObject()
                    .get("finish_reason").getAsString();
        } catch (Exception e) {
            return "stop";
        }
    }

    /**
     * 判断模型是否请求调用工具
     *
     * @param response API 响应
     * @return true 表示模型想调用工具
     */
    public static boolean hasToolCalls(JsonObject response) {
        return "tool_calls".equals(getFinishReason(response));
    }

    /**
     * 提取模型回复的文本内容
     *
     * @param response API 响应
     * @return 模型回复的纯文本（如果没有文本则返回空字符串）
     */
    public static String extractText(JsonObject response) {
        try {
            JsonObject message = getAssistantMessage(response);
            if (message.has("content") && !message.get("content").isJsonNull()) {
                return message.get("content").getAsString();
            }
        } catch (Exception ignored) {}
        return "";
    }

    /**
     * 获取 assistant 消息对象（包含 content 和/或 tool_calls）
     * <p>
     * 为什么要单独提取这个？
     * 因为在 Agent 循环中，需要把 assistant 的完整消息（包括 tool_calls）
     * 追加到对话历史里，下一轮发给模型时它才知道自己之前说了什么。
     *
     * @param response API 响应
     * @return assistant 消息的 JSON 对象
     */
    public static JsonObject getAssistantMessage(JsonObject response) {
        return response.getAsJsonArray("choices")
                .get(0).getAsJsonObject()
                .getAsJsonObject("message");
    }

    /**
     * 获取模型请求调用的工具列表
     * <p>
     * 每个 tool_call 包含：
     * - id: 唯一标识（回传结果时需要用）
     * - function.name: 要调用的工具名
     * - function.arguments: 工具参数（JSON 字符串）
     *
     * @param response API 响应
     * @return tool_calls 数组（如果没有则返回空数组）
     */
    public static JsonArray getToolCalls(JsonObject response) {
        try {
            JsonObject message = getAssistantMessage(response);
            if (message.has("tool_calls")) {
                return message.getAsJsonArray("tool_calls");
            }
        } catch (Exception ignored) {}
        return new JsonArray();
    }

    /**
     * 从单个 tool_call 中提取工具名
     */
    public static String getToolName(JsonObject toolCall) {
        return toolCall.getAsJsonObject("function").get("name").getAsString();
    }

    /**
     * 从单个 tool_call 中提取参数（解析为 JsonObject）
     */
    public static JsonObject getToolArguments(JsonObject toolCall) {
        String args = toolCall.getAsJsonObject("function").get("arguments").getAsString();
        return JsonParser.parseString(args).getAsJsonObject();
    }

    /**
     * 从单个 tool_call 中提取 ID（回传结果时需要）
     */
    public static String getToolCallId(JsonObject toolCall) {
        return toolCall.get("id").getAsString();
    }

    // ==================== 消息构建工具方法 ====================
    // 这些方法帮助构建符合 OpenAI 格式的消息，减少重复代码。

    /**
     * 构建 user 消息
     */
    public static Map<String, Object> userMessage(String content) {
        return Map.of("role", "user", "content", content);
    }

    /**
     * 构建 tool 结果消息
     * <p>
     * 为什么需要 tool_call_id？
     * 因为模型可能一次请求调用多个工具，需要通过 id 对应哪个结果是哪个调用的。
     *
     * @param toolCallId 对应的 tool_call 的 ID
     * @param content    工具执行结果
     * @return 符合格式的 tool 消息
     */
    public static Map<String, Object> toolResultMessage(String toolCallId, String content) {
        return Map.of("role", "tool", "tool_call_id", toolCallId, "content", content);
    }

    /**
     * 把 assistant 消息转为 Map 格式（方便加入对话历史列表）
     */
    public static Map<String, Object> assistantMessageToMap(JsonObject response) {
        JsonObject msg = getAssistantMessage(response);
        // 用 Gson 转成 Map，保留所有字段（content、tool_calls 等）
        return GSON.fromJson(msg, Map.class);
    }

    /**
     * 构建工具定义（OpenAI function calling 格式）
     * <p>
     * 为什么用这个格式？
     * 这是 OpenAI 定义的标准格式，DashScope 兼容接口也用这套。
     * 模型通过 description 理解工具用途，通过 parameters 理解参数要求。
     *
     * @param name        工具名称
     * @param description 工具描述（模型靠这个判断什么时候该用这个工具）
     * @param parameters  参数 schema（JSON Schema 格式）
     * @return 完整的工具定义 Map
     */
    public static Map<String, Object> toolDefinition(String name, String description, Map<String, Object> parameters) {
        return Map.of(
                "type", "function",
                "function", Map.of(
                        "name", name,
                        "description", description,
                        "parameters", parameters
                )
        );
    }

    /** 获取 Gson 实例（供外部使用） */
    public static Gson gson() {
        return GSON;
    }
}
