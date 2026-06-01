package com.learn.harness.agent;

/**
 * 【第十四章】定时调度（Cron Scheduler）
 *
 * 本章教的是让 Agent 能"记住未来要做的事"——定时任务。
 *
 * 场景示例：
 * - "每小时检查一次服务器状态"
 * - "明天早上 9 点跑一次测试"
 * - "每天下午 5 点生成日报"
 *
 * 实现方式：
 * - 用标准的 Cron 表达式描述时间（分 时 日 月 周）
 * - 后台线程每秒检查一次是否有到期任务
 * - 到期时把任务 prompt 注入对话循环，触发 Agent 执行
 *
 * 核心教学点：
 * - Cron 表达式解析（简化版）
 * - ScheduledExecutorService 做定时轮询
 * - "到期通知"注入主循环的模式（和 S13 后台任务类似）
 *
 * 运行方式：
 *   export DASHSCOPE_API_KEY="your-key"
 *   mvn compile exec:java -Dexec.mainClass="com.learn.harness.agent.S14CronScheduler"
 */

import com.google.gson.*;
import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

public class S14CronScheduler {

    // ==================== 配置 ====================

    private static final DashScopeClient client = new DashScopeClient();

    private static final String SYSTEM_PROMPT =
            "你是一个编程助手，工作目录是 " + CommonTools.WORKDIR + "。\n" +
            "使用 cron_create 来调度定时任务。使用 cron_list 查看所有调度。";

    /** 定时任务自动过期天数 */
    private static final int AUTO_EXPIRY_DAYS = 7;

    // ==================== Cron 表达式匹配 ====================

    /**
     * 检查 Cron 表达式是否匹配给定时间
     * <p>
     * Cron 格式：分 时 日 月 周（5 个字段）
     * 支持：* 通配符、逗号分隔、范围（1-5）、步进（* /5）
     */
    static boolean cronMatches(String expr, LocalDateTime dt) {
        String[] fields = expr.trim().split("\\s+");
        if (fields.length != 5) return false;
        int cronDow = dt.getDayOfWeek().getValue() % 7; // Sunday=0
        int[] values = {dt.getMinute(), dt.getHour(), dt.getDayOfMonth(), dt.getMonthValue(), cronDow};
        for (int i = 0; i < 5; i++) {
            if (!fieldMatches(fields[i], values[i])) return false;
        }
        return true;
    }

    /** 匹配单个 Cron 字段 */
    private static boolean fieldMatches(String field, int value) {
        if ("*".equals(field)) return true;
        for (String part : field.split(",")) {
            int step = 1;
            String base = part;
            if (part.contains("/")) {
                String[] s = part.split("/", 2);
                base = s[0];
                step = Integer.parseInt(s[1]);
            }
            if ("*".equals(base)) {
                if (value % step == 0) return true;
            } else if (base.contains("-")) {
                String[] r = base.split("-", 2);
                int start = Integer.parseInt(r[0]), end = Integer.parseInt(r[1]);
                if (value >= start && value <= end && (value - start) % step == 0) return true;
            } else {
                if (Integer.parseInt(base) == value) return true;
            }
        }
        return false;
    }

    // ==================== 调度器 ====================

    /**
     * 定时任务调度器
     */
    static class CronSchedulerService {
        private final List<CronTask> tasks = new CopyOnWriteArrayList<>();
        private final BlockingQueue<String> queue = new LinkedBlockingQueue<>();
        private volatile boolean running = false;
        private int lastCheckMinute = -1;

        /** 定时任务 */
        static class CronTask {
            final String id;
            final String cronExpr;
            final String prompt;
            final boolean recurring;
            final long createdAt;

            CronTask(String id, String cronExpr, String prompt, boolean recurring) {
                this.id = id;
                this.cronExpr = cronExpr;
                this.prompt = prompt;
                this.recurring = recurring;
                this.createdAt = System.currentTimeMillis();
            }
        }

        /** 启动后台调度线程 */
        void start() {
            running = true;
            new Thread(() -> {
                while (running) {
                    LocalDateTime now = LocalDateTime.now();
                    int currentMinute = now.getHour() * 60 + now.getMinute();
                    if (currentMinute != lastCheckMinute) {
                        lastCheckMinute = currentMinute;
                        checkTasks(now);
                    }
                    try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
                }
            }, "cron-scheduler").start();
        }

        void stop() { running = false; }

        /** 创建定时任务 */
        String create(String cronExpr, String prompt, boolean recurring) {
            String taskId = UUID.randomUUID().toString().substring(0, 8);
            tasks.add(new CronTask(taskId, cronExpr, prompt, recurring));
            String mode = recurring ? "重复" : "一次性";
            return "定时任务 " + taskId + " 已创建 (" + mode + "): cron=" + cronExpr;
        }

