package com.learn.harness.agent;

/**
 * 【第十五章】Agent 团队（Agent Teams）
 *
 * 本章教的是多 Agent 协作：多个 Agent 各自独立运行，通过消息通信。
 *
 * 架构设计：
 * - Lead（主 Agent）：和用户对话，负责分配工作
 * - Teammates（队员）：每个队员是独立线程中的 Agent 循环
 * - 通信方式：文件系统中的 JSONL 收件箱（.team/inbox/xxx.jsonl）
 *
 * 为什么用文件做收件箱？
 * - 简单：追加一行 JSON 就是发一条消息
 * - 持久：进程重启后消息还在
 * - 可观察：人可以直接打开文件看通信内容
 *
 * 核心教学点：
 * - 多 Agent 间不共享上下文（各有各的消息列表）
 * - 通信靠消息传递（不是共享内存）
 * - Lead 可以 spawn 队员、send 消息、broadcast 广播
 * - 队员完成后把结果发回给 Lead
 *
 * 运行方式：
 *   export DASHSCOPE_API_KEY="your-key"
 *   mvn compile exec:java -Dexec.mainClass="com.learn.harness.agent.S15AgentTeams"
 */

import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public class S15AgentTeams {

    // ==================== 配置 ====================

    private static final DashScopeClient client = new DashScopeClient();

    /** 团队目录 */
    private static final Path TEAM_DIR = Path.of(CommonTools.WORKDIR, ".team");
    private static final Path INBOX_DIR = TEAM_DIR.resolve("inbox");

    private static final String LEAD_SYSTEM =
            "你是一个团队主管，工作目录是 " + CommonTools.WORKDIR + "。\n" +
            "可以创建队员并分配任务。使用消息工具和队员通信。";

    // ==================== 消息总线 ====================

    /**
     * 消息总线：基于文件的简单消息传递
     * <p>
     * 为什么这么简单的设计就够了？
     * 因为教学场景下 Agent 数量少，消息量小，文件 I/O 完全能承受。
     * 生产环境可以换成 Redis/RabbitMQ 等。
     */
    static class MessageBus {
        private final Path dir;

        MessageBus(Path dir) {
            this.dir = dir;
            try { Files.createDirectories(dir); } catch (IOException ignored) {}
        }

        /** 发送消息到指定收件箱 */
        String send(String from, String to, String content) {
            JsonObject msg = new JsonObject();
            msg.addProperty("from", from);
            msg.addProperty("content", content);
            msg.addProperty("timestamp", System.currentTimeMillis());
            try {
                Files.writeString(dir.resolve(to + ".jsonl"),
                        DashScopeClient.gson().toJson(msg) + "\n",
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                return "已发送消息给 " + to;
            } catch (IOException e) {
                return "错误: " + e.getMessage();
            }
        }

        /** 读取并清空收件箱 */
        List<JsonObject> readInbox(String name) {
            Path path = dir.resolve(name + ".jsonl");
            if (!Files.exists(path)) return List.of();
            try {
                List<JsonObject> messages = new ArrayList<>();
                for (String line : Files.readAllLines(path)) {
                    if (!line.isBlank()) {
                        messages.add(JsonParser.parseString(line).getAsJsonObject());
                    }
                }
                Files.writeString(path, ""); // 清空收件箱
                return messages;
            } catch (IOException e) {
                return List.of();
            }
        }

        /** 广播消息给多个接收者 */
        String broadcast(String from, String content, List<String> names) {
            int count = 0;
            for (String name : names) {
                if (!name.equals(from)) { send(from, name, content); count++; }
            }
            return "已广播给 " + count + " 个队员";
        }
    }

    // ==================== 队员管理 ====================

    /**
     * 队员信息
     */
    static class Teammate {
        final String name;
        final String role;
        volatile String status; // working / idle

        Teammate(String name, String role) {
            this.name = name;
            this.role = role;
            this.status = "working";
        }
    }

    static class TeamManager {
        private final List<Teammate> members = new CopyOnWriteArrayList<>();
        private final MessageBus bus;

        TeamManager(MessageBus bus) { this.bus = bus; }

        /** 创建并启动一个队员 */
        String spawn(String name, String role, String prompt) {
            // 检查是否已存在
            for (Teammate m : members) {
                if (m.name.equals(name) && "working".equals(m.status)) {
                    return "错误: '" + name + "' 正在工作中";
                }
            }

            Teammate teammate = new Teammate(name, role);
            members.add(teammate);

            // 在独立线程中运行队员的 Agent 循环
            new Thread(() -> teammateLoop(teammate, prompt), "teammate-" + name).start();
            return "已创建队员 '" + name + "' (角色: " + role + ")";
        }

        /** 队员的 Agent 循环 */
        private void teammateLoop(Teammate teammate, String prompt) {
            String systemPrompt = "你是 '" + teammate.name + "'，角色: " + teammate.role +
                    "，工作目录: " + CommonTools.WORKDIR + "。\n完成任务后用 send_message 把结果发给 lead。";

            List<Map<String, Object>> messages = new ArrayList<>();
            messages.add(DashScopeClient.userMessage(prompt));

            List<Map<String, Object>> tools = buildTeammateTools();

            for (int turn = 0; turn < 20; turn++) {
                // 检查收件箱
                List<JsonObject> inbox = bus.readInbox(teammate.name);
                for (JsonObject msg : inbox) {
                    messages.add(DashScopeClient.userMessage("[收件箱] " + DashScopeClient.gson().toJson(msg)));
                }

                JsonObject response;
                try {
                    response = client.createMessage(systemPrompt, messages, tools, 4096);
                } catch (Exception e) { break; }

                messages.add(DashScopeClient.assistantMessageToMap(response));
                if (!DashScopeClient.hasToolCalls(response)) break;

                JsonArray toolCalls = DashScopeClient.getToolCalls(response);
                for (int i = 0; i < toolCalls.size(); i++) {
                    JsonObject toolCall = toolCalls.get(i).getAsJsonObject();
                    String toolName = DashScopeClient.getToolName(toolCall);
                    JsonObject arguments = DashScopeClient.getToolArguments(toolCall);
                    String toolCallId = DashScopeClient.getToolCallId(toolCall);

                    String result;
                    try {
                        result = switch (toolName) {
                            case "send_message" -> bus.send(teammate.name,
                                    arguments.get("to").getAsString(), arguments.get("content").getAsString());
                            default -> CommonTools.dispatch(toolName, arguments);
                        };
                    } catch (Exception e) { result = "错误: " + e.getMessage(); }

                    System.out.println("  \033[90m[" + teammate.name + "/" + toolName + "] " +
                            result.substring(0, Math.min(100, result.length())) + "\033[0m");
                    messages.add(DashScopeClient.toolResultMessage(toolCallId, result));
                }
            }
            teammate.status = "idle";
        }

        /** 列出所有队员 */
        String listAll() {
            if (members.isEmpty()) return "暂无队员。";
            StringBuilder sb = new StringBuilder("团队成员:\n");
            for (Teammate m : members) {
                sb.append("  ").append(m.name).append(" (").append(m.role).append("): ").append(m.status).append("\n");
            }
            return sb.toString().trim();
        }

        List<String> memberNames() {
            return members.stream().map(m -> m.name).toList();
        }
    }

    private static final MessageBus bus = new MessageBus(INBOX_DIR);
    private static final TeamManager team = new TeamManager(bus);

    // ==================== 工具定义 ====================

    /** 队员可用的工具（基础工具 + send_message） */
    private static List<Map<String, Object>> buildTeammateTools() {
        List<Map<String, Object>> tools = new ArrayList<>(CommonTools.allBasicToolDefs());
        tools.add(DashScopeClient.toolDefinition("send_message", "给其他队员或 lead 发消息",
                Map.of("type", "object", "properties", Map.of(
                        "to", Map.of("type", "string", "description", "接收者名称"),
                        "content", Map.of("type", "string", "description", "消息内容")
                ), "required", List.of("to", "content"))));
        return tools;
    }

    /** Lead 可用的工具 */
    private static List<Map<String, Object>> buildLeadTools() {
        List<Map<String, Object>> tools = new ArrayList<>(CommonTools.allBasicToolDefs());
        tools.add(DashScopeClient.toolDefinition("spawn_teammate", "创建一个队员并分配任务",
                Map.of("type", "object", "properties", Map.of(
                        "name", Map.of("type", "string", "description", "队员名称"),
                        "role", Map.of("type", "string", "description", "队员角色"),
                        "prompt", Map.of("type", "string", "description", "分配给队员的任务描述")
                ), "required", List.of("name", "role", "prompt"))));
        tools.add(DashScopeClient.toolDefinition("list_teammates", "列出所有队员状态",
                Map.of("type", "object", "properties", Map.of())));
        tools.add(DashScopeClient.toolDefinition("send_message", "给队员发消息",
                Map.of("type", "object", "properties", Map.of(
                        "to", Map.of("type", "string", "description", "接收者名称"),
                        "content", Map.of("type", "string", "description", "消息内容")
                ), "required", List.of("to", "content"))));
        tools.add(DashScopeClient.toolDefinition("read_inbox", "读取 lead 的收件箱",
                Map.of("type", "object", "properties", Map.of())));
        tools.add(DashScopeClient.toolDefinition("broadcast", "给所有队员广播消息",
                Map.of("type", "object", "properties", Map.of(
                        "content", Map.of("type", "string", "description", "广播内容")
                ), "required", List.of("content"))));
        return tools;
    }

    private static final List<Map<String, Object>> LEAD_TOOLS = buildLeadTools();

    // ==================== Lead Agent 循环 ====================

    private static void agentLoop(List<Map<String, Object>> messages) {
        int maxTurns = 25;
        for (int turn = 0; turn < maxTurns; turn++) {
            // 检查 lead 的收件箱
            List<JsonObject> inbox = bus.readInbox("lead");
            if (!inbox.isEmpty()) {
                messages.add(DashScopeClient.userMessage(
                        "[收件箱]\n" + DashScopeClient.gson().toJson(inbox)));
            }

            JsonObject response = client.createMessage(LEAD_SYSTEM, messages, LEAD_TOOLS, 4096);
            messages.add(DashScopeClient.assistantMessageToMap(response));

            if (!DashScopeClient.hasToolCalls(response)) return;

            JsonArray toolCalls = DashScopeClient.getToolCalls(response);
            for (int i = 0; i < toolCalls.size(); i++) {
                JsonObject toolCall = toolCalls.get(i).getAsJsonObject();
                String toolName = DashScopeClient.getToolName(toolCall);
                JsonObject arguments = DashScopeClient.getToolArguments(toolCall);
                String toolCallId = DashScopeClient.getToolCallId(toolCall);

                String result;
                try {
                    result = switch (toolName) {
                        case "spawn_teammate" -> team.spawn(
                                arguments.get("name").getAsString(),
                                arguments.get("role").getAsString(),
                                arguments.get("prompt").getAsString());
                        case "list_teammates" -> team.listAll();
                        case "send_message" -> bus.send("lead",
                                arguments.get("to").getAsString(), arguments.get("content").getAsString());
                        case "read_inbox" -> DashScopeClient.gson().toJson(bus.readInbox("lead"));
                        case "broadcast" -> bus.broadcast("lead",
                                arguments.get("content").getAsString(), team.memberNames());
                        default -> CommonTools.dispatch(toolName, arguments);
                    };
                } catch (Exception e) { result = "错误: " + e.getMessage(); }

                System.out.println("\033[33m[" + toolName + "] " +
                        result.substring(0, Math.min(200, result.length())) + "\033[0m");
                messages.add(DashScopeClient.toolResultMessage(toolCallId, result));
            }
        }
    }

    // ==================== REPL ====================

    public static void main(String[] args) throws Exception {
        List<Map<String, Object>> history = new ArrayList<>();
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));

        System.out.println("=== S15 Agent Teams 演示 ===");
        System.out.println("你是 Lead，可以创建队员并分配任务");
        System.out.println("命令: /team 查看团队 | /inbox 查看收件箱");
        System.out.println("输入 q 退出\n");

        while (true) {
            System.out.print("\033[36m[S15] >>> \033[0m");
            String input = reader.readLine();
            if (input == null || input.isBlank() ||
                    "q".equalsIgnoreCase(input.trim()) || "exit".equalsIgnoreCase(input.trim())) break;
            if ("/team".equals(input.trim())) { System.out.println(team.listAll() + "\n"); continue; }
            if ("/inbox".equals(input.trim())) { System.out.println(DashScopeClient.gson().toJson(bus.readInbox("lead")) + "\n"); continue; }

            history.add(DashScopeClient.userMessage(input));
            agentLoop(history);
            System.out.println();
        }
    }
}
