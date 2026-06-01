package com.learn.harness.agent;

/**
 * 【第十九章】MCP 外部工具插件
 *
 * 本章演示如何通过 MCP（Model Context Protocol）协议加载外部工具。
 *
 * 核心思路：
 * 外部工具不应该是"另一个世界"，而应该和内置工具走同一条管道。
 * 模型不需要知道工具是本地实现的还是通过 MCP 服务器提供的。
 *
 * MCP 协议要点：
 * - 通过 stdio 与外部进程通信（stdin/stdout）
 * - 使用 JSON-RPC 2.0 格式（method + params → result/error）
 * - 标准生命周期：initialize → tools/list → tools/call → shutdown
 *
 * 架构组件：
 * - CapabilityPermissionGate：统一的风险分级（read/write/high）
 * - MCPClient：与单个 MCP 服务器的 stdio 通信
 * - MCPToolRouter：前缀路由（mcp__{server}__{tool}）
 * - 统一工具池：内置工具 + MCP 工具混合在一起给模型用
 *
 * 权限模型：
 * - read 级别的工具（get/list/read/search）→ 直接允许
 * - write 级别的工具（create/update/write）→ 需要用户确认
 * - high 级别的工具（delete/remove/shutdown）→ 强制用户确认
 * - auto 模式：write 级别也直接允许（只拦截 high）
 *
 * 运行方式：
 *   export DASHSCOPE_API_KEY="your-key"
 *   mvn compile exec:java -Dexec.mainClass="com.learn.harness.agent.S19McpPlugin"
 */

