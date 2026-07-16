# TailorAgent

## 项目简介

TailorAgent 是一款仅支持 Windows 的本地 AI Agent 桌面应用。它使用 Spring Boot 作为本地后端、Vue 3 构建界面，并通过 JCEF 将完整 Chromium 嵌入桌面窗口。

应用支持 OpenAI 兼容的对话与 Embedding 模型，可以让 AI 在指定工作区内读取和修改文件、搜索代码、执行命令，并通过 MCP、Skill 和长期记忆扩展能力。同时，TailorAgent 内置了本地知识库，支持 Markdown 阅读编辑、AI 辅助修改以及 BM25 + KNN 混合检索。

应用数据保存在本机，后端仅监听 `127.0.0.1`。除用户自行配置的模型与 MCP 服务外，应用本身不依赖外部数据库、检索服务或浏览器环境。

## 快速开始

TailorAgent 提供两种安装和使用方式。

### 方式一：直接安装已经打包好的 MSI

项目维护者会提供已经编译打包完成的 Windows MSI 安装包。普通用户推荐使用这种方式，不需要安装 JDK、Node.js、Maven 或其他开发环境。

1. 下载提供的 `.msi` 安装包。
2. 双击安装包，根据安装向导完成安装。
3. 从桌面快捷方式或开始菜单启动 TailorAgent。
4. 首次启动后进入“设置”，添加一个 OpenAI 兼容的对话模型。
5. 填写模型的 Base URL、模型名称和 API Key，然后点击连接测试。
6. 选择一个本地工作目录，或使用应用提供的“新建工作区”功能。
7. 如果需要知识库向量检索，再单独配置一个 Embedding 模型。

首次启动时，应用需要将内置的 JCEF Chromium native 文件解压到本地数据目录，因此可能比后续启动稍慢。

> 模型配置和 API Key 只会保存在本机的 `%LOCALAPPDATA%\TailorAgent\app-config.json`。该文件包含敏感配置，请勿分享或上传到公开仓库。

### 方式二：下载源码并自行打包

自行构建需要以下环境：

- Windows 10 或 Windows 11，x64 架构
- JDK 21，并正确设置 `JAVA_HOME`
- 生成 MSI 时需要 WiX Toolset v3
- 项目已经提供 Maven Wrapper，不需要单独安装 Maven
- 完整构建会自动准备 Node.js 并构建前端，不要求系统预装 Node.js

下载或克隆项目源码后，在项目根目录打开 PowerShell 或 CMD。

首先构建包含前端、后端和 JCEF native 依赖的 fat jar：

```powershell
mvn clean package
```

构建完成后会生成：

```text
target\TailorAgent-0.0.1-SNAPSHOT.jar
```

如果希望在构建过程中同时运行测试，请使用：

```powershell
mvn clean package -DskipTests=false
```

然后生成 MSI 安装包：

```bat
.\package.bat
```

生成结果位于：

```text
dist\
```

如果只需要免安装的便携应用目录，可以执行：

```bat
.\package.bat app-image
```

`app-image` 不需要 WiX Toolset。生成 MSI 时，`package.bat` 会使用 `%JAVA_HOME%\bin\jpackage.exe`，请确保 `JAVA_HOME` 指向 JDK 21。

## 项目功能介绍

### AI 对话与任务执行

- 支持多个 OpenAI 兼容对话模型，并可在界面中切换模型。
- 支持流式输出、推理内容展示、工具调用过程展示和任务取消。
- 自动保存会话、轮次和事件，应用重启后仍可查看历史任务。
- 支持上下文用量统计、工作集管理和长会话摘要压缩。
- 支持多个会话并发运行。

### 本地工作区工具

- 读取、创建、覆盖和精确编辑文本文件。
- 使用 Glob 查找文件，使用内置 ripgrep 搜索文本和代码。
- 执行前台或后台 Shell 命令，并可读取输出或终止后台命令。
- 相对路径统一基于用户选择的工作区。
- 文件写入操作限制在工作区范围内，并带有防陈旧编辑检查。

