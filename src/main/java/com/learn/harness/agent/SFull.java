package com.learn.harness.agent;

/**
 * 【合并版】完整 Agent（S01~S18 所有机制整合）
 *
 * 这是本教程的毕业作品：把前面 18 章学到的所有机制整合到一个可运行的 Agent 中。
 *
 * 整合的机制：
 *   S01 Agent 循环        → agentLoop() 核心循环
 *   S02 工具分发          → dispatchTool() switch 分发
 *   S03 计划管理          → TodoManager 跟踪任务进度
 *   S04 子 Agent          → runSubagent() 独立消息列表
 *   S05 技能加载          → SkillLoader 按需加载技能文件
 *   S06 上下文压缩        → microCompact() / autoCompact() / 大输出持久化
 *   S07 权限（简化）      → 嵌入在 bash 的危险命令拦截中
 *   S09 记忆（简化）      → 通过文件系统持久化
 *   S10 系统提示词        → SYSTEM_PROMPT 动态拼接
 *   S11 错误恢复          → agentLoop 中的 try-catch + 重试
 *   S12 任务系统          → TaskManager 持久化任务
 *   S13 后台任务          → BackgroundManager 异步执行
 *   S15 多 Agent 通信     → MessageBus + TeammateManager
 *   S16 团队协议          → shutdown 协议处理
 *   S17 自治 Agent        → 队员空闲轮询 + 超时退出
 *
 * REPL 命令：
 *   /compact  - 手动压缩上下文
 *   /tasks    - 查看任务列表
 *   /team     - 查看团队状态
 *   /inbox    - 查看收件箱
 *   q/exit    - 退出
 *
 * 运行方式：
 *   export DASHSCOPE_API_KEY="your-key"
 *   mvn compile exec:java -Dexec.mainClass="com.learn.harness.agent.SFull"
 */

