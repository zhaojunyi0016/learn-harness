package com.learn.harness.agent;

/**
 * 【第十二章】任务系统（Task System）
 *
 * 本章教的是"持久化任务管理"——让 Agent 的任务状态存活于磁盘上。
 *
 * 和 S03 的 Todo 有什么区别？
 * - S03 的 Todo 只存在于内存中，对话结束就没了
 * - 本章的 Task 存为 JSON 文件，上下文压缩后任务状态不丢失
 * - 支持任务间依赖关系（A 完成后 B 才能开始）
 *
 * 核心设计：
 * - 每个任务是一个 JSON 文件（.tasks/task_1.json）
 * - 状态：pending → in_progress → completed（或 deleted）
 * - 依赖：blockedBy（被哪些任务阻塞）/ blocks（阻塞哪些任务）
 * - 完成一个任务时，自动解除它对其他任务的阻塞
 *
 * 核心教学点：
 * - "磁盘上的状态"比"上下文中的状态"更持久
 * - 任务依赖图让 Agent 知道该先做什么
 * - 状态流转要有约束（不能从 completed 回到 pending）
 *
 * 运行方式：
 *   export DASHSCOPE_API_KEY="your-key"
 *   mvn compile exec:java -Dexec.mainClass="com.learn.harness.agent.S12TaskSystem"
 */

