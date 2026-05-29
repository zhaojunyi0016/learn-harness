# Java Agents - 构建与运行

## 依赖

项目依赖已在 pom.xml 中配置：
- Java 19+
- Gson 2.10.1（JSON 序列化）
- Spring Boot 3.2.0（主项目，教程文件不依赖）

## 运行教程文件

```bash
# 1. 设置环境变量（DashScope API Key，在 application.yml 中可以找到）
export DASHSCOPE_API_KEY="sk-xxx"

# 2. 可选：指定模型（默认 qwen-plus）
export DASHSCOPE_MODEL="qwen-plus"

# 3. 编译
mvn compile

# 4. 运行任意章节（示例：S01）
mvn exec:java -Dexec.mainClass="com.learn.harness.agent.S01AgentLoop"
```

## 文件结构

```
agent/
├── DashScopeClient.java   # 共享：DashScope API 调用客户端
├── CommonTools.java       # 共享：bash/read/write/edit 公共工具
├── S01AgentLoop.java      # 第01章：Agent 循环（最小骨架）
├── S02ToolUse.java        # 第02章：工具分发
├── S03TodoWrite.java      # 第03章：计划管理
├── S04Subagent.java       # 第04章：子 Agent
├── S05SkillLoading.java   # 第05章：技能加载
├── S06ContextCompact.java # 第06章：上下文压缩
├── S07PermissionSystem.java # 第07章：权限系统
├── S08HookSystem.java     # 第08章：生命周期钩子
├── S09MemorySystem.java   # 第09章：记忆系统
├── S10SystemPrompt.java   # 第10章：系统提示词
├── S11ErrorRecovery.java  # 第11章：错误恢复
├── S12TaskSystem.java     # 第12章：任务系统
├── S13BackgroundTasks.java # 第13章：后台任务
├── S14CronScheduler.java  # 第14章：定时调度
├── S15AgentTeams.java     # 第15章：多 Agent 团队
├── S16TeamProtocols.java  # 第16章：团队协议
├── S17AutonomousAgents.java # 第17章：自治 Agent
├── S18WorktreeTaskIsolation.java # 第18章：进程隔离
├── S19McpPlugin.java      # 第19章：MCP 插件
└── SFull.java             # 合并版：所有机制整合
```

## 章节学习顺序

建议按 S01 → S19 → SFull 顺序阅读，每一章在前一章基础上增加一个新机制。