import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public class SFull {

    // ==================== 配置 ====================

    private static final DashScopeClient client = new DashScopeClient();
    private static final Path TASKS_DIR = Path.of(CommonTools.WORKDIR, ".tasks");
    private static final Path TEAM_DIR = Path.of(CommonTools.WORKDIR, ".team");
    private static final Path INBOX_DIR = TEAM_DIR.resolve("inbox");
    private static final Path SKILLS_DIR = Path.of(CommonTools.WORKDIR, "skills");
    private static final Path TRANSCRIPT_DIR = Path.of(CommonTools.WORKDIR, ".transcripts");
    private static final Path TOOL_RESULTS_DIR = Path.of(CommonTools.WORKDIR, ".task_outputs", "tool-results");

    /** 上下文压缩阈值（估算 token 数） */
    private static final int TOKEN_THRESHOLD = 100000;
    /** 大输出持久化阈值（字符数） */
    private static final int PERSIST_THRESHOLD = 30000;
    /** 保留最近 N 条工具结果不截断 */
    private static final int KEEP_RECENT = 3;
    /** 队员空闲轮询间隔（秒） */
    private static final int POLL_INTERVAL = 5;
    /** 队员空闲超时（秒） */
    private static final int IDLE_TIMEOUT = 60;

    // ==================== 计划管理（S03） ====================

    static class TodoManager {
        private List<JsonObject> items = new ArrayList<>();

        String update(JsonArray arr) {
            List<JsonObject> validated = new ArrayList<>();
            int inProgress = 0;
            for (int i = 0; i < arr.size(); i++) {
                JsonObject item = arr.get(i).getAsJsonObject();
                String content = item.has("content") ? item.get("content").getAsString().trim() : "";
                String status = item.has("status") ? item.get("status").getAsString().toLowerCase() : "pending";
                if (content.isEmpty()) return "错误: 第 " + i + " 项缺少 content";
                if (!Set.of("pending", "in_progress", "completed").contains(status))
                    return "错误: 无效状态 '" + status + "'";
                if ("in_progress".equals(status)) inProgress++;
                JsonObject v = new JsonObject();
                v.addProperty("content", content);
                v.addProperty("status", status);
                validated.add(v);
            }
            if (validated.size() > 20) return "错误: 最多 20 条待办";
            if (inProgress > 1) return "错误: 最多 1 条 in_progress";
            items = validated;
            return render();
        }

        String render() {
            if (items.isEmpty()) return "暂无待办。";
            StringBuilder sb = new StringBuilder();
            int done = 0;
            for (JsonObject item : items) {
                String st = item.get("status").getAsString();
                String marker = switch (st) { case "completed" -> "[x]"; case "in_progress" -> "[>]"; default -> "[ ]"; };
                sb.append(marker).append(" ").append(item.get("content").getAsString()).append("\n");
                if ("completed".equals(st)) done++;
            }
            sb.append("(").append(done).append("/").append(items.size()).append(" 已完成)");
            return sb.toString();
        }

        boolean hasOpenItems() {
            return items.stream().anyMatch(i -> !"completed".equals(i.get("status").getAsString()));
        }
    }

    // ==================== 技能加载（S05） ====================

    static class SkillLoader {
        private final Map<String, String> skills = new LinkedHashMap<>();

        SkillLoader(Path dir) {
            if (!Files.exists(dir)) return;
            try (var stream = Files.walk(dir)) {
                stream.filter(p -> p.getFileName().toString().equals("SKILL.md")).sorted().forEach(p -> {
                    try {
                        String name = p.getParent().getFileName().toString();
                        skills.put(name, Files.readString(p));
                    } catch (IOException ignored) {}
                });
            } catch (IOException ignored) {}
        }

        String descriptions() {
            if (skills.isEmpty()) return "(无技能)";
            StringBuilder sb = new StringBuilder();
            skills.keySet().forEach(n -> sb.append("  - ").append(n).append("\n"));
            return sb.toString().trim();
        }

        String load(String name) {
            String content = skills.get(name);
            if (content == null) return "错误: 未知技能 '" + name + "'，可用: " + String.join(", ", skills.keySet());
            return "<skill name=\"" + name + "\">\n" + content + "\n</skill>";
        }
    }

    // ==================== 上下文压缩（S06） ====================

    /**
     * 大输出持久化：如果工具输出太长，存到文件，返回摘要+路径
     */
    private static String maybePersist(String toolCallId, String output) {
        if (output == null || output.length() <= PERSIST_THRESHOLD) return output;
        try {
            Files.createDirectories(TOOL_RESULTS_DIR);
            String safeId = (toolCallId != null ? toolCallId : "anon").replaceAll("[^a-zA-Z0-9_.-]", "_");
            Path path = TOOL_RESULTS_DIR.resolve(safeId + ".txt");
            if (!Files.exists(path)) Files.writeString(path, output);
            String preview = output.substring(0, Math.min(2000, output.length()));
            return "输出过长(" + output.length() + " 字符)，已存到 " + path.getFileName() + "\n预览:\n" + preview + "\n...";
        } catch (IOException e) { return output.substring(0, Math.min(50000, output.length())); }
    }

    /**
     * 微压缩：截断旧的工具结果，保留最近几条
     */
    private static void microCompact(List<Map<String, Object>> messages) {
        // 找出所有 tool 类型的消息
        List<Map<String, Object>> toolMsgs = new ArrayList<>();
        for (Map<String, Object> msg : messages) {
            if ("tool".equals(msg.get("role"))) toolMsgs.add(msg);
        }
        if (toolMsgs.size() <= KEEP_RECENT) return;
        // 截断旧的
        for (int i = 0; i < toolMsgs.size() - KEEP_RECENT; i++) {
            Object content = toolMsgs.get(i).get("content");
            if (content instanceof String s && s.length() > 200) {
                // 需要创建一个新的可变 Map 来替换
                Map<String, Object> newMsg = new HashMap<>(toolMsgs.get(i));
                newMsg.put("content", "[已截断的旧工具结果]");
                int idx = messages.indexOf(toolMsgs.get(i));
                if (idx >= 0) messages.set(idx, newMsg);
            }
        }
    }

    /**
     * 自动压缩：把整段对话摘要为一条消息
     */
    private static List<Map<String, Object>> autoCompact(List<Map<String, Object>> messages) {
        // 先保存完整记录
        try {
            Files.createDirectories(TRANSCRIPT_DIR);
            Path p = TRANSCRIPT_DIR.resolve("transcript_" + System.currentTimeMillis() + ".jsonl");
            StringBuilder sb = new StringBuilder();
            for (Map<String, Object> msg : messages) sb.append(DashScopeClient.gson().toJson(msg)).append("\n");
            Files.writeString(p, sb.toString());
        } catch (IOException ignored) {}

        // 用模型做摘要
        String convText = DashScopeClient.gson().toJson(messages);
        if (convText.length() > 80000) convText = convText.substring(0, 80000);

        List<Map<String, Object>> summaryMsgs = new ArrayList<>();
        summaryMsgs.add(DashScopeClient.userMessage(
                "请把以下对话摘要为续接信息（包含：任务概述、当前进度、关键决策、下一步）:\n\n" + convText));
        JsonObject resp = client.createMessage(null, summaryMsgs, null, 4000);
        String summary = DashScopeClient.extractText(resp);

        List<Map<String, Object>> continuation = new ArrayList<>();
        continuation.add(DashScopeClient.userMessage(
                "本次对话从上一轮压缩继续。上轮摘要：\n\n" + summary + "\n\n请继续工作。"));
        return continuation;
    }

    /** 估算 token 数（粗略：字符数/4） */
    private static int estimateTokens(List<Map<String, Object>> messages) {
        return DashScopeClient.gson().toJson(messages).length() / 4;
    }

    // ==================== 任务系统（S12） ====================

    static class TaskManager {
        TaskManager() { try { Files.createDirectories(TASKS_DIR); } catch (IOException ignored) {} }

        private int nextId() {
            int max = 0;
            try (var s = Files.list(TASKS_DIR)) {
                for (Path p : s.filter(f -> f.getFileName().toString().matches("task_\\d+\\.json")).toList()) {
                    try { max = Math.max(max, Integer.parseInt(p.getFileName().toString().replaceAll("\\D", ""))); }
                    catch (NumberFormatException ignored) {}
                }
            } catch (IOException ignored) {}
            return max + 1;
        }

        String create(String subject, String desc) {
            int id = nextId();
            JsonObject task = new JsonObject();
            task.addProperty("id", id);
            task.addProperty("subject", subject);
            task.addProperty("description", desc);
            task.addProperty("status", "pending");
            try { Files.writeString(TASKS_DIR.resolve("task_" + id + ".json"), DashScopeClient.gson().toJson(task)); }
            catch (IOException e) { return "错误: " + e.getMessage(); }
            return DashScopeClient.gson().toJson(task);
        }

        String get(int id) {
            try { return Files.readString(TASKS_DIR.resolve("task_" + id + ".json")); }
            catch (IOException e) { return "错误: 任务 " + id + " 不存在"; }
        }

        String update(int id, String status) {
            try {
                Path p = TASKS_DIR.resolve("task_" + id + ".json");
                JsonObject task = JsonParser.parseString(Files.readString(p)).getAsJsonObject();
                if (status != null) task.addProperty("status", status);
                Files.writeString(p, DashScopeClient.gson().toJson(task));
                return DashScopeClient.gson().toJson(task);
            } catch (IOException e) { return "错误: " + e.getMessage(); }
        }

        String claim(int id, String owner) {
            try {
                Path p = TASKS_DIR.resolve("task_" + id + ".json");
                JsonObject task = JsonParser.parseString(Files.readString(p)).getAsJsonObject();
                task.addProperty("owner", owner);
                task.addProperty("status", "in_progress");
                Files.writeString(p, DashScopeClient.gson().toJson(task));
                return "已认领任务 #" + id + " → " + owner;
            } catch (IOException e) { return "错误: " + e.getMessage(); }
        }

        String listAll() {
            try (var s = Files.list(TASKS_DIR)) {
                var files = s.filter(f -> f.getFileName().toString().startsWith("task_")).sorted().toList();
                if (files.isEmpty()) return "暂无任务。";
                StringBuilder sb = new StringBuilder();
                for (Path f : files) {
                    JsonObject t = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
                    String m = switch (t.get("status").getAsString()) {
                        case "pending" -> "[ ]"; case "in_progress" -> "[>]"; case "completed" -> "[x]"; default -> "[?]"; };
                    sb.append(m).append(" #").append(t.get("id").getAsInt()).append(": ").append(t.get("subject").getAsString()).append("\n");
                }
                return sb.toString().trim();
            } catch (IOException e) { return "错误: " + e.getMessage(); }
        }
    }

    // ==================== 后台任务（S13） ====================

    static class BackgroundManager {
        private final ConcurrentHashMap<String, JsonObject> tasks = new ConcurrentHashMap<>();
        private final LinkedBlockingQueue<JsonObject> notifications = new LinkedBlockingQueue<>();

        String run(String command, int timeout) {
            String tid = UUID.randomUUID().toString().substring(0, 8);
            JsonObject info = new JsonObject();
            info.addProperty("status", "running");
            info.addProperty("command", command);
            tasks.put(tid, info);

            new Thread(() -> {
                try {
                    Process proc = new ProcessBuilder("sh", "-c", command)
                            .directory(new File(CommonTools.WORKDIR)).redirectErrorStream(true).start();
                    String out = new String(proc.getInputStream().readAllBytes()).trim();
                    boolean done = proc.waitFor(timeout, TimeUnit.SECONDS);
                    if (!done) { proc.destroyForcibly(); out = "超时"; }
                    info.addProperty("status", "completed");
                    info.addProperty("result", out.substring(0, Math.min(50000, out.length())));
                } catch (Exception e) { info.addProperty("status", "error"); info.addProperty("result", e.getMessage()); }
                JsonObject notif = new JsonObject();
                notif.addProperty("task_id", tid);
                notif.addProperty("status", info.get("status").getAsString());
                notifications.add(notif);
            }, "bg-" + tid).start();

            return "后台任务 " + tid + " 已启动: " + command.substring(0, Math.min(80, command.length()));
        }

        String check(String tid) {
            if (tid != null && !tid.isEmpty()) {
                JsonObject t = tasks.get(tid);
                if (t == null) return "未知任务: " + tid;
                return "[" + t.get("status").getAsString() + "] " +
                        (t.has("result") ? t.get("result").getAsString() : "(运行中)");
            }
            if (tasks.isEmpty()) return "无后台任务。";
            StringBuilder sb = new StringBuilder();
            tasks.forEach((k, v) -> sb.append(k).append(": [").append(v.get("status").getAsString()).append("]\n"));
            return sb.toString().trim();
        }

        List<JsonObject> drain() {
            List<JsonObject> list = new ArrayList<>();
            notifications.drainTo(list);
            return list;
        }
    }

    // ==================== 消息总线（S15） ====================

    static class MessageBus {
        MessageBus() { try { Files.createDirectories(INBOX_DIR); } catch (IOException ignored) {} }

        String send(String from, String to, String content) {
            return send(from, to, content, "message");
        }

        String send(String from, String to, String content, String type) {
            JsonObject msg = new JsonObject();
            msg.addProperty("type", type);
            msg.addProperty("from", from);
            msg.addProperty("content", content);
            msg.addProperty("timestamp", System.currentTimeMillis());
            try {
                Files.writeString(INBOX_DIR.resolve(to + ".jsonl"),
                        DashScopeClient.gson().toJson(msg) + "\n",
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) { return "错误: " + e.getMessage(); }
            return "已发送到 " + to;
        }

        List<JsonObject> readInbox(String name) {
            Path p = INBOX_DIR.resolve(name + ".jsonl");
            if (!Files.exists(p)) return List.of();
            try {
                String content = Files.readString(p).trim();
                Files.writeString(p, ""); // 读后清空
                if (content.isEmpty()) return List.of();
                List<JsonObject> msgs = new ArrayList<>();
                for (String line : content.split("\n")) {
                    if (!line.isBlank()) msgs.add(JsonParser.parseString(line).getAsJsonObject());
                }
                return msgs;
            } catch (IOException e) { return List.of(); }
        }

        String broadcast(String from, String content, List<String> members) {
            int count = 0;
            for (String m : members) {
                if (!m.equals(from)) { send(from, m, content, "broadcast"); count++; }
            }
            return "已广播给 " + count + " 个队员";
        }
    }

    // ==================== 团队管理（S15/S16/S17） ====================

    static class TeammateManager {
        private final MessageBus bus;
        private final TaskManager taskMgr;
        private final Path configPath = TEAM_DIR.resolve("config.json");
        private JsonObject config;

        TeammateManager(MessageBus bus, TaskManager tm) {
            this.bus = bus;
            this.taskMgr = tm;
            try { Files.createDirectories(TEAM_DIR); } catch (IOException ignored) {}
            config = Files.exists(configPath) ? loadConfig() : defaultConfig();
        }

        private JsonObject loadConfig() {
            try { return JsonParser.parseString(Files.readString(configPath)).getAsJsonObject(); }
            catch (IOException e) { return defaultConfig(); }
        }

        private JsonObject defaultConfig() {
            JsonObject c = new JsonObject();
            c.addProperty("team_name", "default");
            c.add("members", new JsonArray());
            return c;
        }

        private void saveConfig() {
            try { Files.writeString(configPath, DashScopeClient.gson().toJson(config)); } catch (IOException ignored) {}
        }

        /** 孵化一个自治队员 */
        String spawn(String name, String role, String prompt) {
            JsonArray members = config.getAsJsonArray("members");
            // 检查是否已存在
            for (JsonElement m : members) {
                if (m.getAsJsonObject().get("name").getAsString().equals(name)) {
                    String st = m.getAsJsonObject().get("status").getAsString();
                    if (!"idle".equals(st) && !"shutdown".equals(st))
                        return "错误: '" + name + "' 当前状态是 " + st;
                    m.getAsJsonObject().addProperty("status", "working");
                    m.getAsJsonObject().addProperty("role", role);
                    saveConfig();
                    new Thread(() -> teammateLoop(name, role, prompt), "mate-" + name).start();
                    return "已重启 '" + name + "'";
                }
            }
            // 新建
            JsonObject member = new JsonObject();
            member.addProperty("name", name);
            member.addProperty("role", role);
            member.addProperty("status", "working");
            members.add(member);
            saveConfig();
            new Thread(() -> teammateLoop(name, role, prompt), "mate-" + name).start();
            return "已孵化 '" + name + "' (角色: " + role + ")";
        }

        private void setStatus(String name, String status) {
            for (JsonElement m : config.getAsJsonArray("members")) {
                if (m.getAsJsonObject().get("name").getAsString().equals(name)) {
                    m.getAsJsonObject().addProperty("status", status);
                    saveConfig();
                    return;
                }
            }
        }

        /** 队员的自治循环（独立线程运行） */
        private void teammateLoop(String name, String role, String prompt) {
            String sys = "你是 '" + name + "'，角色: " + role + "，工作目录: " + CommonTools.WORKDIR;
            List<Map<String, Object>> msgs = new ArrayList<>();
            msgs.add(DashScopeClient.userMessage(prompt));

            List<Map<String, Object>> tools = List.of(
                    CommonTools.bashToolDef(), CommonTools.readFileToolDef(),
                    CommonTools.writeFileToolDef(), CommonTools.editFileToolDef(),
                    DashScopeClient.toolDefinition("send_message", "发送消息给队友",
                            Map.of("type", "object", "properties", Map.of(
                                    "to", Map.of("type", "string"), "content", Map.of("type", "string")),
                                    "required", List.of("to", "content"))),
                    DashScopeClient.toolDefinition("idle", "信号：我做完了，进入空闲",
                            Map.of("type", "object", "properties", Map.of())),
                    DashScopeClient.toolDefinition("claim_task", "认领任务",
                            Map.of("type", "object", "properties", Map.of(
                                    "task_id", Map.of("type", "integer")),
                                    "required", List.of("task_id")))
            );

            while (true) {
                // 工作阶段
                for (int i = 0; i < 50; i++) {
                    // 检查收件箱
                    List<JsonObject> inbox = bus.readInbox(name);
                    for (JsonObject m : inbox) {
                        if ("shutdown_request".equals(m.get("type").getAsString())) {
                            setStatus(name, "shutdown");
                            return;
                        }
                        msgs.add(DashScopeClient.userMessage(DashScopeClient.gson().toJson(m)));
                    }

                    JsonObject resp;
                    try { resp = client.createMessage(sys, msgs, tools, 4096); }
                    catch (Exception e) { setStatus(name, "shutdown"); return; }
                    msgs.add(DashScopeClient.assistantMessageToMap(resp));

                    if (!DashScopeClient.hasToolCalls(resp)) break;

                    JsonArray toolCalls = DashScopeClient.getToolCalls(resp);
                    boolean idle = false;
                    for (int j = 0; j < toolCalls.size(); j++) {
                        JsonObject tc = toolCalls.get(j).getAsJsonObject();
                        String tn = DashScopeClient.getToolName(tc);
                        JsonObject ta = DashScopeClient.getToolArguments(tc);
                        String tid = DashScopeClient.getToolCallId(tc);

                        String out = switch (tn) {
                            case "idle" -> { idle = true; yield "进入空闲。"; }
                            case "claim_task" -> taskMgr.claim(ta.get("task_id").getAsInt(), name);
                            case "send_message" -> bus.send(name, ta.get("to").getAsString(), ta.get("content").getAsString());
                            default -> CommonTools.dispatch(tn, ta);
                        };
                        msgs.add(DashScopeClient.toolResultMessage(tid, out));
                    }
                    if (idle) break;
                }

                // 空闲轮询阶段（S17 核心）
                setStatus(name, "idle");
                boolean resume = false;
                for (int p = 0; p < IDLE_TIMEOUT / POLL_INTERVAL; p++) {
                    try { Thread.sleep(POLL_INTERVAL * 1000L); } catch (InterruptedException e) { break; }
                    List<JsonObject> inbox = bus.readInbox(name);
                    if (!inbox.isEmpty()) {
                        for (JsonObject m : inbox) msgs.add(DashScopeClient.userMessage(DashScopeClient.gson().toJson(m)));
                        resume = true;
                        break;
                    }
                }
                if (!resume) { setStatus(name, "shutdown"); return; }
                setStatus(name, "working");
            }
        }

        String listAll() {
            JsonArray members = config.getAsJsonArray("members");
            if (members.isEmpty()) return "暂无队员。";
            StringBuilder sb = new StringBuilder("团队: " + config.get("team_name").getAsString());
            for (JsonElement m : members) {
                JsonObject o = m.getAsJsonObject();
                sb.append("\n  ").append(o.get("name").getAsString())
                  .append(" (").append(o.get("role").getAsString())
                  .append("): ").append(o.get("status").getAsString());
            }
            return sb.toString();
        }

        List<String> memberNames() {
            List<String> names = new ArrayList<>();
            for (JsonElement m : config.getAsJsonArray("members")) {
                names.add(m.getAsJsonObject().get("name").getAsString());
            }
            return names;
        }
    }

    // ==================== 子 Agent（S04） ====================

    private static String runSubagent(String prompt) {
        List<Map<String, Object>> msgs = new ArrayList<>();
        msgs.add(DashScopeClient.userMessage(prompt));
        List<Map<String, Object>> tools = CommonTools.allBasicToolDefs();

        JsonObject resp = null;
        for (int i = 0; i < 30; i++) {
            resp = client.createMessage(null, msgs, tools, 4096);
            msgs.add(DashScopeClient.assistantMessageToMap(resp));
            if (!DashScopeClient.hasToolCalls(resp)) break;

            JsonArray toolCalls = DashScopeClient.getToolCalls(resp);
            for (int j = 0; j < toolCalls.size(); j++) {
                JsonObject tc = toolCalls.get(j).getAsJsonObject();
                String result = CommonTools.dispatch(DashScopeClient.getToolName(tc), DashScopeClient.getToolArguments(tc));
                msgs.add(DashScopeClient.toolResultMessage(DashScopeClient.getToolCallId(tc), result));
            }
        }
        return resp != null ? DashScopeClient.extractText(resp) : "(子Agent 执行失败)";
    }

    // ==================== 全局实例 ====================

    private static final TodoManager TODO = new TodoManager();
    private static final SkillLoader SKILLS = new SkillLoader(SKILLS_DIR);
    private static final TaskManager TASK_MGR = new TaskManager();
    private static final BackgroundManager BG = new BackgroundManager();
    private static final MessageBus BUS = new MessageBus();
    private static final TeammateManager TEAM = new TeammateManager(BUS, TASK_MGR);

    private static final String SYSTEM_PROMPT =
            "你是一个全能编程助手，工作目录是 " + CommonTools.WORKDIR + "。\n" +
            "你可以执行命令、管理任务、孵化队员、加载技能。\n" +
            "可用技能:\n" + SKILLS.descriptions();

    // ==================== 工具定义 ====================

    private static List<Map<String, Object>> buildTools() {
        List<Map<String, Object>> tools = new ArrayList<>(CommonTools.allBasicToolDefs());

        // S03 计划管理
        tools.add(DashScopeClient.toolDefinition("TodoWrite", "更新待办列表",
                Map.of("type", "object", "properties", Map.of(
                        "items", Map.of("type", "array", "items", Map.of("type", "object"))),
                        "required", List.of("items"))));
        // S04 子 Agent
        tools.add(DashScopeClient.toolDefinition("subagent", "启动子 Agent 执行子任务",
                Map.of("type", "object", "properties", Map.of(
                        "prompt", Map.of("type", "string", "description", "子任务描述")),
                        "required", List.of("prompt"))));
        // S05 技能
        tools.add(DashScopeClient.toolDefinition("load_skill", "加载技能详情",
                Map.of("type", "object", "properties", Map.of(
                        "name", Map.of("type", "string")),
                        "required", List.of("name"))));
        // S06 压缩
        tools.add(DashScopeClient.toolDefinition("compress", "压缩上下文（对话太长时使用）",
                Map.of("type", "object", "properties", Map.of())));
        // S12 任务
        tools.add(DashScopeClient.toolDefinition("task_create", "创建任务",
                Map.of("type", "object", "properties", Map.of(
                        "subject", Map.of("type", "string"), "description", Map.of("type", "string")),
                        "required", List.of("subject"))));
        tools.add(DashScopeClient.toolDefinition("task_list", "列出任务",
                Map.of("type", "object", "properties", Map.of())));
        tools.add(DashScopeClient.toolDefinition("task_update", "更新任务状态",
                Map.of("type", "object", "properties", Map.of(
                        "task_id", Map.of("type", "integer"), "status", Map.of("type", "string")),
                        "required", List.of("task_id", "status"))));
        // S13 后台
        tools.add(DashScopeClient.toolDefinition("background_run", "后台运行命令",
                Map.of("type", "object", "properties", Map.of(
                        "command", Map.of("type", "string"), "timeout", Map.of("type", "integer")),
                        "required", List.of("command"))));
        tools.add(DashScopeClient.toolDefinition("check_background", "检查后台任务",
                Map.of("type", "object", "properties", Map.of(
                        "task_id", Map.of("type", "string")))));
        // S15 团队
        tools.add(DashScopeClient.toolDefinition("spawn_teammate", "孵化队员",
                Map.of("type", "object", "properties", Map.of(
                        "name", Map.of("type", "string"), "role", Map.of("type", "string"),
                        "prompt", Map.of("type", "string")),
                        "required", List.of("name", "role", "prompt"))));
        tools.add(DashScopeClient.toolDefinition("list_teammates", "列出队员",
                Map.of("type", "object", "properties", Map.of())));
        tools.add(DashScopeClient.toolDefinition("send_message", "发送消息给队员",
                Map.of("type", "object", "properties", Map.of(
                        "to", Map.of("type", "string"), "content", Map.of("type", "string")),
                        "required", List.of("to", "content"))));
        tools.add(DashScopeClient.toolDefinition("read_inbox", "读取收件箱",
                Map.of("type", "object", "properties", Map.of())));
        tools.add(DashScopeClient.toolDefinition("broadcast", "广播消息",
                Map.of("type", "object", "properties", Map.of(
                        "content", Map.of("type", "string")),
                        "required", List.of("content"))));
        // S16 协议
        tools.add(DashScopeClient.toolDefinition("shutdown_teammate", "关闭队员",
                Map.of("type", "object", "properties", Map.of(
                        "name", Map.of("type", "string")),
                        "required", List.of("name"))));

        return tools;
    }

    // ==================== 工具分发 ====================

    private static String dispatchTool(String name, JsonObject args, String toolCallId) {
        String result = switch (name) {
            case "bash", "read_file", "write_file", "edit_file" -> CommonTools.dispatch(name, args);
            case "TodoWrite" -> TODO.update(args.getAsJsonArray("items"));
            case "subagent" -> runSubagent(args.get("prompt").getAsString());
            case "load_skill" -> SKILLS.load(args.get("name").getAsString());
            case "compress" -> "压缩中...";
            case "task_create" -> TASK_MGR.create(args.get("subject").getAsString(),
                    args.has("description") ? args.get("description").getAsString() : "");
            case "task_list" -> TASK_MGR.listAll();
            case "task_update" -> TASK_MGR.update(args.get("task_id").getAsInt(), args.get("status").getAsString());
            case "background_run" -> BG.run(args.get("command").getAsString(),
                    args.has("timeout") ? args.get("timeout").getAsInt() : 120);
            case "check_background" -> BG.check(args.has("task_id") ? args.get("task_id").getAsString() : null);
            case "spawn_teammate" -> TEAM.spawn(args.get("name").getAsString(),
                    args.get("role").getAsString(), args.get("prompt").getAsString());
            case "list_teammates" -> TEAM.listAll();
            case "send_message" -> BUS.send("lead", args.get("to").getAsString(), args.get("content").getAsString());
            case "read_inbox" -> DashScopeClient.gson().toJson(BUS.readInbox("lead"));
            case "broadcast" -> BUS.broadcast("lead", args.get("content").getAsString(), TEAM.memberNames());
            case "shutdown_teammate" -> {
                BUS.send("lead", args.get("name").getAsString(), "请关机", "shutdown_request");
                yield "已发送关机请求给 " + args.get("name").getAsString();
            }
            default -> "未知工具: " + name;
        };

        // 大输出持久化（S06）
        if ("bash".equals(name) || "read_file".equals(name)) {
            result = maybePersist(toolCallId, result);
        }
        return result;
    }

    // ==================== Agent 主循环 ====================

    private static final List<Map<String, Object>> TOOLS = buildTools();

    private static void agentLoop(List<Map<String, Object>> messages) {
        int roundsWithoutTodo = 0;

        for (int turn = 0; turn < 50; turn++) {
            // 微压缩
            microCompact(messages);

            // 自动压缩检查
            if (estimateTokens(messages) > TOKEN_THRESHOLD) {
                System.out.println("[自动压缩触发]");
                List<Map<String, Object>> compacted = autoCompact(messages);
                messages.clear();
                messages.addAll(compacted);
            }

            // 注入后台通知
            List<JsonObject> notifs = BG.drain();
            if (!notifs.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (JsonObject n : notifs) {
                    sb.append("[后台:").append(n.get("task_id").getAsString()).append("] ")
                      .append(n.get("status").getAsString()).append("\n");
                }
                messages.add(DashScopeClient.userMessage("<background-results>\n" + sb + "</background-results>"));
            }

            // 注入收件箱
            List<JsonObject> inbox = BUS.readInbox("lead");
            if (!inbox.isEmpty()) {
                messages.add(DashScopeClient.userMessage("<inbox>" + DashScopeClient.gson().toJson(inbox) + "</inbox>"));
            }

            // 调用模型
            JsonObject response = client.createMessage(SYSTEM_PROMPT, messages, TOOLS, 4096);
            messages.add(DashScopeClient.assistantMessageToMap(response));

            if (!DashScopeClient.hasToolCalls(response)) return;

            // 执行工具
            JsonArray toolCalls = DashScopeClient.getToolCalls(response);
            boolean usedTodo = false;
            boolean manualCompress = false;

            for (int i = 0; i < toolCalls.size(); i++) {
                JsonObject tc = toolCalls.get(i).getAsJsonObject();
                String toolName = DashScopeClient.getToolName(tc);
                JsonObject toolArgs = DashScopeClient.getToolArguments(tc);
                String toolId = DashScopeClient.getToolCallId(tc);

                if ("compress".equals(toolName)) manualCompress = true;
                if ("TodoWrite".equals(toolName)) usedTodo = true;

                String result;
                try { result = dispatchTool(toolName, toolArgs, toolId); }
                catch (Exception e) { result = "错误: " + e.getMessage(); }

                System.out.println("  [工具] " + toolName + " → " + result.substring(0, Math.min(120, result.length())));
                messages.add(DashScopeClient.toolResultMessage(toolId, result));
            }

            // 待办提醒（S03）
            roundsWithoutTodo = usedTodo ? 0 : roundsWithoutTodo + 1;
            if (TODO.hasOpenItems() && roundsWithoutTodo >= 3) {
                messages.add(DashScopeClient.userMessage("<reminder>请更新你的待办列表。</reminder>"));
            }

            // 手动压缩
            if (manualCompress) {
                System.out.println("[手动压缩]");
                List<Map<String, Object>> compacted = autoCompact(messages);
                messages.clear();
                messages.addAll(compacted);
            }
        }
    }

    // ==================== REPL 入口 ====================

    public static void main(String[] args) throws Exception {
        System.out.println("=== SFull 完整 Agent（S01~S18 整合版）===");
        System.out.println("工作目录: " + CommonTools.WORKDIR);
        System.out.println("命令: /compact /tasks /team /inbox   输入 q 退出\n");

        List<Map<String, Object>> messages = new ArrayList<>();
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));

        while (true) {
            System.out.print("full >> ");
            String input = reader.readLine();
            if (input == null || input.trim().equalsIgnoreCase("q") || input.trim().equalsIgnoreCase("exit")) break;
            if (input.trim().isEmpty()) continue;

            // REPL 命令
            switch (input.trim()) {
                case "/compact" -> {
                    if (!messages.isEmpty()) {
                        System.out.println("[手动压缩]");
                        List<Map<String, Object>> c = autoCompact(messages);
                        messages.clear();
                        messages.addAll(c);
                    }
                    continue;
                }
                case "/tasks" -> { System.out.println(TASK_MGR.listAll()); continue; }
                case "/team" -> { System.out.println(TEAM.listAll()); continue; }
                case "/inbox" -> { System.out.println(DashScopeClient.gson().toJson(BUS.readInbox("lead"))); continue; }
            }

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
