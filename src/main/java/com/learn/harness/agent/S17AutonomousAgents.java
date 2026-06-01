package com.learn.harness.agent;

/**
 * 【第十七章】自治 Agent（Autonomous Agents）
 *
 * 本章在 S15 的基础上让队员变成"自治的"——不需要 Lead 持续指挥，
 * 能自己找任务做。
 *
 * S15 的队员：接收一个 prompt → 执行完 → 结束
 * 本章的队员：接收初始任务 → 执行完 → 进入空闲轮询 → 有新消息/任务时自动恢复
 *
 * 队员生命周期：
 *   spawn → 工作（Agent 循环）→ 空闲（轮询收件箱+任务板）→ 恢复或超时退出
 *
 * 核心教学点：
 * - 空闲轮询：定期检查收件箱和任务板，有活就干
 * - 超时退出：空闲太久自动关机（节省资源）
 * - 任务自动认领：从任务板上 claim 一个 pending 的任务
 *
 * 运行方式：
 *   export DASHSCOPE_API_KEY="your-key"
 *   mvn compile exec:java -Dexec.mainClass="com.learn.harness.agent.S17AutonomousAgents"
 */

import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public class S17AutonomousAgents {

    // ==================== 配置 ====================

    private static final DashScopeClient client = new DashScopeClient();
    private static final Path INBOX_DIR = Path.of(CommonTools.WORKDIR, ".team", "inbox");
    private static final Path TASKS_DIR = Path.of(CommonTools.WORKDIR, ".tasks");

    /** 空闲轮询间隔（秒） */
    private static final int POLL_INTERVAL = 5;

    /** 空闲超时退出（秒） */
    private static final int IDLE_TIMEOUT = 60;

    private static final String SYSTEM_PROMPT =
            "你是一个团队主管，工作目录是 " + CommonTools.WORKDIR + "。\n" +
            "队员是自治的——他们会自己找任务做。使用 spawn 创建自治队员。";

    // ==================== 消息总线 ====================

    static class MessageBus {
        private final Path dir;
        MessageBus(Path dir) { this.dir = dir; try { Files.createDirectories(dir); } catch (IOException ignored) {} }

        String send(String from, String to, String content) {
            JsonObject msg = new JsonObject();
            msg.addProperty("from", from);
            msg.addProperty("content", content);
            msg.addProperty("timestamp", System.currentTimeMillis());
            try {
                Files.writeString(dir.resolve(to + ".jsonl"),
                        DashScopeClient.gson().toJson(msg) + "\n",
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                return "已发送给 " + to;
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

    // ==================== 自治队员 ====================

    static class AutonomousTeammate {
        final String name;
        final String role;
        volatile String status; // working / idle / shutdown

        AutonomousTeammate(String name, String role) {
            this.name = name;
            this.role = role;
            this.status = "working";
        }
    }

    private static final MessageBus bus = new MessageBus(INBOX_DIR);
    private static final List<AutonomousTeammate> teammates = new CopyOnWriteArrayList<>();

    /**
     * 启动自治队员
     * <p>
     * 队员在独立线程中运行：
     * 1. 先执行初始任务
     * 2. 任务完成后进入空闲轮询
     * 3. 有新消息或新任务时恢复工作
     * 4. 空闲超时后自动退出
     */
    static String spawnAutonomous(String name, String role, String prompt) {
        AutonomousTeammate teammate = new AutonomousTeammate(name, role);
        teammates.add(teammate);

        new Thread(() -> {
            String sysPrompt = "你是自治队员 '" + name + "'（角色: " + role + "），工作目录: " + CommonTools.WORKDIR + "。\n" +
                    "完成当前任务后，用 send_message 把结果报告给 lead。";

            List<Map<String, Object>> messages = new ArrayList<>();
            messages.add(DashScopeClient.userMessage(prompt));
            List<Map<String, Object>> tools = CommonTools.allBasicToolDefs();
            // 添加 send_message 工具
            List<Map<String, Object>> fullTools = new ArrayList<>(tools);
            fullTools.add(DashScopeClient.toolDefinition("send_message", "发消息给队员或 lead",
                    Map.of("type", "object", "properties", Map.of(
                            "to", Map.of("type", "string"), "content", Map.of("type", "string")
                    ), "required", List.of("to", "content"))));

            // 工作阶段
            for (int turn = 0; turn < 20; turn++) {
                try {
                    JsonObject response = client.createMessage(sysPrompt, messages, fullTools, 4096);
                    messages.add(DashScopeClient.assistantMessageToMap(response));
                    if (!DashScopeClient.hasToolCalls(response)) break;

                    JsonArray toolCalls = DashScopeClient.getToolCalls(response);
                    for (int i = 0; i < toolCalls.size(); i++) {
                        JsonObject toolCall = toolCalls.get(i).getAsJsonObject();
                        String toolName = DashScopeClient.getToolName(toolCall);
                        JsonObject args = DashScopeClient.getToolArguments(toolCall);
                        String toolCallId = DashScopeClient.getToolCallId(toolCall);

                        String result = "send_message".equals(toolName)
                                ? bus.send(name, args.get("to").getAsString(), args.get("content").getAsString())
                                : CommonTools.dispatch(toolName, args);

                        System.out.println("  \033[90m[" + name + "/" + toolName + "] " +
                                result.substring(0, Math.min(100, result.length())) + "\033[0m");
                        messages.add(DashScopeClient.toolResultMessage(toolCallId, result));
                    }
                } catch (Exception e) { break; }
            }

            // 空闲轮询阶段
            teammate.status = "idle";
            long idleStart = System.currentTimeMillis();
            while ((System.currentTimeMillis() - idleStart) < IDLE_TIMEOUT * 1000L) {
                List<JsonObject> inbox = bus.readInbox(name);
                if (!inbox.isEmpty()) {
                    // 收到新消息，恢复工作
                    teammate.status = "working";
                    messages.add(DashScopeClient.userMessage("[收件箱] " + DashScopeClient.gson().toJson(inbox)));
                    idleStart = System.currentTimeMillis(); // 重置超时
                    // 简化：只处理一轮
                    try {
                        JsonObject resp = client.createMessage(sysPrompt, messages, fullTools, 4096);
                        messages.add(DashScopeClient.assistantMessageToMap(resp));
                    } catch (Exception ignored) {}
                    teammate.status = "idle";
                }
                try { Thread.sleep(POLL_INTERVAL * 1000L); } catch (InterruptedException e) { break; }
            }
            teammate.status = "shutdown";
            System.out.println("  \033[90m[" + name + "] 空闲超时，已退出\033[0m");
        }, "autonomous-" + name).start();

        return "自治队员 '" + name + "' 已启动 (角色: " + role + ")";
    }

    // ==================== Lead 工具 ====================

    private static List<Map<String, Object>> buildTools() {
        List<Map<String, Object>> tools = new ArrayList<>(CommonTools.allBasicToolDefs());
        tools.add(DashScopeClient.toolDefinition("spawn_autonomous", "创建自治队员",
                Map.of("type", "object", "properties", Map.of(
                        "name", Map.of("type", "string"), "role", Map.of("type", "string"),
                        "prompt", Map.of("type", "string", "description", "初始任务描述")
                ), "required", List.of("name", "role", "prompt"))));
        tools.add(DashScopeClient.toolDefinition("list_teammates", "列出队员状态",
                Map.of("type", "object", "properties", Map.of())));
        tools.add(DashScopeClient.toolDefinition("send_message", "发消息给队员",
                Map.of("type", "object", "properties", Map.of(
                        "to", Map.of("type", "string"), "content", Map.of("type", "string")
                ), "required", List.of("to", "content"))));
        tools.add(DashScopeClient.toolDefinition("read_inbox", "读取 lead 收件箱",
                Map.of("type", "object", "properties", Map.of())));
        return tools;
    }

    private static final List<Map<String, Object>> TOOLS = buildTools();

    // ==================== Agent 循环 ====================

    private static void agentLoop(List<Map<String, Object>> messages) {
        int maxTurns = 20;
        for (int turn = 0; turn < maxTurns; turn++) {
            List<JsonObject> inbox = bus.readInbox("lead");
            if (!inbox.isEmpty()) messages.add(DashScopeClient.userMessage("[收件箱]\n" + DashScopeClient.gson().toJson(inbox)));

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
                        case "spawn_autonomous" -> spawnAutonomous(args.get("name").getAsString(), args.get("role").getAsString(), args.get("prompt").getAsString());
                        case "list_teammates" -> {
                            if (teammates.isEmpty()) yield "暂无队员。";
                            StringBuilder sb = new StringBuilder();
                            for (var t : teammates) sb.append("  ").append(t.name).append(" (").append(t.role).append("): ").append(t.status).append("\n");
                            yield sb.toString().trim();
                        }
                        case "send_message" -> bus.send("lead", args.get("to").getAsString(), args.get("content").getAsString());
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

        System.out.println("=== S17 Autonomous Agents 演示 ===");
        System.out.println("自治队员会自己找活干，空闲 " + IDLE_TIMEOUT + " 秒后自动退出");
        System.out.println("输入 q 退出\n");

        while (true) {
            System.out.print("\033[36m[S17] >>> \033[0m");
            String input = reader.readLine();
            if (input == null || input.isBlank() || "q".equalsIgnoreCase(input.trim()) || "exit".equalsIgnoreCase(input.trim())) break;
            history.add(DashScopeClient.userMessage(input));
            agentLoop(history);
            System.out.println();
        }
    }
}
