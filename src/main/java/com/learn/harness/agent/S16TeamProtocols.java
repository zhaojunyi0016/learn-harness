package com.learn.harness.agent;

/**
 * 【第十六章】团队协议（Team Protocols）
 *
 * 本章在 S15（Agent 团队）的基础上增加"协议"概念。
 *
 * 问题：S15 中队员之间的通信是自由格式的，没有约束。
 * 这会导致：消息含义不明确、处理逻辑混乱、无法追踪状态。
 *
 * 解决方案：定义几种"协议消息"，每种有固定的格式和处理流程。
 *
 * 本章实现两个协议：
 * 1. 关机协议（Shutdown）：Lead 请求队员关机，队员确认后退出
 *    流程：Lead发送shutdown_request → 队员回复shutdown_response(approved/rejected)
 *
 * 2. 计划审批（Plan Approval）：队员提交计划，Lead 审批
 *    流程：队员发送plan_approval → Lead回复plan_approval_response(approved/rejected)
 *
 * 核心教学点：
 * - 消息类型化：不同类型的消息有不同的处理逻辑
 * - 状态机（FSM）：请求有 pending → approved/rejected 的状态流转
 * - 请求持久化：协议请求存为 JSON 文件，便于追踪和审计
 *
 * 运行方式：
 *   export DASHSCOPE_API_KEY="your-key"
 *   mvn compile exec:java -Dexec.mainClass="com.learn.harness.agent.S16TeamProtocols"
 */

