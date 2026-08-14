# 部署指南

## Dockerfile vs Docker Compose

两者**不是二选一**，而是配合使用：

| 文件 | 职责 |
|------|------|
| `Dockerfile` | 定义如何**构建镜像**（编译 Java 项目、打包 jar） |
| `docker-compose.yml` | 定义如何**运行容器**（端口映射、重启策略、健康检查） |

**推荐使用 Docker Compose**，因为它封装了所有运行参数，一条命令完成部署。

---

## 前置条件

服务器上需安装：

- [Docker](https://docs.docker.com/engine/install/) 24+
- [Docker Compose](https://docs.docker.com/compose/install/) v2+（Docker 24+ 已内置）

验证安装：

```bash
docker --version
docker compose version
```

---

## 目录结构

部署前确保项目目录包含以下文件：

```
reader-mcp/
├── Dockerfile
├── docker-compose.yml
├── .dockerignore
├── pom.xml
├── libs/
│   └── reader-engine-1.0.0.jar    # 本地依赖（构建时安装到 Maven 仓库）
├── src/                             # 源代码
└── README.md
```

> **重要**：`libs/reader-engine-1.0.0.jar` 必须存在，否则 Docker 构建会失败。

---

## 部署步骤

### 1. 上传项目到服务器

```bash
# 方式一：Git 克隆
git clone <your-repo-url> /opt/reader-mcp
cd /opt/reader-mcp

# 方式二：SCP 上传
scp -r reader-mcp/ user@server:/opt/reader-mcp
```

### 2. 构建并启动

```bash
cd /opt/reader-mcp

# 构建镜像并启动容器（首次构建约 3-5 分钟）
docker compose up -d --build
```

### 3. 查看启动日志

```bash
docker compose logs -f
```

看到以下日志说明启动成功：

```
Started ReaderApplication in x.xxx seconds
Tomcat started on port 8081 (http)
```

按 `Ctrl+C` 退出日志查看。

---

## 验证部署

### 方式一：MCP 协议验证（推荐）

```bash
# 1. 初始化握手
curl -s -X POST http://localhost:8081/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "initialize",
    "params": {
      "protocolVersion": "2025-06-18",
      "capabilities": {},
      "clientInfo": {"name": "verify", "version": "1.0.0"}
    }
  }'
```

### 方式二：容器健康检查

```bash
docker inspect --format='{{.State.Health.Status}}' reader-mcp
```

输出 `healthy` 表示服务正常。

### 方式三：工具列表验证

完成 initialize 握手后，获取 Session ID 并查询工具：

```bash
# 获取 Session ID
SESSION_ID=$(curl -s -D - -o /dev/null -X POST http://localhost:8081/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"verify","version":"1.0.0"}}}' \
  | grep -i "mcp-session-id" | awk '{print $2}' | tr -d '\r')

# 发送 initialized 通知
curl -s -X POST http://localhost:8081/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -H "Mcp-Session-Id: $SESSION_ID" \
  -d '{"jsonrpc":"2.0","method":"notifications/initialized"}'

# 查询工具列表
curl -s -X POST http://localhost:8081/mcp \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -H "Mcp-Session-Id: $SESSION_ID" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}'
```

预期返回 8 个工具：`reader_list_sources`、`reader_search`、`reader_search_comic`、`reader_search_by_author`、`reader_book_info`、`reader_chapters`、`reader_content`、`reader_download`。

---

## 日常运维

### 查看状态

```bash
docker compose ps          # 容器状态
docker compose logs -f     # 实时日志
docker compose logs --tail 100  # 最近 100 行日志
```

### 重启 / 停止 / 启动

```bash
docker compose restart     # 重启
docker compose stop        # 停止
docker compose start       # 启动
docker compose down        # 停止并删除容器
```

### 更新代码后重新部署

```bash
git pull                   # 拉取最新代码
docker compose up -d --build   # 重新构建并启动
```

---

## 客户端接入

部署完成后，AI 客户端配置 MCP 服务地址为：

```
http://<服务器IP>:8081/mcp
```

### Claude Desktop

```json
{
  "mcpServers": {
    "reader-mcp": {
      "url": "http://<服务器IP>:8081/mcp"
    }
  }
}
```

### Cursor

在 Settings → MCP 中添加 Streamable-HTTP 服务，URL 填 `http://<服务器IP>:8081/mcp`。

---

## 常见问题

### 构建失败：找不到 reader-engine 依赖

**原因**：`libs/reader-engine-1.0.0.jar` 未上传到服务器。

**解决**：确认 `libs/` 目录存在且包含 jar 文件：

```bash
ls -la libs/reader-engine-1.0.0.jar
```

### 容器启动后立即退出

**排查**：

```bash
docker compose logs --tail 50
```

常见原因：
- 端口 8081 被占用：修改 `docker-compose.yml` 中的端口映射
- 内存不足：在 `docker-compose.yml` 添加 `mem_limit: 512m`（或更高）

### 远程客户端无法连接

**排查**：
1. 确认服务器防火墙放行 8081 端口
2. 云服务器还需在安全组规则中放行 8081 端口
3. 如果通过反向代理（Nginx）转发，需配置 SSE/Streamable-HTTP 支持：

```nginx
location /mcp {
    proxy_pass http://127.0.0.1:8081/mcp;
    proxy_http_version 1.1;
    proxy_set_header Connection "";
    proxy_buffering off;
    proxy_read_timeout 120s;
}
```