### MCP、Skill 与长期记忆

- 支持配置和连接外部 MCP 服务，将其工具加入 Agent 的工具调用链。
- 支持导入 Skill，让 Agent 按需加载专门的工作流程和说明。
- 支持按工作区隔离的长期记忆，保存跨会话仍然有价值的项目知识。
- 主对话 Agent 与知识库编辑 Agent 使用彼此独立的 MCP 和 Skill 配置，避免权限和用途混淆。

### 本地知识库

- 以本地真实文件作为知识正文来源，而不是把正文复制到数据库。
- 支持目录创建、文件导入、重命名、移动和删除。
- 支持 Markdown 文档阅读与所见即所得编辑。
- 支持知识库专用 AI Agent，通过受限文件工具辅助修改知识内容。
- 支持对 Markdown 文件异步建立 Lucene 索引，并显示索引进度。
- 使用 BM25 全文检索、KNN 向量检索和 RRF 融合进行混合召回。
- 主对话 Agent 可以通过 `kb_search` 检索已经完成索引的知识内容。

> 当前只会为 `.md` 和 `.markdown` 文件建立检索索引。PDF、Word 等其他文件可以导入和保存，但尚未进入正文抽取与 RAG 检索链路。

### 模型与运行环境设置

- 支持对话模型和独立 Embedding 模型配置与连接测试。
- 支持自定义 Base URL、模型名称、API Key、上下文窗口和 Embedding 批次等参数。
- 支持检测本机 Node.js 和 uv 环境，并可通过 winget 启动安装。
- 支持选择已有工作目录，或在应用数据目录中自动创建新工作区。

## 应用目录介绍

通过 MSI 或 jpackage app-image 启动时，TailorAgent 的可写数据统一保存在：

```text
%LOCALAPPDATA%\TailorAgent\
```

通常对应：

```text
C:\Users\<用户名>\AppData\Local\TailorAgent\
```

可以在文件资源管理器地址栏中直接输入 `%LOCALAPPDATA%\TailorAgent` 打开该目录。

目录内容如下：

| 路径 | 用途 |
|---|---|
| `app-config.json` | 保存对话模型、Embedding 模型、MCP、工作区和上下文设置。可能包含 API Key，不应对外分享。 |
| `data\tailor_agent.db` | SQLite 数据库，保存聊天会话、轮次、事件和知识库目录索引状态。 |
| `knowledge\MD\` | 保存 Markdown 知识文档，是知识正文的真实来源。 |
| `knowledge\files\` | 保存导入的 PDF、Word、表格等其他知识文件。 |
| `index\knowledge\` | Lucene 知识库索引，包含 BM25 和向量检索数据。属于可重新生成的派生数据。 |
| `workspace\` | 保存通过应用“新建工作区”创建的工作目录；用户也可以选择该目录之外的现有工作区。 |
| `skills\` | 保存主对话 Agent 导入的 Skill。 |
| `kb-skills\` | 保存知识库编辑 Agent 导入的 Skill，与主对话 Skill 隔离。 |
| `memory\<工作区名>\` | 保存对应工作区的 Agent 长期记忆及其索引。 |
| `media\` | 保存文档编辑器上传的图片等媒体资源。 |
| `jcef-bundle\` | 保存从应用包中解压的 JCEF/Chromium native 文件。该目录可重新生成。 |
| `bin\` | 保存应用运行时释放的内置命令行工具，例如 `rg.exe`。该目录可重新生成。 |
| `tools\visualize-data-structures\` | 保存内置数据结构可视化工具运行所需的脚本资源。该目录可重新生成。 |

备份应用数据时，建议至少保留以下内容：

- `app-config.json`
- `data\tailor_agent.db`
- `knowledge\`
- `workspace\` 中需要保留的工作区
- `skills\`、`kb-skills\` 和 `memory\`

`jcef-bundle\`、`bin\`、`tools\` 和 `index\knowledge\` 都属于可以重新解压或重建的内容，通常不需要备份。