import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public class S16TeamProtocols {

    // ==================== 配置 ====================

    private static final DashScopeClient client = new DashScopeClient();
    private static final Path TEAM_DIR = Path.of(CommonTools.WORKDIR, ".team");
    private static final Path INBOX_DIR = TEAM_DIR.resolve("inbox");
    private static final Path REQUESTS_DIR = TEAM_DIR.resolve("requests");

    private static final String SYSTEM_PROMPT =
            "你是一个团队主管，工作目录是 " + CommonTools.WORKDIR + "。\n" +
            "管理队员，使用协议进行正式通信（关机请求/计划审批）。";

    // ==================== 消息类型 ====================

    /** 支持的消息类型 */
    private static final Set<String> MSG_TYPES = Set.of(
            "message", "broadcast",
            "shutdown_request", "shutdown_response",
            "plan_approval", "plan_approval_response"
    );

    // ==================== 消息总线（增强版，支持类型化消息） ====================

    static class MessageBus {
        private final Path dir;
        MessageBus(Path dir) { this.dir = dir; try { Files.createDirectories(dir); } catch (IOException ignored) {} }

        /** 发送类型化消息 */
        String send(String from, String to, String content, String msgType) {
            if (!MSG_TYPES.contains(msgType)) return "错误: 无效消息类型 '" + msgType + "'";
            JsonObject msg = new JsonObject();
            msg.addProperty("type", msgType);
            msg.addProperty("from", from);
            msg.addProperty("content", content);
            msg.addProperty("timestamp", System.currentTimeMillis());
            try {
                Files.writeString(dir.resolve(to + ".jsonl"),
                        DashScopeClient.gson().toJson(msg) + "\n",
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                return "已发送 " + msgType + " 给 " + to;
            } catch (IOException e) { return "错误: " + e.getMessage(); }
        }

        List<JsonObject> readInbox(String name) {
            Path path = dir.resolve(name + ".jsonl");
            if (!Files.exists(path)) return List.of();
            try {
                List<JsonObject> messages = new ArrayList<>();
                for (String line : Files.readAllLines(path))
                    if (!line.isBlank()) messages.add(JsonParser.parseString(line).getAsJsonObject());
                Files.writeString(path, "");
                return messages;
            } catch (IOException e) { return List.of(); }
        }
    }

    // ==================== 协议请求管理 ====================

    /**
     * 协议请求存储
     * <p>
     * 每个协议请求存为独立 JSON 文件，便于追踪状态
     */
    static class ProtocolManager {
        private final Path requestsDir;

        ProtocolManager(Path dir) {
            this.requestsDir = dir;
            try { Files.createDirectories(dir); } catch (IOException ignored) {}
        }

        /** 创建协议请求 */
        String createRequest(String type, String from, String to, String content) {
            String reqId = UUID.randomUUID().toString().substring(0, 8);
            JsonObject request = new JsonObject();
            request.addProperty("id", reqId);
            request.addProperty("type", type);
            request.addProperty("from", from);
            request.addProperty("to", to);
            request.addProperty("content", content);
            request.addProperty("status", "pending");
            request.addProperty("created_at", System.currentTimeMillis());

            try {
                Files.writeString(requestsDir.resolve(reqId + ".json"),
                        DashScopeClient.gson().toJson(request));
                return "协议请求 " + reqId + " 已创建 (" + type + ")";
            } catch (IOException e) { return "错误: " + e.getMessage(); }
        }

        /** 响应协议请求 */
        String respond(String reqId, String decision) {
            try {
                Path path = requestsDir.resolve(reqId + ".json");
                if (!Files.exists(path)) return "错误: 请求 " + reqId + " 不存在";
                JsonObject req = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
                req.addProperty("status", decision);
                req.addProperty("resolved_at", System.currentTimeMillis());
                Files.writeString(path, DashScopeClient.gson().toJson(req));
                return "请求 " + reqId + " 已处理: " + decision;
            } catch (Exception e) { return "错误: " + e.getMessage(); }
        }

        /** 列出所有待处理的请求 */
        String listPending() {
            try (var stream = Files.list(requestsDir)) {
                List<String> pending = stream
                        .filter(f -> f.toString().endsWith(".json"))
                        .map(f -> { try { return JsonParser.parseString(Files.readString(f)).getAsJsonObject(); } catch (Exception e) { return null; } })
                        .filter(Objects::nonNull)
                        .filter(r -> "pending".equals(r.get("status").getAsString()))
                        .map(r -> r.get("id").getAsString() + " [" + r.get("type").getAsString() + "] " +
                                r.get("from").getAsString() + " → " + r.get("to").getAsString())
                        .toList();
                if (pending.isEmpty()) return "没有待处理的协议请求。";
                return "待处理请求:\n" + String.join("\n", pending);
            } catch (IOException e) { return "错误: " + e.getMessage(); }
        }
    }

    private static final MessageBus bus = new MessageBus(INBOX_DIR);
    private static final ProtocolManager protocol = new ProtocolManager(REQUESTS_DIR);

    // ==================== 工具定义 ====================

    private static List<Map<String, Object>> buildTools() {
        List<Map<String, Object>> tools = new ArrayList<>(CommonTools.allBasicToolDefs());
        tools.add(DashScopeClient.toolDefinition("send_message", "发送普通消息",
                Map.of("type", "object", "properties", Map.of(
                        "to", Map.of("type", "string"), "content", Map.of("type", "string")
                ), "required", List.of("to", "content"))));
        tools.add(DashScopeClient.toolDefinition("shutdown_request", "发送关机请求给队员",
                Map.of("type", "object", "properties", Map.of(
                        "to", Map.of("type", "string", "description", "目标队员名称"),
                        "reason", Map.of("type", "string", "description", "关机原因")
                ), "required", List.of("to"))));
        tools.add(DashScopeClient.toolDefinition("approve_request", "审批协议请求",
                Map.of("type", "object", "properties", Map.of(
                        "request_id", Map.of("type", "string"),
                        "decision", Map.of("type", "string", "description", "approved 或 rejected")
                ), "required", List.of("request_id", "decision"))));
        tools.add(DashScopeClient.toolDefinition("list_requests", "列出待处理的协议请求",
                Map.of("type", "object", "properties", Map.of())));
        tools.add(DashScopeClient.toolDefinition("read_inbox", "读取收件箱",
                Map.of("type", "object", "properties", Map.of())));
        return tools;
    }

    private static final List<Map<String, Object>> TOOLS = buildTools();

    // ==================== Agent 循环 ====================

    private static void agentLoop(List<Map<String, Object>> messages) {
        int maxTurns = 25;
        for (int turn = 0; turn < maxTurns; turn++) {
            List<JsonObject> inbox = bus.readInbox("lead");
            if (!inbox.isEmpty()) {
                messages.add(DashScopeClient.userMessage("[收件箱]\n" + DashScopeClient.gson().toJson(inbox)));
            }

            JsonObject response = client.createMessage(SYSTEM_PROMPT, messages, TOOLS, 4096);
            messages.add(DashScopeClient.assistantMessageToMap(response));
            if (!DashScopeClient.hasToolCalls(response)) return;

            JsonArray toolCalls = DashScopeClient.getToolCalls(response);
            for (int i = 0; i < toolCalls.size(); i++) {
                JsonObject toolCall = toolCalls.get(i).getAsJsonObject();
                String toolName = DashScopeClient.getToolName(toolCall);
                JsonObject args = DashScopeClient.getToolArguments(toolCall);
                String toolCallId = DashScopeClient.getToolCallId(toolCall);

                String result;
                try {
                    result = switch (toolName) {
                        case "send_message" -> bus.send("lead", args.get("to").getAsString(), args.get("content").getAsString(), "message");
                        case "shutdown_request" -> {
                            String to = args.get("to").getAsString();
                            String reason = args.has("reason") ? args.get("reason").getAsString() : "主管请求关机";
                            bus.send("lead", to, reason, "shutdown_request");
                            yield protocol.createRequest("shutdown", "lead", to, reason);
                        }
                        case "approve_request" -> protocol.respond(args.get("request_id").getAsString(), args.get("decision").getAsString());
                        case "list_requests" -> protocol.listPending();
                        case "read_inbox" -> DashScopeClient.gson().toJson(bus.readInbox("lead"));
                        default -> CommonTools.dispatch(toolName, args);
                    };
                } catch (Exception e) { result = "错误: " + e.getMessage(); }

                System.out.println("\033[33m[" + toolName + "] " + result.substring(0, Math.min(200, result.length())) + "\033[0m");
                messages.add(DashScopeClient.toolResultMessage(toolCallId, result));
            }
        }
    }

    // ==================== REPL ====================

    public static void main(String[] args) throws Exception {
        List<Map<String, Object>> history = new ArrayList<>();
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));

        System.out.println("=== S16 Team Protocols 演示 ===");
        System.out.println("协议类型: shutdown_request（关机） / plan_approval（计划审批）");
        System.out.println("输入 q 退出\n");

        while (true) {
            System.out.print("\033[36m[S16] >>> \033[0m");
            String input = reader.readLine();
            if (input == null || input.isBlank() || "q".equalsIgnoreCase(input.trim()) || "exit".equalsIgnoreCase(input.trim())) break;
            history.add(DashScopeClient.userMessage(input));
            agentLoop(history);
            System.out.println();
        }
    }
}
