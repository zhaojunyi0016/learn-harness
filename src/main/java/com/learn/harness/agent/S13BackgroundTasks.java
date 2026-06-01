package com.learn.harness.agent;

/**
 * 【第十三章】后台任务（Background Tasks）
 *
 * 本章教的是并发执行：让耗时命令在后台运行，主循环不被阻塞。
 *
 * 问题背景：
 * 有些命令很慢（编译、测试、下载），如果同步等待，Agent 就卡住了。
 * 用户什么都做不了，只能干等。
 *
 * 解决方案：
 * - background_run：异步执行命令，立即返回任务 ID
 * - check_background：查询后台任务状态和结果
 * - 每轮循环开始前，自动检查是否有后台任务完成，把结果注入对话
 *
 * 核心教学点：
 * - ExecutorService / Thread 做异步执行
 * - BlockingQueue 做通知：后台线程完成后往队列里塞通知
 * - 主循环每轮 drain 通知队列，把完成的结果告诉模型
 *
 * 运行方式：
 *   export DASHSCOPE_API_KEY="your-key"
 *   mvn compile exec:java -Dexec.mainClass="com.learn.harness.agent.S13BackgroundTasks"
 */

import com.google.gson.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public class S13BackgroundTasks {

    // ==================== 配置 ====================

    private static final DashScopeClient client = new DashScopeClient();

    private static final String SYSTEM_PROMPT =
            "你是一个编程助手，工作目录是 " + CommonTools.WORKDIR + "。\n" +
            "对于耗时命令（编译、测试等），使用 background_run 在后台执行。\n" +
            "用 check_background 查看后台任务状态。";

    // ==================== 后台任务管理器 ====================

    /**
     * 后台任务管理器
     * <p>
     * 核心组件：
     * - tasks: 所有后台任务的状态表（线程安全）
     * - notifications: 完成通知队列（后台线程 → 主循环）
     */
    static class BackgroundManager {
        private final Map<String, TaskInfo> tasks = new ConcurrentHashMap<>();
        private final BlockingQueue<String> notifications = new LinkedBlockingQueue<>();

        /** 后台任务信息 */
        static class TaskInfo {
            final String id;
            final String command;
            volatile String status; // running / completed / error / timeout
            volatile String result;

            TaskInfo(String id, String command) {
                this.id = id;
                this.command = command;
                this.status = "running";
                this.result = "";
            }
        }

        /**
         * 提交后台任务
         * <p>
         * 开一个新线程执行命令，主线程立即返回任务 ID。
         * 为什么用线程而不是 ExecutorService？
         * 教学目的——让代码更直观。生产环境推荐用线程池。
         */
        String submit(String command) {
            String taskId = UUID.randomUUID().toString().substring(0, 8);
            TaskInfo info = new TaskInfo(taskId, command);
            tasks.put(taskId, info);

            new Thread(() -> execute(info), "bg-" + taskId).start();
            return "后台任务 " + taskId + " 已启动: " + command.substring(0, Math.min(80, command.length()));
        }

        /** 在后台线程中执行命令 */
        private void execute(TaskInfo info) {
            try {
                ProcessBuilder pb = new ProcessBuilder("sh", "-c", info.command);
                pb.directory(new java.io.File(CommonTools.WORKDIR));
                pb.redirectErrorStream(true);
                Process process = pb.start();
                boolean finished = process.waitFor(300, TimeUnit.SECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    info.status = "timeout";
                    info.result = "错误: 超时（300秒）";
                } else {
                    info.result = new String(process.getInputStream().readAllBytes()).trim();
                    if (info.result.isEmpty()) info.result = "(无输出)";
                    if (info.result.length() > 50000) info.result = info.result.substring(0, 50000);
                    info.status = "completed";
                }
            } catch (Exception e) {
                info.status = "error";
                info.result = "错误: " + e.getMessage();
            }
            // 往通知队列发通知
            String preview = info.result.substring(0, Math.min(200, info.result.length()));
            notifications.offer("[后台:" + info.id + "] " + info.status + " - " + preview);
        }

        /** 查询任务状态 */
        String check(String taskId) {
            if (taskId != null && !taskId.isEmpty()) {
                TaskInfo info = tasks.get(taskId);
                if (info == null) return "错误: 未知任务 " + taskId;
                return "[" + info.status + "] " + info.result.substring(0, Math.min(500, info.result.length()));
            }
            // 列出所有任务
            if (tasks.isEmpty()) return "没有后台任务。";
            StringBuilder sb = new StringBuilder();
            tasks.forEach((id, info) -> sb.append(id).append(": [").append(info.status).append("] ")
                    .append(info.command.substring(0, Math.min(60, info.command.length()))).append("\n"));
            return sb.toString().trim();
        }

        /** 取出所有完成通知 */
        List<String> drainNotifications() {
            List<String> result = new ArrayList<>();
            notifications.drainTo(result);
            return result;
        }
    }

    private static final BackgroundManager bgManager = new BackgroundManager();

    // ==================== 工具定义 ====================

    private static List<Map<String, Object>> buildTools() {
        List<Map<String, Object>> tools = new ArrayList<>(CommonTools.allBasicToolDefs());
        tools.add(DashScopeClient.toolDefinition("background_run",
                "在后台执行命令（立即返回任务ID，不阻塞）",
                Map.of("type", "object", "properties", Map.of(
                        "command", Map.of("type", "string", "description", "要执行的命令")
                ), "required", List.of("command"))));
        tools.add(DashScopeClient.toolDefinition("check_background",
                "查看后台任务状态（不传 task_id 则列出所有）",
                Map.of("type", "object", "properties", Map.of(
                        "task_id", Map.of("type", "string", "description", "任务ID（可选）")
                ))));
        return tools;
    }

    private static final List<Map<String, Object>> TOOLS = buildTools();

    // ==================== Agent 循环 ====================

    private static void agentLoop(List<Map<String, Object>> messages) {
        int maxTurns = 30;
        for (int turn = 0; turn < maxTurns; turn++) {
            // 每轮开始前，检查后台任务通知
            List<String> notifications = bgManager.drainNotifications();
            if (!notifications.isEmpty()) {
                String notifText = String.join("\n", notifications);
                System.out.println("\033[32m[后台通知]\n" + notifText + "\033[0m");
                messages.add(DashScopeClient.userMessage("[后台任务完成通知]\n" + notifText));
            }

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
                try {
                    result = switch (toolName) {
                        case "background_run" -> bgManager.submit(arguments.get("command").getAsString());
                        case "check_background" -> bgManager.check(
                                arguments.has("task_id") ? arguments.get("task_id").getAsString() : null);
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

        System.out.println("=== S13 Background Tasks 演示 ===");
        System.out.println("Agent 可以把耗时命令放到后台执行");
        System.out.println("输入 q 退出\n");

        while (true) {
            System.out.print("\033[36m[S13] >>> \033[0m");
            String input = reader.readLine();
            if (input == null || input.isBlank() ||
                    "q".equalsIgnoreCase(input.trim()) || "exit".equalsIgnoreCase(input.trim())) break;
            history.add(DashScopeClient.userMessage(input));
            agentLoop(history);
            System.out.println();
        }
    }
}
