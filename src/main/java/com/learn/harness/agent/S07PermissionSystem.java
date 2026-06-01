package com.learn.harness.agent;

/**
 * 【第七章】权限系统（Permission System）
 *
 * 本章教的是 Agent 安全的核心模式：在工具执行前做权限检查。
 *
 * 为什么需要权限系统？
 * AI 模型可能生成危险操作（删除文件、执行 sudo、修改系统配置等）。
 * 不能什么都让它执行，需要一个"关卡"来过滤。
 *
 * 设计思想：权限是一条管道（Pipeline），不是一个 boolean
 * 管道从上到下依次判断：
 * 1. 拒绝规则（deny）：黑名单，匹配就直接拒绝
 * 2. 模式检查（mode）：plan 模式只读，auto 模式自动放行读操作
 * 3. 允许规则（allow）：白名单，匹配就放行
 * 4. 询问用户（ask）：前面都没匹配到，让用户决定
 *
 * 核心教学点：
 * - 管道顺序很重要：deny 永远最先检查（安全优先）
 * - 模式（mode）是一种粗粒度的权限控制
 * - 用户可以动态添加 allow 规则（"always" 选项）
 * - 这个模式可以扩展到任意细粒度的权限控制
 *
 * 运行方式：
 *   export DASHSCOPE_API_KEY="your-key"
 *   mvn compile exec:java -Dexec.mainClass="com.learn.harness.agent.S07PermissionSystem"
 */

import com.google.gson.*;
import java.io.*;
import java.util.*;

public class S07PermissionSystem {

    // ==================== 配置 ====================

    private static final DashScopeClient client = new DashScopeClient();

    /** 支持的模式 */
    private static final Set<String> MODES = Set.of("default", "plan", "auto");

    /** 只读工具集（plan 模式下可用） */
    private static final Set<String> READ_ONLY_TOOLS = Set.of("read_file", "bash");

    /** 写入工具集（plan 模式下被阻止） */
    private static final Set<String> WRITE_TOOLS = Set.of("write_file", "edit_file");

    private static final String SYSTEM_PROMPT =
            "你是一个编程助手，工作目录是 " + CommonTools.WORKDIR + "。\n" +
            "使用工具来完成任务。注意：部分操作可能需要用户授权。";

    // ==================== 权限管理器 ====================

    /**
     * 权限检查结果
     */
    enum Decision {
        ALLOW,  // 允许执行
        DENY,   // 拒绝执行
        ASK     // 需要询问用户
    }

    /**
     * 权限规则
     */
    static class PermissionRule {
        final String toolPattern;  // 工具名匹配（* 表示所有）
        final String contentPattern; // 内容匹配（用于 bash 命令等）
        final Decision decision;   // 匹配后的决策

        PermissionRule(String toolPattern, String contentPattern, Decision decision) {
            this.toolPattern = toolPattern;
            this.contentPattern = contentPattern;
            this.decision = decision;
        }
    }

    /**
     * 权限管理器：维护规则列表 + 执行管道检查
     */
    static class PermissionManager {
        private final String mode;
        private final List<PermissionRule> rules = new ArrayList<>();
        private final BufferedReader userInput;

        PermissionManager(String mode, BufferedReader userInput) {
            this.mode = MODES.contains(mode) ? mode : "default";
            this.userInput = userInput;

            // 初始化默认拒绝规则（安全底线）
            rules.add(new PermissionRule("bash", "rm -rf /", Decision.DENY));
            rules.add(new PermissionRule("bash", "sudo", Decision.DENY));
            rules.add(new PermissionRule("bash", "shutdown", Decision.DENY));
            // 默认允许规则
            rules.add(new PermissionRule("read_file", "*", Decision.ALLOW));
        }

        /**
         * 权限管道检查
         * <p>
         * 按顺序经过四层检查，返回最终决策。
         */
        Decision check(String toolName, JsonObject arguments) {
            // 第一层：拒绝规则
            for (PermissionRule rule : rules) {
                if (rule.decision != Decision.DENY) continue;
                if (matchesRule(rule, toolName, arguments)) {
                    System.out.println("  \033[31m[权限] 被拒绝规则阻止\033[0m");
                    return Decision.DENY;
                }
            }

            // 第二层：模式检查
            if ("plan".equals(mode)) {
                if (WRITE_TOOLS.contains(toolName)) {
                    System.out.println("  \033[31m[权限] Plan 模式下不允许写操作\033[0m");
                    return Decision.DENY;
                }
                return Decision.ALLOW; // plan 模式下读操作直接放行
            }
            if ("auto".equals(mode) && READ_ONLY_TOOLS.contains(toolName)) {
                return Decision.ALLOW; // auto 模式下读操作自动放行
            }

            // 第三层：允许规则
            for (PermissionRule rule : rules) {
                if (rule.decision != Decision.ALLOW) continue;
                if (matchesRule(rule, toolName, arguments)) {
                    return Decision.ALLOW;
                }
            }

            // 第四层：询问用户
            return Decision.ASK;
        }

