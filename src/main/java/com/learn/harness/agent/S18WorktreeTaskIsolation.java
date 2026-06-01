package com.learn.harness.agent;

/**
 * 【第十八章】Git Worktree 任务隔离
 *
 * 本章演示如何用 git worktree 实现任务级别的代码隔离：
 * 每个任务在独立的 worktree 中执行，互不干扰。
 *
 * 核心架构：
 *   任务（Task）= 控制面：什么要做、谁在做、什么状态
 *   工作树（Worktree）= 执行面：代码在哪改、分支是什么、命令在哪跑
 *
 * 为什么需要隔离？
 * 当 Agent 同时处理多个任务时（比如一个改前端，一个改后端），
 * 如果在同一个目录操作会互相冲突。worktree 让每个任务有独立的工作目录。
 *
 * 生命周期：
 *   创建任务 → 创建 worktree 并绑定 → 在 worktree 中执行命令
 *   → 完成后选择 keep（保留 worktree）或 remove（清理）
 *
 * 事件总线：记录所有操作历史，方便回溯和审计。
 *
 * 运行方式：
 *   export DASHSCOPE_API_KEY="your-key"
 *   mvn compile exec:java -Dexec.mainClass="com.learn.harness.agent.S18WorktreeTaskIsolation"
 */

import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public class S18WorktreeTaskIsolation {

    // ==================== 配置 ====================

    private static final DashScopeClient client = new DashScopeClient();
    private static final Path REPO_ROOT = detectRepoRoot();
    private static final Path TASKS_DIR = REPO_ROOT.resolve(".tasks");
    private static final Path WT_DIR = REPO_ROOT.resolve(".worktrees");
    private static final Path EVENTS_PATH = WT_DIR.resolve("events.jsonl");
    private static final Path INDEX_PATH = WT_DIR.resolve("index.json");

    private static final String SYSTEM_PROMPT =
            "你是一个编程助手，工作在 git 仓库 " + REPO_ROOT + " 中。\n" +
            "你可以创建任务、创建 worktree 来隔离执行、在 worktree 中运行命令。\n" +
            "每个任务绑定一个独立的 worktree，互不干扰。";

    /** 检测 git 仓库根目录 */
    private static Path detectRepoRoot() {
        try {
            Process proc = new ProcessBuilder("git", "rev-parse", "--show-toplevel")
                    .directory(new File(CommonTools.WORKDIR))
                    .redirectErrorStream(true).start();
            String out = new String(proc.getInputStream().readAllBytes()).trim();
            proc.waitFor();
            if (proc.exitValue() == 0 && !out.isEmpty() && Files.exists(Path.of(out))) {
                return Path.of(out);
            }
        } catch (Exception ignored) {}
        return Path.of(CommonTools.WORKDIR);
    }

    // ==================== 事件总线 ====================
    // 每一次操作（创建 worktree、运行命令、删除等）都记录为事件，
    // 方便事后回溯"谁在什么时候做了什么"。

    static class EventBus {
        EventBus() {
            try {
                Files.createDirectories(WT_DIR);
                if (!Files.exists(EVENTS_PATH)) Files.writeString(EVENTS_PATH, "");
            } catch (IOException ignored) {}
        }

        /** 记录一个事件 */
        void emit(String event, Integer taskId, String worktree) {
            JsonObject obj = new JsonObject();
            obj.addProperty("event", event);
            obj.addProperty("timestamp", System.currentTimeMillis() / 1000.0);
            if (taskId != null) obj.addProperty("task_id", taskId);
            if (worktree != null) obj.addProperty("worktree", worktree);
            try {
                Files.writeString(EVENTS_PATH, DashScopeClient.gson().toJson(obj) + "\n",
                        StandardOpenOption.APPEND);
            } catch (IOException ignored) {}
        }

        /** 查看最近的事件 */
        String listRecent(int limit) {
            try {
                List<String> lines = Files.readAllLines(EVENTS_PATH);
                int from = Math.max(0, lines.size() - Math.min(limit, 50));
                JsonArray arr = new JsonArray();
                for (int i = from; i < lines.size(); i++) {
                    if (!lines.get(i).isBlank()) {
                        arr.add(JsonParser.parseString(lines.get(i)));
                    }
                }
                return DashScopeClient.gson().toJson(arr);
            } catch (IOException e) { return "[]"; }
        }
    }

    // ==================== 任务管理器 ====================
    // 任务是"控制面"：记录要做什么、状态如何、绑定了哪个 worktree。

    static class TaskManager {
        private int nextId;

        TaskManager() {
            try { Files.createDirectories(TASKS_DIR); } catch (IOException ignored) {}
            this.nextId = scanMaxId() + 1;
        }

        private int scanMaxId() {
            int max = 0;
            try (var stream = Files.list(TASKS_DIR)) {
                for (Path p : stream.filter(f -> f.getFileName().toString().matches("task_\\d+\\.json")).toList()) {
                    try {
                        int id = Integer.parseInt(p.getFileName().toString().replaceAll("\\D", ""));
                        max = Math.max(max, id);
                    } catch (NumberFormatException ignored) {}
                }
            } catch (IOException ignored) {}
            return max;
        }

        private Path taskPath(int id) { return TASKS_DIR.resolve("task_" + id + ".json"); }

        String create(String subject, String description) {
            JsonObject task = new JsonObject();
            task.addProperty("id", nextId);
            task.addProperty("subject", subject);
            task.addProperty("description", description);
            task.addProperty("status", "pending");
            task.addProperty("worktree", "");
            task.addProperty("created_at", System.currentTimeMillis() / 1000.0);
            try {
                Files.writeString(taskPath(nextId), DashScopeClient.gson().toJson(task));
            } catch (IOException e) { return "错误: " + e.getMessage(); }
            nextId++;
            return DashScopeClient.gson().toJson(task);
        }

        String get(int id) {
            try { return Files.readString(taskPath(id)); }
            catch (IOException e) { return "错误: 任务 " + id + " 不存在"; }
        }

        String update(int id, String status) {
            try {
                Path p = taskPath(id);
                JsonObject task = JsonParser.parseString(Files.readString(p)).getAsJsonObject();
                if (status != null) task.addProperty("status", status);
                task.addProperty("updated_at", System.currentTimeMillis() / 1000.0);
                Files.writeString(p, DashScopeClient.gson().toJson(task));
                return DashScopeClient.gson().toJson(task);
            } catch (IOException e) { return "错误: " + e.getMessage(); }
        }

        /** 将 worktree 绑定到任务 */
        String bindWorktree(int id, String worktreeName) {
            try {
                Path p = taskPath(id);
                JsonObject task = JsonParser.parseString(Files.readString(p)).getAsJsonObject();
                task.addProperty("worktree", worktreeName);
                if ("pending".equals(task.get("status").getAsString())) {
                    task.addProperty("status", "in_progress");
                }
                task.addProperty("updated_at", System.currentTimeMillis() / 1000.0);
                Files.writeString(p, DashScopeClient.gson().toJson(task));
                return DashScopeClient.gson().toJson(task);
            } catch (IOException e) { return "错误: " + e.getMessage(); }
        }

        boolean exists(int id) { return Files.exists(taskPath(id)); }

        String listAll() {
            try (var stream = Files.list(TASKS_DIR)) {
                var files = stream.filter(f -> f.getFileName().toString().startsWith("task_")).sorted().toList();
                if (files.isEmpty()) return "暂无任务。";
                StringBuilder sb = new StringBuilder();
                for (Path f : files) {
                    JsonObject t = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
                    String marker = switch (t.get("status").getAsString()) {
                        case "pending" -> "[ ]";
                        case "in_progress" -> "[>]";
                        case "completed" -> "[x]";
                        default -> "[?]";
                    };
                    String wt = t.has("worktree") && !t.get("worktree").getAsString().isEmpty()
                            ? " wt=" + t.get("worktree").getAsString() : "";
                    sb.append(marker).append(" #").append(t.get("id").getAsInt())
                      .append(": ").append(t.get("subject").getAsString()).append(wt).append("\n");
                }
                return sb.toString().trim();
            } catch (IOException e) { return "错误: " + e.getMessage(); }
        }
    }

    // ==================== Worktree 管理器 ====================
    // Worktree 是"执行面"：代码在哪个目录、用什么分支、跑什么命令。

    static class WorktreeManager {
        private final TaskManager tasks;
        private final EventBus events;
        private boolean gitAvailable;

        WorktreeManager(TaskManager tasks, EventBus events) {
            this.tasks = tasks;
            this.events = events;
            try { Files.createDirectories(WT_DIR); } catch (IOException ignored) {}
            if (!Files.exists(INDEX_PATH)) {
                try { Files.writeString(INDEX_PATH, "{\"worktrees\":[]}"); } catch (IOException ignored) {}
            }
            this.gitAvailable = checkGit();
        }

        private boolean checkGit() {
            try {
                Process proc = new ProcessBuilder("git", "rev-parse", "--is-inside-work-tree")
                        .directory(REPO_ROOT.toFile()).redirectErrorStream(true).start();
                proc.waitFor();
                return proc.exitValue() == 0;
            } catch (Exception e) { return false; }
        }

        private String runGit(String... args) throws IOException {
            if (!gitAvailable) throw new IOException("当前不在 git 仓库中");
            List<String> cmd = new ArrayList<>();
            cmd.add("git");
            cmd.addAll(List.of(args));
            try {
                Process proc = new ProcessBuilder(cmd)
                        .directory(REPO_ROOT.toFile()).redirectErrorStream(true).start();
                String out = new String(proc.getInputStream().readAllBytes()).trim();
                proc.waitFor();
                if (proc.exitValue() != 0) throw new IOException(out.isEmpty() ? "git 命令失败" : out);
                return out.isEmpty() ? "(无输出)" : out;
            } catch (InterruptedException e) { throw new IOException(e); }
        }

        private JsonObject loadIndex() {
            try { return JsonParser.parseString(Files.readString(INDEX_PATH)).getAsJsonObject(); }
            catch (IOException e) { JsonObject o = new JsonObject(); o.add("worktrees", new JsonArray()); return o; }
        }

        private void saveIndex(JsonObject data) {
            try { Files.writeString(INDEX_PATH, DashScopeClient.gson().toJson(data)); } catch (IOException ignored) {}
        }

        private JsonObject findEntry(String name) {
            for (JsonElement wt : loadIndex().getAsJsonArray("worktrees")) {
                if (wt.getAsJsonObject().get("name").getAsString().equals(name)) {
                    return wt.getAsJsonObject();
                }
            }
            return null;
        }

        /** 创建 worktree 并绑定到任务 */
        String create(String name, Integer taskId) {
            if (!name.matches("[A-Za-z0-9._-]{1,40}")) return "错误: worktree 名称不合法";
            if (findEntry(name) != null) return "错误: worktree '" + name + "' 已存在";
            if (taskId != null && !tasks.exists(taskId)) return "错误: 任务 " + taskId + " 不存在";

            Path path = WT_DIR.resolve(name);
            String branch = "wt/" + name;
            events.emit("worktree.create", taskId, name);

            try {
                runGit("worktree", "add", "-b", branch, path.toString(), "HEAD");

                // 记录到 index
                JsonObject entry = new JsonObject();
                entry.addProperty("name", name);
                entry.addProperty("path", path.toString());
                entry.addProperty("branch", branch);
                entry.addProperty("task_id", taskId != null ? taskId : -1);
                entry.addProperty("status", "active");
                entry.addProperty("created_at", System.currentTimeMillis() / 1000.0);

                JsonObject idx = loadIndex();
                idx.getAsJsonArray("worktrees").add(entry);
                saveIndex(idx);

                // 绑定到任务
                if (taskId != null) tasks.bindWorktree(taskId, name);

                return DashScopeClient.gson().toJson(entry);
            } catch (IOException e) { return "错误: " + e.getMessage(); }
        }

        /** 在指定 worktree 中运行命令 */
        String run(String name, String command) {
            // 安全检查
            for (String d : List.of("rm -rf /", "sudo", "shutdown")) {
                if (command.contains(d)) return "错误: 危险命令被拦截";
            }
            JsonObject wt = findEntry(name);
            if (wt == null) return "错误: 未知 worktree '" + name + "'";
            Path path = Path.of(wt.get("path").getAsString());
            if (!Files.exists(path)) return "错误: worktree 目录不存在: " + path;

            try {
                events.emit("worktree.run", null, name);
                Process proc = new ProcessBuilder("sh", "-c", command)
                        .directory(path.toFile()).redirectErrorStream(true).start();
                String out = new String(proc.getInputStream().readAllBytes()).trim();
                proc.waitFor();
                if (out.isEmpty()) return "(无输出)";
                return out.substring(0, Math.min(50000, out.length()));
            } catch (Exception e) { return "错误: " + e.getMessage(); }
        }

        /** 查看 worktree 的 git 状态 */
        String status(String name) {
            JsonObject wt = findEntry(name);
            if (wt == null) return "错误: 未知 worktree '" + name + "'";
            Path path = Path.of(wt.get("path").getAsString());
            if (!Files.exists(path)) return "错误: worktree 目录不存在";
            try {
                Process proc = new ProcessBuilder("git", "status", "--short", "--branch")
                        .directory(path.toFile()).redirectErrorStream(true).start();
                String out = new String(proc.getInputStream().readAllBytes()).trim();
                proc.waitFor();
                return out.isEmpty() ? "工作树干净" : out;
            } catch (Exception e) { return "错误: " + e.getMessage(); }
        }

        /** 删除 worktree */
        String remove(String name, boolean completeTask) {
            JsonObject wt = findEntry(name);
            if (wt == null) return "错误: 未知 worktree '" + name + "'";
            events.emit("worktree.remove", null, name);
            try {
                runGit("worktree", "remove", "--force", wt.get("path").getAsString());
                int taskId = wt.has("task_id") ? wt.get("task_id").getAsInt() : -1;
                if (completeTask && taskId > 0) tasks.update(taskId, "completed");
                // 更新 index 状态
                JsonObject idx = loadIndex();
                for (JsonElement e : idx.getAsJsonArray("worktrees")) {
                    if (e.getAsJsonObject().get("name").getAsString().equals(name)) {
                        e.getAsJsonObject().addProperty("status", "removed");
                        break;
                    }
                }
                saveIndex(idx);
                return "已删除 worktree '" + name + "'";
            } catch (IOException e) { return "错误: " + e.getMessage(); }
        }

        /** 列出所有 worktree */
        String listAll() {
            JsonArray wts = loadIndex().getAsJsonArray("worktrees");
            if (wts.isEmpty()) return "暂无 worktree。";
            StringBuilder sb = new StringBuilder();
            for (JsonElement wt : wts) {
                JsonObject o = wt.getAsJsonObject();
                String taskSuffix = o.get("task_id").getAsInt() > 0
                        ? " task=#" + o.get("task_id").getAsInt() : "";
                sb.append("[").append(o.get("status").getAsString()).append("] ")
                  .append(o.get("name").getAsString())
                  .append(" (").append(o.get("branch").getAsString()).append(")")
                  .append(taskSuffix).append("\n");
            }
            return sb.toString().trim();
        }

        boolean isGitAvailable() { return gitAvailable; }
    }

    // ==================== 全局实例 ====================

    private static final EventBus EVENTS = new EventBus();
    private static final TaskManager TASKS = new TaskManager();
    private static final WorktreeManager WORKTREES = new WorktreeManager(TASKS, EVENTS);

    // ==================== 工具定义 ====================

    private static List<Map<String, Object>> buildTools() {
        List<Map<String, Object>> tools = new ArrayList<>(CommonTools.allBasicToolDefs());

        // 任务管理工具
        tools.add(DashScopeClient.toolDefinition("task_create", "创建一个新任务",
                Map.of("type", "object",
                        "properties", Map.of(
                                "subject", Map.of("type", "string", "description", "任务标题"),
                                "description", Map.of("type", "string", "description", "任务描述")
                        ), "required", List.of("subject"))));
        tools.add(DashScopeClient.toolDefinition("task_list", "列出所有任务",
                Map.of("type", "object", "properties", Map.of())));
        tools.add(DashScopeClient.toolDefinition("task_update", "更新任务状态",
                Map.of("type", "object",
                        "properties", Map.of(
                                "task_id", Map.of("type", "integer", "description", "任务ID"),
                                "status", Map.of("type", "string", "description", "新状态: pending/in_progress/completed")
                        ), "required", List.of("task_id", "status"))));

        // Worktree 管理工具
        tools.add(DashScopeClient.toolDefinition("worktree_create", "创建 worktree 并绑定任务",
                Map.of("type", "object",
                        "properties", Map.of(
                                "name", Map.of("type", "string", "description", "worktree 名称"),
                                "task_id", Map.of("type", "integer", "description", "要绑定的任务ID")
                        ), "required", List.of("name"))));
        tools.add(DashScopeClient.toolDefinition("worktree_list", "列出所有 worktree",
                Map.of("type", "object", "properties", Map.of())));
        tools.add(DashScopeClient.toolDefinition("worktree_run", "在指定 worktree 中运行命令",
                Map.of("type", "object",
                        "properties", Map.of(
                                "name", Map.of("type", "string", "description", "worktree 名称"),
                                "command", Map.of("type", "string", "description", "要执行的命令")
                        ), "required", List.of("name", "command"))));
        tools.add(DashScopeClient.toolDefinition("worktree_status", "查看 worktree 的 git 状态",
                Map.of("type", "object",
                        "properties", Map.of("name", Map.of("type", "string", "description", "worktree 名称")),
                        "required", List.of("name"))));
        tools.add(DashScopeClient.toolDefinition("worktree_remove", "删除 worktree",
                Map.of("type", "object",
                        "properties", Map.of(
                                "name", Map.of("type", "string", "description", "worktree 名称"),
                                "complete_task", Map.of("type", "boolean", "description", "是否同时完成关联任务")
                        ), "required", List.of("name"))));
        tools.add(DashScopeClient.toolDefinition("worktree_events", "查看最近的操作事件",
                Map.of("type", "object",
                        "properties", Map.of("limit", Map.of("type", "integer", "description", "返回条数")),
                        "required", List.of())));

        return tools;
    }

    // ==================== 工具分发 ====================

    private static String dispatchTool(String name, JsonObject args) {
        return switch (name) {
            case "bash", "read_file", "write_file", "edit_file" -> CommonTools.dispatch(name, args);
            case "task_create" -> TASKS.create(
                    args.get("subject").getAsString(),
                    args.has("description") ? args.get("description").getAsString() : "");
            case "task_list" -> TASKS.listAll();
            case "task_update" -> TASKS.update(
                    args.get("task_id").getAsInt(), args.get("status").getAsString());
            case "worktree_create" -> WORKTREES.create(
                    args.get("name").getAsString(),
                    args.has("task_id") ? args.get("task_id").getAsInt() : null);
            case "worktree_list" -> WORKTREES.listAll();
            case "worktree_run" -> WORKTREES.run(
                    args.get("name").getAsString(), args.get("command").getAsString());
            case "worktree_status" -> WORKTREES.status(args.get("name").getAsString());
            case "worktree_remove" -> WORKTREES.remove(
                    args.get("name").getAsString(),
                    args.has("complete_task") && args.get("complete_task").getAsBoolean());
            case "worktree_events" -> EVENTS.listRecent(
                    args.has("limit") ? args.get("limit").getAsInt() : 20);
            default -> "未知工具: " + name;
        };
    }

    // ==================== Agent 循环 ====================

    private static final List<Map<String, Object>> TOOLS = buildTools();

    private static void agentLoop(List<Map<String, Object>> messages) {
        for (int turn = 0; turn < 30; turn++) {
            JsonObject response = client.createMessage(SYSTEM_PROMPT, messages, TOOLS, 4096);
            messages.add(DashScopeClient.assistantMessageToMap(response));

            if (!DashScopeClient.hasToolCalls(response)) return;

            JsonArray toolCalls = DashScopeClient.getToolCalls(response);
            for (int i = 0; i < toolCalls.size(); i++) {
                JsonObject tc = toolCalls.get(i).getAsJsonObject();
                String toolName = DashScopeClient.getToolName(tc);
                JsonObject toolArgs = DashScopeClient.getToolArguments(tc);
                String toolId = DashScopeClient.getToolCallId(tc);

                String result;
                try { result = dispatchTool(toolName, toolArgs); }
                catch (Exception e) { result = "错误: " + e.getMessage(); }

                System.out.println("  [工具] " + toolName + " → " +
                        result.substring(0, Math.min(120, result.length())));
                messages.add(DashScopeClient.toolResultMessage(toolId, result));
            }
        }
    }

    // ==================== REPL 入口 ====================

    public static void main(String[] args) throws Exception {
        System.out.println("=== S18 Worktree 任务隔离 ===");
        System.out.println("仓库根目录: " + REPO_ROOT);
        if (!WORKTREES.isGitAvailable()) {
            System.out.println("⚠ 当前不在 git 仓库中，worktree 工具将不可用");
        }
        System.out.println("输入任务描述，或输入 q 退出\n");

        List<Map<String, Object>> messages = new ArrayList<>();
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));

        while (true) {
            System.out.print("s18 >> ");
            String input = reader.readLine();
            if (input == null || input.trim().equalsIgnoreCase("q") || input.trim().equalsIgnoreCase("exit")) break;
            if (input.trim().isEmpty()) continue;

            messages.add(DashScopeClient.userMessage(input.trim()));
            agentLoop(messages);

            // 打印最后一条 assistant 回复
            for (int i = messages.size() - 1; i >= 0; i--) {
                Object role = messages.get(i).get("role");
                if ("assistant".equals(role)) {
                    Object content = messages.get(i).get("content");
                    if (content instanceof String text && !text.isEmpty()) {
                        System.out.println("\n" + text);
                    }
                    break;
                }
            }
            System.out.println();
        }
    }
}