        /** 删除定时任务 */
        String delete(String taskId) {
            boolean removed = tasks.removeIf(t -> taskId.equals(t.id));
            return removed ? "已删除任务 " + taskId : "任务 " + taskId + " 不存在";
        }

        /** 列出所有定时任务 */
        String listTasks() {
            if (tasks.isEmpty()) return "暂无定时任务。";
            StringBuilder sb = new StringBuilder();
            for (CronTask t : tasks) {
                String mode = t.recurring ? "重复" : "一次性";
                double ageHours = (System.currentTimeMillis() - t.createdAt) / 3600000.0;
                sb.append("  ").append(t.id).append("  ").append(t.cronExpr)
                        .append(" [").append(mode).append("] (").append(String.format("%.1f", ageHours)).append("h前): ")
                        .append(t.prompt.substring(0, Math.min(60, t.prompt.length()))).append("\n");
            }
            return sb.toString().trim();
        }

        /** 取出到期通知 */
        List<String> drainNotifications() {
            List<String> result = new ArrayList<>();
            queue.drainTo(result);
            return result;
        }

        /** 检查哪些任务到期了 */
        private void checkTasks(LocalDateTime now) {
            List<CronTask> toRemove = new ArrayList<>();
            for (CronTask task : tasks) {
                // 自动过期
                long ageDays = (System.currentTimeMillis() - task.createdAt) / 86400000;
                if (task.recurring && ageDays > AUTO_EXPIRY_DAYS) {
                    toRemove.add(task);
                    continue;
                }
                if (cronMatches(task.cronExpr, now)) {
                    queue.offer("[定时任务 " + task.id + " 触发]: " + task.prompt);
                    System.out.println("\033[32m[Cron] 触发: " + task.id + "\033[0m");
                    if (!task.recurring) toRemove.add(task);
                }
            }
            tasks.removeAll(toRemove);
        }
    }

    private static final CronSchedulerService scheduler = new CronSchedulerService();

    // ==================== 工具定义 ====================

    private static List<Map<String, Object>> buildTools() {
        List<Map<String, Object>> tools = new ArrayList<>(CommonTools.allBasicToolDefs());
        tools.add(DashScopeClient.toolDefinition("cron_create", "创建定时任务",
                Map.of("type", "object", "properties", Map.of(
                        "cron", Map.of("type", "string", "description", "Cron 表达式（分 时 日 月 周）"),
                        "prompt", Map.of("type", "string", "description", "到期时要执行的任务描述"),
                        "recurring", Map.of("type", "boolean", "description", "是否重复执行（默认 true）")
                ), "required", List.of("cron", "prompt"))));
        tools.add(DashScopeClient.toolDefinition("cron_delete", "删除定时任务",
                Map.of("type", "object", "properties", Map.of(
                        "id", Map.of("type", "string", "description", "任务 ID")
                ), "required", List.of("id"))));
        tools.add(DashScopeClient.toolDefinition("cron_list", "列出所有定时任务",
                Map.of("type", "object", "properties", Map.of())));
        return tools;
    }

    private static final List<Map<String, Object>> TOOLS = buildTools();

    // ==================== Agent 循环 ====================

    private static void agentLoop(List<Map<String, Object>> messages) {
        int maxTurns = 25;
        for (int turn = 0; turn < maxTurns; turn++) {
            // 检查定时任务通知
            List<String> notifications = scheduler.drainNotifications();
            for (String note : notifications) {
                System.out.println("\033[32m[定时通知] " + note.substring(0, Math.min(100, note.length())) + "\033[0m");
                messages.add(DashScopeClient.userMessage(note));
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
                        case "cron_create" -> scheduler.create(
                                arguments.get("cron").getAsString(),
                                arguments.get("prompt").getAsString(),
                                !arguments.has("recurring") || arguments.get("recurring").getAsBoolean());
                        case "cron_delete" -> scheduler.delete(arguments.get("id").getAsString());
                        case "cron_list" -> scheduler.listTasks();
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
        scheduler.start();

        List<Map<String, Object>> history = new ArrayList<>();
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));

        System.out.println("=== S14 Cron Scheduler 演示 ===");
        System.out.println("[定时调度器已启动，每秒检查一次]");
        System.out.println("命令: /cron 查看所有定时任务");
        System.out.println("输入 q 退出\n");

        while (true) {
            System.out.print("\033[36m[S14] >>> \033[0m");
            String input = reader.readLine();
            if (input == null || input.isBlank() ||
                    "q".equalsIgnoreCase(input.trim()) || "exit".equalsIgnoreCase(input.trim())) {
                scheduler.stop();
                break;
            }
            if ("/cron".equals(input.trim())) {
                System.out.println(scheduler.listTasks());
                System.out.println();
                continue;
            }
            history.add(DashScopeClient.userMessage(input));
            agentLoop(history);
            System.out.println();
        }
    }
}