import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public class S12TaskSystem {

    // ==================== 配置 ====================

    private static final DashScopeClient client = new DashScopeClient();

    /** 任务文件存储目录 */
    private static final Path TASKS_DIR = Path.of(CommonTools.WORKDIR, ".tasks");

    private static final String SYSTEM_PROMPT =
            "你是一个编程助手，工作目录是 " + CommonTools.WORKDIR + "。\n" +
            "使用 task 工具来规划和追踪工作。先创建任务列表，再逐个执行。";

    // ==================== 任务管理器 ====================

    /**
     * 任务管理器：负责任务的 CRUD 和依赖关系
     */
    static class TaskManager {
        private final Path dir;
        private int nextId;

        TaskManager(Path dir) {
            this.dir = dir;
            try { Files.createDirectories(dir); } catch (IOException ignored) {}
            this.nextId = findMaxId() + 1;
        }

        /** 找到当前最大的任务 ID */
        private int findMaxId() {
            try (var stream = Files.list(dir)) {
                return stream.filter(f -> f.getFileName().toString().matches("task_\\d+\\.json"))
                        .mapToInt(f -> {
                            String name = f.getFileName().toString();
                            return Integer.parseInt(name.replaceAll("task_(\\d+)\\.json", "$1"));
                        })
                        .max().orElse(0);
            } catch (IOException e) { return 0; }
        }

        /** 创建新任务 */
        String create(String subject, String description) {
            JsonObject task = new JsonObject();
            task.addProperty("id", nextId);
            task.addProperty("subject", subject);
            task.addProperty("description", description != null ? description : "");
            task.addProperty("status", "pending");
            task.add("blockedBy", new JsonArray());
            task.add("blocks", new JsonArray());

            try {
                save(task);
                nextId++;
                return "任务 #" + task.get("id").getAsInt() + " 已创建: " + subject;
            } catch (IOException e) {
                return "错误: " + e.getMessage();
            }
        }

        /** 更新任务状态 */
        String update(int taskId, String status, String owner) {
            try {
                JsonObject task = load(taskId);
                if (status != null) {
                    if (!List.of("pending", "in_progress", "completed", "deleted").contains(status)) {
                        return "错误: 无效状态 '" + status + "'（允许: pending/in_progress/completed/deleted）";
                    }
                    task.addProperty("status", status);
                    // 完成任务时自动解除对其他任务的阻塞
                    if ("completed".equals(status)) {
                        clearBlockedBy(taskId);
                    }
                }
                if (owner != null) task.addProperty("owner", owner);
                save(task);
                return "任务 #" + taskId + " 已更新: status=" + task.get("status").getAsString();
            } catch (Exception e) {
                return "错误: " + e.getMessage();
            }
        }

        /** 获取任务详情 */
        String get(int taskId) {
            try {
                return new GsonBuilder().setPrettyPrinting().create().toJson(load(taskId));
            } catch (Exception e) {
                return "错误: " + e.getMessage();
            }
        }

        /** 列出所有任务 */
        String listAll() {
            try (var stream = Files.list(dir)) {
                List<JsonObject> tasks = stream
                        .filter(f -> f.getFileName().toString().matches("task_\\d+\\.json"))
                        .sorted()
                        .map(f -> { try { return JsonParser.parseString(Files.readString(f)).getAsJsonObject(); } catch (Exception e) { return null; } })
                        .filter(Objects::nonNull)
                        .toList();

                if (tasks.isEmpty()) return "暂无任务。使用 task_create 创建新任务。";
                StringBuilder sb = new StringBuilder("当前任务列表:\n");
                for (JsonObject t : tasks) {
                    String status = t.get("status").getAsString();
                    String marker = switch (status) {
                        case "pending" -> "[ ]";
                        case "in_progress" -> "[>]";
                        case "completed" -> "[x]";
                        case "deleted" -> "[-]";
                        default -> "[?]";
                    };
                    sb.append(marker).append(" #").append(t.get("id").getAsInt())
                            .append(": ").append(t.get("subject").getAsString());
                    if (t.has("blockedBy") && t.getAsJsonArray("blockedBy").size() > 0) {
                        sb.append(" (被 ").append(t.getAsJsonArray("blockedBy")).append(" 阻塞)");
                    }
                    sb.append("\n");
                }
                return sb.toString().trim();
            } catch (IOException e) {
                return "错误: " + e.getMessage();
            }
        }

        private JsonObject load(int taskId) throws Exception {
            Path path = dir.resolve("task_" + taskId + ".json");
            if (!Files.exists(path)) throw new Exception("任务 #" + taskId + " 不存在");
            return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        }

        private void save(JsonObject task) throws IOException {
            Path path = dir.resolve("task_" + task.get("id").getAsInt() + ".json");
            Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(task));
        }

        /** 当任务完成时，从其他任务的 blockedBy 中移除它 */
        private void clearBlockedBy(int completedId) {
            try (var stream = Files.list(dir)) {
                stream.filter(f -> f.getFileName().toString().matches("task_\\d+\\.json")).forEach(f -> {
                    try {
                        JsonObject t = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
                        JsonArray blocked = t.has("blockedBy") ? t.getAsJsonArray("blockedBy") : new JsonArray();
                        JsonArray newBlocked = new JsonArray();
                        for (var el : blocked) {
                            if (el.getAsInt() != completedId) newBlocked.add(el);
                        }
                        if (newBlocked.size() != blocked.size()) {
                            t.add("blockedBy", newBlocked);
                            save(t);
                        }
                    } catch (Exception ignored) {}
                });
            } catch (IOException ignored) {}
        }
    }

    private static final TaskManager taskMgr = new TaskManager(TASKS_DIR);

    // ==================== 工具定义 =======================

    private static List<Map<String, Object>> buildTools() {
        List<Map<String, Object>> tools = new ArrayList<>(CommonTools.allBasicToolDefs());
        tools.add(DashScopeClient.toolDefinition("task_create", "创建新任务",
                Map.of("type", "object", "properties", Map.of(
                        "subject", Map.of("type", "string", "description", "任务标题"),
                        "description", Map.of("type", "string", "description", "任务详细描述")
                ), "required", List.of("subject"))));
        tools.add(DashScopeClient.toolDefinition("task_update", "更新任务状态",
                Map.of("type", "object", "properties", Map.of(
                        "task_id", Map.of("type", "integer", "description", "任务 ID"),
                        "status", Map.of("type", "string", "description", "新状态: pending/in_progress/completed/deleted"),
                        "owner", Map.of("type", "string", "description", "负责人（可选）")
                ), "required", List.of("task_id"))));
        tools.add(DashScopeClient.toolDefinition("task_list", "列出所有任务",
                Map.of("type", "object", "properties", Map.of())));
        tools.add(DashScopeClient.toolDefinition("task_get", "获取任务详情",
                Map.of("type", "object", "properties", Map.of(
                        "task_id", Map.of("type", "integer", "description", "任务 ID")
                ), "required", List.of("task_id"))));
        return tools;
    }

    private static final List<Map<String, Object>> TOOLS = buildTools();

    // ==================== 工具分发 =======================

    private static String dispatchTool(String name, JsonObject args) {
        return switch (name) {
            case "task_create" -> taskMgr.create(
                    args.get("subject").getAsString(),
                    args.has("description") ? args.get("description").getAsString() : null);
            case "task_update" -> taskMgr.update(
                    args.get("task_id").getAsInt(),
                    args.has("status") ? args.get("status").getAsString() : null,
                    args.has("owner") ? args.get("owner").getAsString() : null);
            case "task_list" -> taskMgr.listAll();
            case "task_get" -> taskMgr.get(args.get("task_id").getAsInt());
            default -> CommonTools.dispatch(name, args);
        };
    }

    // =================== Agent 循环 =======================
    private static void agentLoop(List<Map<String, Object>> messages) {
        int maxTurns = 30;
        for (int turn = 0; turn < maxTurns; turn++) {
            JsonObject response = client.createMessage(SYSTEM_PROMPT, messages, TOOLS, 4096);
            messages.add(DashScopeClient.assistantMessageToMap(response));

            if (!DashScopeClient.hasToolCalls(response)) return;

            JsonArray toolCalls = DashScopeClient.getToolCalls(response);
            for (int i = 0; i < toolCalls.size(); i++) {
                JsonObject toolCall = toolCalls.get(i).getAsJsonObject();
                String toolName = DashScopeClient.getToolName(toolCall);
                JsonObject arguments = DashScopeClient.getToolArguments(toolCall);
                String toolCallId = DashScopeClient.getToolCallId(toolCall);

                String result;
                try { result = dispatchTool(toolName, arguments); }
                catch (Exception e) { result = "错误: " + e.getMessage(); }

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

        System.out.println("=== S12 Task System 演示 ===");
        System.out.println("任务存储目录: " + TASKS_DIR);
        System.out.println("Agent 可以创建/更新/查询任务，任务持久化到磁盘");
        System.out.println("输入 q 退出\n");

        while (true) {
            System.out.print("\033[36m[S12] >>> \033[0m");
            String input = reader.readLine();
            if (input == null || input.isBlank() ||
                    "q".equalsIgnoreCase(input.trim()) || "exit".equalsIgnoreCase(input.trim())) break;
            history.add(DashScopeClient.userMessage(input));
            agentLoop(history);
            System.out.println();
        }
    }
}
