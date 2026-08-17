# reader-mcp

基于 [reader-engine](https://github.com/changshengyu/reader-dev) 引擎的小说/漫画下载器 MCP 服务。

通过 [Model Context Protocol](https://modelcontextprotocol.io/) 将小说和漫画的搜索、详情、章节目录、正文和批量下载能力暴露为 MCP 工具，AI 客户端（Claude Desktop、Cursor、CatPaw 等）可直接调用。

## 功能

- **多书源聚合搜索** — 并行搜索所有小说源或漫画源，支持指定单源或全源聚合，支持分页
- **按作者搜索** — 聚合所有小说源或漫画源，按作者名查询其全部作品
- **书籍详情** — 书名、作者、简介、封面、字数、最新章节、来源等
- **章节目录** — 完整章节列表（序号、标题、URL）
- **单章正文** — 获取指定章节内容；小说返回纯文本，漫画返回图片 HTML（后端自动清洗图片 URL 元数据）
- **批量下载** — 并发下载多章正文，暂存为 TXT 文件并提供 HTTP 下载链接（24 小时有效）
- **多级缓存** — 搜索结果 10 分钟、书籍详情 1 小时、章节目录 2 小时、单章正文 30 分钟，减少重复 HTTP 请求
- **Web 界面** — 内置响应式搜索页面，支持小说/漫画搜索、在线阅读、漫画图片展示、弹窗可调整大小
- **内置 8 个书源** — 4 个小说源 + 4 个漫画源，开箱即用

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

服务注册了 9 个 MCP 工具，客户端通过 `tools/list` 自动发现：

| 工具 | 说明 | 关键参数 |
|------|------|----------|
| `reader_list_sources` | 列出书源（可按类型筛选） | `type`（可选：`novel`=仅小说源，`comic`=仅漫画源，空=全部） |
| `reader_search_novel` | 按关键词搜索小说 | `keyword`（必填）、`source`（可选，书源简称）、`page`（可选，默认 1） |
| `reader_search_comic` | 按关键词搜索漫画 | `keyword`（必填）、`source`（可选，书源简称）、`page`（可选，默认 1） |
| `reader_search_novel_by_author` | 按作者搜索小说 | `author`（必填），聚合所有小说源 |
| `reader_search_comic_by_author` | 按作者搜索漫画 | `author`（必填），聚合所有漫画源 |
| `reader_book_info` | 获取书籍详情（书名、作者、简介、封面、字数、最新章节等） | `source`（书源简称，从搜索结果获取）、`bookUrl` |
| `reader_chapters` | 获取章节目录 | `source`（书源简称）、`bookUrl` |
| `reader_content` | 获取单章正文（小说返回纯文本，漫画返回图片 HTML） | `source`（书源简称）、`bookUrl`、`chapterIndex`（从 1 开始） |
| `reader_download` | 批量下载章节并返回下载链接 | `source`（书源简称）、`bookUrl`、`start`、`end` |

### 典型调用流程

```
reader_list_sources → reader_search_novel / reader_search_comic → reader_book_info → reader_chapters → reader_content / reader_download
```

## 内置书源

### 小说源（4 个）

| 源名称 | 简称(source) | URL | 类型 |
|--------|-------------|-----|------|
| 八零小说 | `80` | `http://www.80ge.info` | novel |
| 独步小说 | `dubu` | `https://www.dbxsd.com` | novel |
| 猫眼看书 | `maoyan` | `http://api.lemiyigou.com` | novel |
| 七猫小说 | `qimao` | `https://api-bc.wtzw.com` | novel |

### 漫画源（4 个）

| 源名称 | 简称(source) | URL | 类型 |
|--------|-------------|-----|------|
| G站漫画 | `godamanga` | `https://godamanga.com` | comic |
| 漫画台 | `manhuatai` | `https://m.manhuatai.com` | comic |
| 如漫画 | `rumanhua` | `https://www.rumanhua.com` | comic |
| 再漫画 | `zaimanhua` | `https://www.zaimanhua.com` | comic |

> 后续操作传入 `source` 简称即可（如 `80`、`dubu`、`godamanga`），无需传完整 URL。
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
  download:
    temp-dir: /tmp/reader-downloads   # 临时文件存放目录
    expire-hours: 24                   # 文件过期时间（小时）
    base-url: ""                       # 服务外部访问 URL（部署时必须配置，如 https://reader.example.com）
  cache:
    search-expire-minutes: 10         # 搜索结果缓存过期时间（分钟）
    detail-expire-minutes: 60         # 书籍详情缓存过期时间（分钟）
    chapter-list-expire-minutes: 120  # 章节目录缓存过期时间（分钟）
    content-expire-minutes: 30        # 单章正文缓存过期时间（分钟）
    max-size: 500                     # 缓存最大条目数（每种缓存独立计算）
```

### 下载文件

`reader_download` 工具下载完成后，将内容暂存为 TXT 文件，返回包含 `downloadUrl` 的结果。

- 下载链接格式：`GET /api/reader/download/{fileId}`
- 文件默认 24 小时后自动过期清理
- 部署到服务器时，通过 `reader.download.base-url` 配置服务外部访问 URL，确保下载链接可远程访问

## 架构

```
cn.kong.reader/
├── ReaderApplication.java           # 启动类（@EnableScheduling）
├── config/
│   ├── CacheProperties.java          # 缓存配置属性（TTL、最大容量）
│   ├── EngineConfig.java             # 引擎配置（ReaderService Bean 注册）
│   ├── McpToolConfig.java            # MCP 工具注册配置（ToolCallbackProvider）
│   ├── TempFileProperties.java       # 临时文件配置属性
│   └── WebConfig.java                # Web 配置（根路径转发到 index.html）
├── controller/
│   ├── GlobalExceptionHandler.java   # 全局异常处理（统一 HTTP 状态码）
│   └── ReaderController.java         # REST API（搜索/详情/目录/正文/下载）
├── mcp/
│   └── ReaderMcpTools.java           # 9 个 MCP 工具定义
├── service/
│   ├── ReaderFacade.java             # 增强门面层（缓存/参数校验/漫画清洗/下载）
│   └── TempFileService.java          # 临时文件管理（创建/查询/清理）
└── task/
    └── FileCleanupTask.java          # 定时清理过期文件

src/main/resources/
└── static/
    └── index.html                    # Web 搜索页面（响应式/弹窗可调整大小）
```

### 技术栈

| 组件 | 版本 | 用途 |
|------|------|------|
| Spring Boot | 3.5.15 | 应用框架 |
| Spring AI | 1.1.8 | MCP Server 支持 |
| reader-engine | 1.0.0 | 书源/漫画源解析引擎（Java + Kotlin） |
| Java | 17+ | 运行时 |

### 核心设计

- **引擎委托**：`ReaderFacade` 门面层直接调用 `ReaderService` 高层 API，只需传入书源简称和书籍 URL，引擎内部通过 `ReaderEngineBridge.kt` 的 `runBlocking` 将 Kotlin suspend 函数桥接为同步调用
- **门面增强层（`ReaderFacade`）**：在引擎之上提供多级缓存（`ConcurrentHashMap` + TTL + 后台清理）、参数校验（关键词长度、页码合法性）、搜索结果数量限制、下载章节数限制、漫画图片 URL 清洗等增强能力
- **引擎层 Book 缓存**：引擎内部以 `source + bookUrl` 为 key 缓存 Book 对象，避免获取目录和正文时重复请求详情页
- **通用 DTO**：书源信息（`SourceInfo`）、搜索结果（`SearchResult`）、书籍详情（`BookDetail`）、章节信息（`ChapterInfo`）、章节正文（`ChapterContent`）均为通用 DTO，不暴露内部实体对象
- **ReaderService 单例**：内置 8 个书源（4 小说 + 4 漫画），开箱即用
- **临时文件管理**：下载内容写入临时文件，定时清理过期文件（默认 24 小时），通过 `/api/reader/download/{fileId}` 端点提供远程下载
- **漫画图片清洗**：引擎返回的漫画图片 URL 可能附带 `,{"headers":{"Referer":...}}` 格式的元数据，`ReaderFacade` 自动剥离，确保前端可直接加载图片