import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public class S19McpPlugin {

    // ==================== 配置 ====================

    private static final DashScopeClient client = new DashScopeClient();

    private static final String SYSTEM_PROMPT =
            "你是一个编程助手，工作目录是 " + CommonTools.WORKDIR + "。\n" +
            "你可以使用内置工具和 MCP 外部工具。MCP 工具以 mcp__{server}__{tool} 格式命名。\n" +
            "所有工具调用都会经过权限检查。";

    // ==================== 权限门控 ====================
    // 统一对所有工具（内置 + MCP）进行风险分级。

    static class PermissionGate {
        private static final String[] READ_PREFIXES = {"read", "list", "get", "show", "search", "query"};
        private static final String[] HIGH_PREFIXES = {"delete", "remove", "drop", "shutdown"};
        private final String mode; // "default" 或 "auto"

        PermissionGate(String mode) { this.mode = "auto".equals(mode) ? "auto" : "default"; }

        /** 分析工具的风险等级 */
        String classifyRisk(String toolName, JsonObject input) {
            String actualTool = toolName.contains("__")
                    ? toolName.substring(toolName.lastIndexOf("__") + 2) : toolName;
            String lowered = actualTool.toLowerCase();

            // bash 特殊处理：看命令内容
            if ("bash".equals(actualTool) && input != null && input.has("command")) {
                String cmd = input.get("command").getAsString();
                if (cmd.contains("rm -rf") || cmd.contains("sudo") || cmd.contains("shutdown")) {
                    return "high";
                }
                return "write";
            }
            // 前缀匹配
            for (String p : READ_PREFIXES) if (lowered.startsWith(p)) return "read";
            for (String p : HIGH_PREFIXES) if (lowered.startsWith(p)) return "high";
            return "write";
        }

        /**
         * 检查是否允许执行
         * @return "allow"、"ask" 或 "deny"
         */
        String check(String toolName, JsonObject input) {
            String risk = classifyRisk(toolName, input);
            if ("read".equals(risk)) return "allow";
            if ("auto".equals(mode) && !"high".equals(risk)) return "allow";
            return "ask"; // 需要用户确认
        }

        /** 向用户询问确认 */
        boolean askUser(String toolName, JsonObject input) {
            String risk = classifyRisk(toolName, input);
            String preview = input != null ? DashScopeClient.gson().toJson(input) : "{}";
            if (preview.length() > 150) preview = preview.substring(0, 150) + "...";
            System.out.println("\n  [权限] " + toolName + " (风险:" + risk + ")");
            System.out.println("  参数: " + preview);
            System.out.print("  允许执行? (y/n): ");
            try {
                BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
                String answer = br.readLine().trim().toLowerCase();
                return "y".equals(answer) || "yes".equals(answer);
            } catch (Exception e) { return false; }
        }
    }

    // ==================== MCP 客户端 ====================
    // 与单个 MCP 服务器通过 stdio 通信的客户端。
    // JSON-RPC 2.0：发送 {jsonrpc, id, method, params}，接收 {jsonrpc, id, result/error}

    static class MCPClient {
        private final String serverName;
        private final String command;
        private final List<String> args;
        private Process process;
        private int requestId = 0;
        private final List<JsonObject> tools = new ArrayList<>();

        MCPClient(String serverName, String command, List<String> args) {
            this.serverName = serverName;
            this.command = command;
            this.args = args != null ? args : List.of();
        }

        /** 连接到 MCP 服务器（启动进程 + 握手） */
        boolean connect() {
            try {
                List<String> cmd = new ArrayList<>();
                cmd.add(command);
                cmd.addAll(args);
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(false);
                process = pb.start();

                // 发送 initialize 请求
                JsonObject initParams = new JsonObject();
                initParams.addProperty("protocolVersion", "2024-11-05");
                initParams.add("capabilities", new JsonObject());
                JsonObject clientInfo = new JsonObject();
                clientInfo.addProperty("name", "teaching-agent");
                clientInfo.addProperty("version", "1.0");
                initParams.add("clientInfo", clientInfo);
                JsonObject resp = sendAndRecv("initialize", initParams);

                if (resp != null && resp.has("result")) {
                    // 发送 initialized 通知
                    sendNotification("notifications/initialized");
                    return true;
                }
            } catch (Exception e) {
                System.out.println("[MCP] 连接失败: " + e.getMessage());
            }
            return false;
        }

        /** 获取服务器提供的工具列表 */
        List<JsonObject> listTools() {
            JsonObject resp = sendAndRecv("tools/list", new JsonObject());
            if (resp != null && resp.has("result")) {
                tools.clear();
                for (JsonElement t : resp.getAsJsonObject("result").getAsJsonArray("tools")) {
                    tools.add(t.getAsJsonObject());
                }
            }
            return tools;
        }

        /** 调用 MCP 服务器上的工具 */
        String callTool(String toolName, JsonObject arguments) {
            JsonObject params = new JsonObject();
            params.addProperty("name", toolName);
            params.add("arguments", arguments);
            JsonObject resp = sendAndRecv("tools/call", params);

            if (resp != null && resp.has("result")) {
                JsonArray content = resp.getAsJsonObject("result").getAsJsonArray("content");
                StringBuilder sb = new StringBuilder();
                for (JsonElement c : content) {
                    JsonObject co = c.getAsJsonObject();
                    sb.append(co.has("text") ? co.get("text").getAsString() : co.toString()).append("\n");
                }
                return sb.toString().trim();
            }
            if (resp != null && resp.has("error")) {
                return "MCP 错误: " + resp.getAsJsonObject("error").get("message").getAsString();
            }
            return "MCP 错误: 无响应";
        }

        /** 获取适合给模型用的工具定义（带 mcp__ 前缀） */
        List<Map<String, Object>> getToolDefinitions() {
            List<Map<String, Object>> defs = new ArrayList<>();
            for (JsonObject tool : tools) {
                String name = "mcp__" + serverName + "__" + tool.get("name").getAsString();
                String desc = tool.has("description") ? tool.get("description").getAsString() : "";
                Map<String, Object> params = tool.has("inputSchema")
                        ? DashScopeClient.gson().fromJson(tool.get("inputSchema"), Map.class)
                        : Map.of("type", "object", "properties", Map.of());
                defs.add(DashScopeClient.toolDefinition(name, "[MCP:" + serverName + "] " + desc, params));
            }
            return defs;
        }

        /** 断开连接 */
        void disconnect() {
            if (process != null) {
                try { sendNotification("shutdown"); process.destroy(); process.waitFor(); }
                catch (Exception e) { process.destroyForcibly(); }
                process = null;
            }
        }

        // --- 底层通信 ---

        private JsonObject sendAndRecv(String method, JsonObject params) {
            send(method, params, true);
            return recv();
        }

        private void sendNotification(String method) {
            send(method, new JsonObject(), false);
        }

        private void send(String method, JsonObject params, boolean needResponse) {
            if (process == null || !process.isAlive()) return;
            requestId++;
            JsonObject envelope = new JsonObject();
            envelope.addProperty("jsonrpc", "2.0");
            envelope.addProperty("id", requestId);
            envelope.addProperty("method", method);
            envelope.add("params", params);
            try {
                String json = DashScopeClient.gson().toJson(envelope) + "\n";
                process.getOutputStream().write(json.getBytes());
                process.getOutputStream().flush();
            } catch (IOException ignored) {}
        }

        private JsonObject recv() {
            if (process == null || !process.isAlive()) return null;
            try {
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                String line = reader.readLine();
                if (line != null) return JsonParser.parseString(line).getAsJsonObject();
            } catch (Exception ignored) {}
            return null;
        }
    }

    // ==================== MCP 工具路由 ====================
    // 根据工具名前缀（mcp__{server}__{tool}）路由到对应的 MCP 客户端。

    static class MCPToolRouter {
        private final Map<String, MCPClient> clients = new LinkedHashMap<>();

        void registerClient(MCPClient c) { clients.put(c.serverName, c); }

        boolean isMcpTool(String name) { return name.startsWith("mcp__"); }

        String call(String toolName, JsonObject arguments) {
            String[] parts = toolName.split("__", 3);
            if (parts.length != 3) return "错误: 无效的 MCP 工具名: " + toolName;
            MCPClient c = clients.get(parts[1]);
            if (c == null) return "错误: MCP 服务器不存在: " + parts[1];
            return c.callTool(parts[2], arguments);
        }

        /** 获取所有 MCP 工具的定义（给模型用） */
        List<Map<String, Object>> getAllToolDefs() {
            List<Map<String, Object>> defs = new ArrayList<>();
            for (MCPClient c : clients.values()) defs.addAll(c.getToolDefinitions());
            return defs;
        }

        int toolCount() {
            return clients.values().stream().mapToInt(c -> c.tools.size()).sum();
        }
    }

    // ==================== 插件发现 ====================
    // 扫描工作目录下的 .mcp-plugin/plugin.json 来发现 MCP 服务器配置。

    static class PluginLoader {
        /** 从 plugin.json 加载 MCP 服务器配置 */
        static Map<String, JsonObject> scan(Path dir) {
            Map<String, JsonObject> servers = new LinkedHashMap<>();
            Path manifest = dir.resolve(".mcp-plugin").resolve("plugin.json");
            if (Files.exists(manifest)) {
                try {
                    JsonObject obj = JsonParser.parseString(Files.readString(manifest)).getAsJsonObject();
                    if (obj.has("mcpServers")) {
                        for (var entry : obj.getAsJsonObject("mcpServers").entrySet()) {
                            servers.put(entry.getKey(), entry.getValue().getAsJsonObject());
                        }
                    }
                } catch (Exception e) {
                    System.out.println("[插件] 解析失败: " + e.getMessage());
                }
            }
            return servers;
        }
    }

    // ==================== 全局实例 ====================

    private static final PermissionGate GATE = new PermissionGate("default");
    private static final MCPToolRouter ROUTER = new MCPToolRouter();

    // ==================== 统一工具池 ====================
    // 把内置工具和 MCP 工具合并成一个列表给模型。

    private static List<Map<String, Object>> buildToolPool() {
        List<Map<String, Object>> pool = new ArrayList<>(CommonTools.allBasicToolDefs());
        pool.addAll(ROUTER.getAllToolDefs());
        return pool;
    }

    // ==================== 工具分发（带权限检查） ====================

    private static String handleTool(String toolName, JsonObject input) {
        // 权限检查
        String decision = GATE.check(toolName, input);
        if ("ask".equals(decision)) {
            if (!GATE.askUser(toolName, input)) {
                return "用户拒绝执行";
            }
        }

        // 分发执行
        if (ROUTER.isMcpTool(toolName)) {
            return ROUTER.call(toolName, input);
        }
        return CommonTools.dispatch(toolName, input);
    }

    // ==================== Agent 循环 ====================

    private static void agentLoop(List<Map<String, Object>> messages) {
        List<Map<String, Object>> tools = buildToolPool();

        for (int turn = 0; turn < 30; turn++) {
            JsonObject response = client.createMessage(SYSTEM_PROMPT, messages, tools, 4096);
            messages.add(DashScopeClient.assistantMessageToMap(response));

            if (!DashScopeClient.hasToolCalls(response)) return;

            JsonArray toolCalls = DashScopeClient.getToolCalls(response);
            for (int i = 0; i < toolCalls.size(); i++) {
                JsonObject tc = toolCalls.get(i).getAsJsonObject();
                String toolName = DashScopeClient.getToolName(tc);
                JsonObject toolArgs = DashScopeClient.getToolArguments(tc);
                String toolId = DashScopeClient.getToolCallId(tc);

                String result;
                try { result = handleTool(toolName, toolArgs); }
                catch (Exception e) { result = "错误: " + e.getMessage(); }

                String prefix = ROUTER.isMcpTool(toolName) ? "[MCP] " : "[内置] ";
                System.out.println("  " + prefix + toolName + " → " +
                        result.substring(0, Math.min(120, result.length())));
                messages.add(DashScopeClient.toolResultMessage(toolId, result));
            }
        }
    }

    // ==================== REPL 入口 ====================

    public static void main(String[] args) throws Exception {
        System.out.println("=== S19 MCP 外部工具插件 ===");

        // 扫描插件
        Map<String, JsonObject> servers = PluginLoader.scan(Path.of(CommonTools.WORKDIR));
        if (!servers.isEmpty()) {
            System.out.println("[发现 MCP 服务器配置: " + String.join(", ", servers.keySet()) + "]");
            for (var entry : servers.entrySet()) {
                JsonObject config = entry.getValue();
                MCPClient mc = new MCPClient(
                        entry.getKey(),
                        config.has("command") ? config.get("command").getAsString() : "",
                        config.has("args")
                                ? config.getAsJsonArray("args").asList().stream()
                                    .map(JsonElement::getAsString).toList()
                                : List.of());
                if (mc.connect()) {
                    mc.listTools();
                    ROUTER.registerClient(mc);
                    System.out.println("[MCP] 已连接: " + entry.getKey()
                            + " (" + mc.tools.size() + " 个工具)");
                }
            }
        }

        List<Map<String, Object>> toolPool = buildToolPool();
        System.out.println("[工具池: " + toolPool.size() + " 个工具"
                + " (其中 " + ROUTER.toolCount() + " 个来自 MCP)]");
        System.out.println("命令: /tools 查看工具列表, /mcp 查看 MCP 状态");
        System.out.println("输入问题开始交互，q 退出\n");

        List<Map<String, Object>> messages = new ArrayList<>();
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));

        while (true) {
            System.out.print("s19 >> ");
            String input = reader.readLine();
            if (input == null || input.trim().equalsIgnoreCase("q") || input.trim().equalsIgnoreCase("exit")) break;
            if (input.trim().isEmpty()) continue;

            // 特殊命令
            if ("/tools".equals(input.trim())) {
                for (Map<String, Object> t : buildToolPool()) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> fn = (Map<String, Object>) t.get("function");
                    String name = (String) fn.get("name");
                    String desc = (String) fn.get("description");
                    String prefix = name.startsWith("mcp__") ? "[MCP] " : "      ";
                    System.out.println("  " + prefix + name + ": " + desc);
                }
                continue;
            }
            if ("/mcp".equals(input.trim())) {
                if (ROUTER.clients.isEmpty()) System.out.println("  (无 MCP 服务器连接)");
                else ROUTER.clients.forEach((n, c) ->
                        System.out.println("  " + n + ": " + c.tools.size() + " 个工具"));
                continue;
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

        // 清理：断开所有 MCP 连接
        ROUTER.clients.values().forEach(MCPClient::disconnect);
        System.out.println("已断开所有 MCP 连接，再见！");
    }
}
