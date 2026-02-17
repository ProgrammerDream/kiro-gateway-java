# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概述

Kiro Gateway Java 是一个 API 代理网关，将 OpenAI 和 Anthropic 格式的请求翻译为 Kiro 内部协议（AWS CodeWhisperer / Amazon Q 端点），支持多账号池、自动 Token 刷新、流式响应和全链路追踪。

## 构建与运行

```bash
# 构建（无测试）
mvn clean package -DskipTests

# 运行
java -jar target/kiro-gateway-java-1.0.0.jar

# 前端开发（Vue 3 管理面板）
cd frontend && npm install && npm run dev

# 前端构建（输出到 src/main/resources/static）
cd frontend && npm run build
```

服务启动后访问 `http://localhost:8080/admin`

当前项目无测试代码，测试依赖（spring-boot-starter-test, reactor-test）已就绪。

## 技术栈

- Java 17 + Spring Boot 3.3.0 (WebFlux, 响应式)
- SQLite (WAL 模式, HikariCP 单连接)
- fastjson2（全局 JSON 处理，非 Jackson）
- Lombok
- 前端: Vue 3 + Vite + Element Plus

## 架构与请求流

```
客户端 (OpenAI/Claude 格式)
  → ApiKeyFilter (Bearer token 验证, order=10)
  → TraceFilter (创建 TraceContext, 最高优先级)
  → OpenAiController (/v1/chat/completions) 或 ClaudeController (/v1/messages)
    → ModelResolver (外部模型名 → Kiro 内部模型 ID, 支持 contains/exact/regex 匹配)
    → AccountPool.getNext() (4种策略: round-robin/random/least-used/smart-score)
    → AuthService.getAccessToken() (OIDC/Social token 刷新, 带缓存+锁)
    → OpenAiTranslator/ClaudeTranslator (双向协议转换)
    → KiroApiClient.callStream() (双端点回退 + RetryHandler 指数退避)
      → EventStreamParser (AWS 二进制事件流: 12字节前导 + headers + gzip 载荷 + CRC)
    → Translator 将 Kiro 响应转回客户端格式
    → TraceStore 持久化到 SQLite
  → 客户端收到 OpenAI/Claude 格式响应
```

## 关键包职责

| 包 | 职责 |
|---|------|
| `auth` | Token 生命周期管理，`TokenRefresher` 接口 + OIDC/Social 实现 |
| `config` | `AppProperties`（`kiro.*` 配置绑定）、DatabaseConfig（schema 初始化+迁移）、HttpClientConfig |
| `controller` | API 端点 + WebFilter（ApiKeyFilter, AdminController 管理面板 API） |
| `dao` | SQLite DAO 层（JdbcTemplate），Account/ApiKey/Model/RequestLog/Trace |
| `model` | `ModelResolver` 外部模型名到内部 ID 的映射解析 |
| `pool` | `AccountPool` 多账号选择，`SelectionStrategy` 策略接口 + 4种实现 |
| `proxy` | `KiroApiClient` 核心代理调用，`EventStreamParser` AWS 二进制流解析，`RetryHandler` 重试 |
| `trace` | `TraceContext` 请求级上下文，`TraceFilter` 创建上下文，`TraceStore` 环形缓冲+DB持久化 |
| `translator` | `RequestTranslator` 接口，OpenAi/Claude 双向协议转换，`ThinkingParser` thinking 模式处理 |

## 重要架构细节

- **响应式栈**: 全程 WebFlux `Mono`/`Flux`，控制器返回 `Mono<Void>` 直接写 exchange response 实现流式输出
- **EventStreamParser**: 解析 AWS 私有二进制帧格式，需处理 gzip 解压、部分帧缓冲、CRC 校验
- **Token 刷新**: `AuthService` 使用 `ConcurrentHashMap` 缓存 + `ReentrantLock` per-account 双重检查锁
- **TraceStore**: `ArrayBlockingQueue(1000)` 环形缓冲用于快速查询 + 同步 SQLite 写入
- **DatabaseConfig.migrate()**: 增量 schema 迁移，启动时检查并添加新列/索引
- **BackgroundScheduler**: 每日 03:00 清理过期日志，每小时刷新模型缓存

## 数据库

SQLite 文件位于 `data/kiro.db`，7 张表: accounts, settings, api_keys, models, model_mappings, request_logs, traces, metrics。Schema 定义在 `src/main/resources/schema.sql`。

## 配置

主配置文件 `src/main/resources/application.yml`，所有自定义配置在 `kiro.*` 命名空间下，绑定到 `AppProperties`。关键配置项：
- `kiro.api-key`: 客户端调用所需的 API Key
- `kiro.pool-strategy`: 账号池选择策略
- `kiro.endpoints`: 双端点 URL 列表（CodeWhisperer, AmazonQ）
- `kiro.proxy`: VPN/代理设置
- `kiro.retry`: 重试配置

## 请使用中文回复
