# Loom HTTP Core

HTTP 调用能力的纯 Java 引擎。无 Spring 依赖，提供给 `loom-http-mcp`（独立 jar）与主库
`spring-ai-loom-agent`（内部工具模式）共用。

## 概述

- `HttpStorage` —— 存储根目录派生（profiles / systems / history / responses / log）
- `HttpConfig` —— 全局配置（白名单、超时、批并发、敏感 header）
- `HttpEngine` —— 唯一门面，调用 / 注册 / 历史查询的统一入口
- `InvokeService` 16 步流水线（白名单→占位→请求→大响应落盘→断言→JSONPath→脱敏→历史）
- `ProfileService` / `SystemService` / `EndpointService` / `HistoryService` —— 各领域服务
- `DomainWhitelist` / `SensitiveFieldMasker` —— 安全工具

## 架构

```
+-----------------+        +-----------------+
| loom-http-mcp   |        | spring-ai-      |
| (jar 壳)        |        | loom-agent      |
| 19 @McpTool     |        | (IHttpTool)     |
+-------+---------+        +--------+--------+
        |                           |
        v                           v
+---------------------------------------+
|         HttpEngine (门面)             |
+---------------------------------------+
        |
        v
+---------------------------------------+
|  HttpStorage + HttpConfig + 各服务    |
+---------------------------------------+
```

## 构建

```bash
mvn clean install -Dgpg.skip=true -pl loom-http-core
```

## 测试

411 个 JUnit 测试，包含 ProfileValidatorTest / InvokeScaleTest / InvokeFailureTest /
HeaderResolverTest 等。fail-closed 行为由 `HttpConfig.failClosed` 控制（默认 false）。

## 许可

Apache License 2.0
