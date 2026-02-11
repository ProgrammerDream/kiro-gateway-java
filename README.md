# Kiro Gateway Java

OpenAI & Anthropic 兼容的 Kiro API 代理网关 — Java 版

## 功能特性

- **OpenAI API 兼容** — `/v1/chat/completions`, `/v1/models`
- **Anthropic API 兼容** — `/v1/messages`
- **多账号池** — round-robin / random / least-used / 智能评分
- **自动 Token 刷新** — OIDC / Social / Builder ID / IAM SSO
- **流式响应** — SSE (Server-Sent Events)
- **双端点回退** — CodeWhisperer → AmazonQ
- **全链路追踪** — 请求/响应 JSON 可视化
- **Vue 3 管理面板** — 账号管理、对话记录、日志查看
- **日志持久化** — 应用日志文件 + SQLite 请求/追踪日志

## 快速开始

```bash
# 构建
mvn clean package -DskipTests

# 运行
java -jar target/kiro-gateway-java-1.0.0.jar
```

服务启动后访问 `http://localhost:8080/admin`

## 技术栈

- Java 17+
- Spring Boot 3.x (WebFlux)
- SQLite (WAL 模式)
- fastjson2
- Vue 3 + Vite + Element Plus

## License

AGPL-3.0