        /**
         * 询问用户是否允许执行
         *
         * @return true=允许，false=拒绝
         */
        boolean askUser(String toolName, JsonObject arguments) {
            System.out.println("\n  \033[33m[权限请求] " + toolName + ": " +
                    arguments.toString().substring(0, Math.min(150, arguments.toString().length())) + "\033[0m");
            System.out.print("  允许执行? (y=允许 / n=拒绝 / always=始终允许此工具): ");
            try {
                String answer = userInput.readLine().trim().toLowerCase();
                if ("always".equals(answer)) {
                    // 动态添加 allow 规则
                    rules.add(new PermissionRule(toolName, "*", Decision.ALLOW));
                    System.out.println("  \033[32m[已添加规则] " + toolName + " 始终允许\033[0m");
                    return true;
                }
                return "y".equals(answer) || "yes".equals(answer);
            } catch (Exception e) {
                return false;
            }
        }

        /** 检查规则是否匹配 */
        private boolean matchesRule(PermissionRule rule, String toolName, JsonObject arguments) {
            // 工具名匹配
            if (!"*".equals(rule.toolPattern) && !rule.toolPattern.equals(toolName)) {
                return false;
            }
            // 内容匹配（仅对有 command 参数的工具检查）
            if (!"*".equals(rule.contentPattern) && arguments.has("command")) {
                String command = arguments.get("command").getAsString();
                if (!command.contains(rule.contentPattern)) {
                    return false;
                }
            }
            return true;
        }
    }

    // ==================== Agent 循环 ====================

    private static void agentLoop(List<Map<String, Object>> messages, PermissionManager perms) {
        int maxTurns = 25;
        for (int turn = 0; turn < maxTurns; turn++) {
            JsonObject response = client.createMessage(SYSTEM_PROMPT, messages, CommonTools.allBasicToolDefs(), 4096);
            messages.add(DashScopeClient.assistantMessageToMap(response));

            if (!DashScopeClient.hasToolCalls(response)) {
                return;
            }

            JsonArray toolCalls = DashScopeClient.getToolCalls(response);
            for (int i = 0; i < toolCalls.size(); i++) {
                JsonObject toolCall = toolCalls.get(i).getAsJsonObject();
                String toolName = DashScopeClient.getToolName(toolCall);
                JsonObject arguments = DashScopeClient.getToolArguments(toolCall);
                String toolCallId = DashScopeClient.getToolCallId(toolCall);

                // 权限检查
                Decision decision = perms.check(toolName, arguments);
                String result;
                switch (decision) {
                    case DENY -> result = "权限被拒绝：该操作不被允许";
                    case ASK -> {
                        if (perms.askUser(toolName, arguments)) {
                            result = CommonTools.dispatch(toolName, arguments);
                        } else {
                            result = "权限被拒绝：用户拒绝了该操作";
                        }
                    }
                    default -> result = CommonTools.dispatch(toolName, arguments);
                }

                System.out.println("\033[33m[" + toolName + "] " +
                        result.substring(0, Math.min(200, result.length())) + "\033[0m");
                messages.add(DashScopeClient.toolResultMessage(toolCallId, result));
            }
        }
        System.out.println("[警告] 达到最大轮次限制");
    }

    // ==================== REPL ====================

    public static void main(String[] args) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));

        // 选择模式
        System.out.println("=== S07 Permission System 演示 ===");
        System.out.println("可用模式:");
        System.out.println("  default - 写操作需要用户确认");
        System.out.println("  plan    - 只允许读操作");
        System.out.println("  auto    - 读操作自动放行，写操作需确认");
        System.out.print("选择模式 (default): ");
        String modeInput = reader.readLine().trim().toLowerCase();
        if (!MODES.contains(modeInput)) modeInput = "default";

        PermissionManager perms = new PermissionManager(modeInput, reader);
        System.out.println("[当前模式: " + modeInput + "]\n");

        List<Map<String, Object>> history = new ArrayList<>();
        while (true) {
            System.out.print("\033[36m[S07] >>> \033[0m");
            String input = reader.readLine();
            if (input == null || input.isBlank() ||
                    "q".equalsIgnoreCase(input.trim()) || "exit".equalsIgnoreCase(input.trim())) {
                break;
            }

            history.add(DashScopeClient.userMessage(input));
            agentLoop(history, perms);
            System.out.println();
        }
    }
}
