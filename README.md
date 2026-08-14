# reader-mcp

基于 [reader-engine](https://github.com/changshengyu/reader-dev) 引擎的小说/漫画下载器 MCP 服务。

通过 [Model Context Protocol](https://modelcontextprotocol.io/) 将小说和漫画的搜索、详情、章节目录、正文和批量下载能力暴露为 MCP 工具，AI 客户端（Claude Desktop、Cursor、CatPaw 等）可直接调用。

## 功能

- **多书源聚合搜索** — 并行搜索所有小说源或漫画源，支持指定单源或全源聚合
- **书籍详情** — 作者、简介、封面、字数、分类等
- **章节目录** — 完整章节列表（序号、标题、URL）
- **单章正文** — 获取指定章节内容
- **批量下载** — 并发下载多章正文，支持分批调用
- **内置 8 个书源** — 4 个小说源 + 4 个漫画源，开箱即用
- **运行时导入** — 支持通过 `importSources` 动态导入外部书源

## 快速开始

### 前置条件

- JDK 17+
- Maven 3.6+
- `reader-engine` 已安装到本地 Maven 仓库

### 构建与运行

```bash
mvn clean package
java -jar target/reader-1.0.0.jar
```

或开发模式：

```bash
mvn spring-boot:run
```

服务启动后监听 `http://localhost:8081/mcp`。

## 接入 AI 客户端

### Claude Desktop

在 `claude_desktop_config.json` 中添加：

```json
{
  "mcpServers": {
    "reader-mcp": {
      "url": "http://localhost:8081/mcp"
    }
  }
}
```

### Cursor

在 Cursor 设置 → MCP 中添加 Streamable-HTTP 服务：

```
URL: http://localhost:8081/mcp
```

### CatPaw

在 MCP 服务配置中添加 `http://localhost:8081/mcp`，同时可配合 [reader-downloader Skill](./SKILL.md) 获得更好的工具编排指导。

## MCP 工具

服务注册了 8 个 MCP 工具，客户端通过 `tools/list` 自动发现：

| 工具 | 说明 | 关键参数 |
|------|------|----------|
| `reader_list_sources` | 列出所有书源（小说+漫画） | 无 |
| `reader_search` | 按关键词搜索小说 | `keyword`（必填）、`sourceUrl`（可选，传 `all` 聚合搜索） |
| `reader_search_comic` | 按关键词搜索漫画 | `keyword`（必填）、`sourceUrl`（可选，传 `all` 聚合搜索） |
| `reader_search_by_author` | 按作者搜索小说 | `author`（必填）、`sourceUrl`（可选） |
| `reader_book_info` | 获取书籍详情 | `sourceUrl`、`bookUrl` |
| `reader_chapters` | 获取章节目录 | `sourceUrl`、`bookUrl` |
| `reader_content` | 获取单章正文 | `sourceUrl`、`bookUrl`、`chapterIndex`（从 1 开始） |
| `reader_download` | 批量下载章节 | `sourceUrl`、`bookUrl`、`start`、`end` |

### 典型调用流程

```
reader_list_sources → reader_search / reader_search_comic → reader_book_info → reader_chapters → reader_content / reader_download
```

## 内置书源

### 小说源（4 个）

| 源名称 | URL | 类型 |
|--------|-----|------|
| 八零小说 | `http://www.80ge.info` | novel |
| 独步小说 | `https://www.dbxsd.com` | novel |
| 猫眼看书 | `http://api.lemiyigou.com` | novel |
| 七猫小说 | `https://api-bc.wtzw.com` | novel |

### 漫画源（4 个）

| 源名称 | URL | 类型 |
|--------|-----|------|
| G站漫画 | `https://godamanga.com` | comic |
| 漫画台 | `https://m.manhuatai.com` | comic |
| 如漫画 | `https://www.rumanhua.com` | comic |
| 再漫画 | `https://www.zaimanhua.com` | comic |

> 漫画源在加载时自动执行 `initSource()` 初始化 variable（URL、cookie 等），无需手动调用。

## 配置

### MCP 服务配置 (`application.yml`)

```yaml
spring:
  ai:
    mcp:
      server:
        name: reader-mcp
        version: 1.0.0
        protocol: STREAMABLE      # 传输协议：STREAMABLE（推荐）/ SSE / STATELESS
        type: SYNC                # 运行模式：SYNC / ASYNC
        instructions: "..."       # 向 AI 客户端描述服务能力
        request-timeout: 120s     # 请求超时（批量下载需要较长超时）
        streamable-http:
          mcpEndpoint: /mcp       # MCP 端点路径
```

### 业务配置

```yaml
reader:
  max-search-results: 50              # 单次搜索结果上限
  max-download-chapters: 200          # 单次下载章节数上限
```

## 架构

```
cn.kong.reader/
├── ReaderApplication.java           # 启动类
├── mcp/
│   ├── McpToolConfig.java            # MCP 工具注册配置
│   └── ReaderMcpTools.java           # 8 个 MCP 工具定义
└── service/
    └── ReaderApi.java                # 业务逻辑（搜索/详情/目录/正文/下载）
```

### 技术栈

| 组件 | 版本 | 用途 |
|------|------|------|
| Spring Boot | 3.5.15 | 应用框架 |
| Spring AI | 1.1.8 | MCP Server 支持 |
| reader-engine | 1.0.0 | 书源/漫画源解析引擎（Java + Kotlin） |
| Java | 17+ | 运行时 |

### 核心设计

- **引擎委托**：业务逻辑层直接调用 `BookSourceManager`（高层 API）和 `ReaderEngine`（底层 API），引擎内部通过 `ReaderEngineBridge.kt` 的 `runBlocking` 将 Kotlin suspend 函数桥接为同步调用
- **LRU 缓存**：搜索结果（500 条）和章节列表（100 条）使用 `LinkedHashMap` access-order 实现 LRU 淘汰
- **per-key 锁**：章节缓存使用 double-check + per-key lock 防止缓存击穿
- **BookSourceManager 单例**：内置 8 个书源（4 小说 + 4 漫画），支持运行时导入外部书源
