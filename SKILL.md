---
name: reader-downloader
description: 搜索和下载网络小说/漫画正文。当用户想搜索小说或漫画、查找书籍、阅读章节、下载小说正文或批量下载章节时使用。涉及关键词搜索书籍、获取书籍详情、查看章节目录、阅读单章内容、批量下载多章正文等需求时触发。
---

# 小说/漫画下载器

通过 MCP 服务（reader-mcp）搜索和下载网络小说和漫画。服务默认运行在 `http://localhost:8081/mcp`。

## 工作流程

根据用户需求选择合适的流程：

### 搜索书籍
1. 调用 `reader_list_sources` 获取可用书源列表（包含小说源和漫画源）
2. 调用 `reader_search`（小说）或 `reader_search_comic`（漫画）搜索关键词（`sourceUrl` 传 `all` 聚合所有书源，或指定单个书源 URL）
3. 展示搜索结果，让用户选择目标书籍

### 阅读单章
1. 搜索并确定书籍后，调用 `reader_chapters` 获取章节目录
2. 调用 `reader_content` 获取指定章节正文（`chapterIndex` 从 1 开始）

### 批量下载
1. 搜索并确定书籍后，调用 `reader_chapters` 获取章节目录
2. 调用 `reader_download` 批量下载（传入 `start` 和 `end` 章节序号）
3. 下载结果返回完整文本内容，包含书名、作者、来源、章节范围及下载统计信息（成功/失败数、总字数）

## 最佳实践

- **先列书源再搜索**：如果用户没有指定书源，先调用 `reader_list_sources` 让用户选择
- **分批下载**：单次下载不超过 50 章，避免请求超时；大量章节分多次调用
- **复用 bookUrl**：从搜索结果或章节目录中获取的 `bookUrl` 直接传给后续工具，无需修改
- **聚合搜索**：不确定用哪个书源时，`sourceUrl` 传 `all` 一次性搜索所有书源
- **先看目录再下载**：下载前先调用 `reader_chapters` 确认章节范围，避免下载到无关内容
- **小说 vs 漫画**：小说用 `reader_search`，漫画用 `reader_search_comic`

## 常见场景

| 用户需求 | 调用顺序 |
|----------|----------|
| "搜索斗破苍穹" | `reader_list_sources` → `reader_search` |
| "搜索漫画哑舍" | `reader_list_sources` → `reader_search_comic` |
| "搜索并阅读第1章" | `reader_search` → `reader_chapters` → `reader_content` |
| "下载前100章" | `reader_search` → `reader_chapters` → `reader_download`（分 2 批：1-50, 51-100）|
| "看看有哪些书源" | `reader_list_sources` |
| "按作者搜索" | `reader_list_sources` → `reader_search_by_author` |
