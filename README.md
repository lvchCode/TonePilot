# TonePilot

## 当前架构要点

- 用户端由 Lightroom Classic 插件和 TonePilot Local Runtime 组成。插件负责读取当前照片和执行 `photo:applyDevelopSettings`，Local Runtime 负责 ReAct Agent 对话、模型调用、知识检索、上下文和工具调用。
- Local Runtime 不再支持本地规则修图兜底。用户需要在本机运行时配置 OpenAI 或阿里 Qwen2 的 Base URL、模型名和 API Key；模型调用失败会把错误展示给用户，不会偷偷改用规则参数。
- 管理端 `tonepilot-admin` 是云端控制面，负责风格、素材导入、知识审核、Hybrid RAG、运行时可观测和评估。用户日常修图不需要管理端直接控制 Lightroom。
- 知识库检索采用 Hybrid RAG 设计：关键词检索 + 向量检索 + 元数据过滤 + rerank 扩展点。当前开发态默认使用内存向量索引，配置中已经预留 Milvus、embedding provider 和 rerank provider。
- ReAct 用户链路是：用户输入 -> 运行时读取 Lightroom 当前照片 -> 可选检索管理端知识库 -> 大模型生成主 Agent 判断、行动轨迹和参数 -> 运行时按 decision 决定是否调用 Lightroom 工具。


TonePilot 是一个非生成式 AI 摄影调色 Agent。系统不直接生成图片，而是模拟摄影师的调色判断，输出可解释、可校验、可落地到 Lightroom Classic 的调色参数。

当前工程保留两个产品端：

- 管理端：Web 管理台，用于维护调色风格、样片、知识库、审核状态、可观测日志和自动评测。
- 插件端：Lightroom Classic 用户端，摄影师在 Lightroom 中选中照片，通过 Agent 对话完成修图，并由本地运行时把参数应用到当前照片。

管理端后端面向云端部署，负责知识库、样片、风格、观测和评测；摄影师日常修图不依赖管理端后端。Lightroom 插件端由 TonePilot Local Runtime 在本机完成对话、模型调用和 Lightroom 参数应用。真实修图效果以 Lightroom Classic 当前照片的 Develop Settings 为准。

## 快速启动

推荐在 WSL 目录中运行项目：

```bash
cd <项目目录>
```

启动本地依赖和后端：

```bash
chmod +x scripts/start-local-compose.sh
./scripts/start-local-compose.sh
```

脚本会启动 Docker Compose 中的 MySQL、Redis、MinIO、Milvus，并启动后端服务。默认 API 地址：

```text
http://localhost:8080
```

只快速验证 Java 服务时，也可以使用 H2 和本地文件存储：

```bash
cd tonepilot-admin/backend
mvn -pl starter -am spring-boot:run
```

启动管理端：

```bash
cd tonepilot-admin/frontend
npm install
npm run dev
```

默认管理端地址：

```text
http://localhost:5173
```

只启动基础设施：

```bash
docker compose up -d redis mysql minio etcd milvus
```

依赖用途：

- Redis：保存多 Agent 工作流上下文和 trace，支持分布式内存与服务重启后的上下文恢复。
- MySQL：生产形态关系数据库。开发时可用 H2 文件数据库。
- MinIO：本地模拟 OSS，用于管理端样片等文件对象存储。
- Milvus：向量数据库，用于调色知识库的向量检索和后续混合检索扩展。

## Lightroom 插件端

插件运行在 Windows 的 Lightroom Classic 中。TonePilot Local Runtime 是用户侧核心，推荐运行在 WSL：

```bash
cd <项目目录>/clients/lightroom-classic/local-runtime
chmod +x start-bridge-wsl.sh
./start-bridge-wsl.sh
```

第一次安装插件需要在 Windows PowerShell 执行：

```powershell
cd <项目目录>\clients\lightroom-classic\local-runtime
.\install-plugin.ps1
```

Lightroom 中的入口：

```text
文件 > 增效工具附加功能 > 打开 TonePilot Agent 控制台
```

插件端链路：

```text
Lightroom 当前选中照片
  -> Lua 插件读取照片信息和 Develop 参数
  -> TonePilot Local Runtime 打开 Agent 控制台
  -> 用户用对话描述调色意图
  -> Local Runtime 使用用户配置的 OpenAI/Qwen 生成参数
  -> Local Runtime 写入任务文件
  -> Lua 插件调用 photo:applyDevelopSettings 应用到当前照片
```

Local Runtime 默认地址：

```text
http://127.0.0.1:33335
```

检查 Local Runtime 状态：

```bash
curl http://127.0.0.1:33335/status
```

查看当前 Lightroom 选中照片状态：

```bash
curl http://127.0.0.1:33335/api/lightroom/selected-photo
```

## Local Runtime 核心 Agent 流程

Local Runtime 文件较少是有意设计：它不是云端管理后端，而是摄影师电脑上的 Lightroom 本地执行器。核心交互流程如下：

```text
Lightroom 选中照片
  -> Lua 插件写入 selected-photo.json、selected-preview.jpg 和当前 Develop 参数
  -> Local Runtime 读取当前照片状态
  -> 用户在 Agent 控制台输入修图意图
  -> Local Runtime 根据模型配置选择 OpenAI 或 Qwen
  -> 生成本轮 Develop Settings、参数 diff 和 Agent 回复
  -> Local Runtime 写入 apply-jobs
  -> Lua 插件调用 photo:applyDevelopSettings
  -> Lightroom 显示真实修图结果
```

因此它的核心模块集中在：

- `domain/`：Agent 会话和值对象。
- `repository/`：Lightroom 文件桥接端口。
- `infrastructure/`：Lightroom 文件协议、模型适配、观测日志和管理端客户端。
- `application/`：本地 API、Agent 对话编排、Lightroom 状态和工具用例。
- `starter/`：Spring Boot 启动类、配置、深灰 Agent 控制台和测试。

## 当前修图能力

已真实支持的是 Lightroom 全局 Develop Settings：

- 基础：曝光、对比度、高光、阴影、白色色阶、黑色色阶、色温、色调、纹理、清晰度、去朦胧、自然饱和度、饱和度。
- HSL：红、橙、黄、绿、浅绿、蓝、紫、洋红的色相、饱和度、明亮度。
- 效果：颗粒、暗角。
- 扩展项：曲线、锐化、降噪、颜色分级、镜头校正、透视变换、暗角细项、颗粒细项、相机校准、裁剪和方向等全局参数。

暂未真实自动应用：

- Lightroom AI 天空、主体、背景等局部蒙版。
- 修复画笔和内容识别移除。
- 局部区域参数写回。

后续可以先支持局部修图计划展示，再实验 Lightroom SDK 或插件可行的真实落地方式。

## 大模型配置

默认使用在线模型配置。模型通过 OpenAI 兼容接口接入，当前支持 OpenAI 和阿里 Qwen2，需要配置对应 API Key。

可以复制后端示例配置：

```bash
cd <项目目录>/tonepilot-admin/backend
cp starter/src/main/resources/application-local.yml.example starter/src/main/resources/application-local.yml
mvn -pl starter -am spring-boot:run -Dspring-boot.run.profiles=local
```

然后在 `application-local.yml` 里填写 `tonepilot.ai.openai` 或 `tonepilot.ai.qwen2`。

OpenAI 环境变量：

```bash
export TONEPILOT_AI_PROVIDER=openai
export OPENAI_API_KEY=你的_OpenAI_Key
export OPENAI_CHAT_MODEL=你的_OpenAI_文本模型
export OPENAI_VISION_MODEL=你的_OpenAI_视觉模型
```

阿里 Qwen2 环境变量：

```bash
export TONEPILOT_AI_PROVIDER=qwen2
export DASHSCOPE_API_KEY=你的_DashScope_Key
export QWEN2_CHAT_MODEL=你的_Qwen2_文本模型
export QWEN2_VISION_MODEL=你的_Qwen2_视觉模型
```

插件端请求也可以传入 `provider`，临时选择 `openai` 或 `qwen2`。

## 抖音调色教程导入知识库

管理端支持把抖音调色教程导入为待审核知识。当前推荐两种入口：

1. `抖音视频链接`：粘贴分享链接，同时在备注里粘贴字幕、调色步骤或教程摘要。这个入口完全由 Java 服务处理，不依赖第三方解析平台。
2. `上传抖音视频文件`：上传已经下载好的视频，管理端保存视频对象，由 Java 服务调用 `ffmpeg` 提取音频，再调用本机 `whisper.cpp` 生成字幕，最后写入知识库。

上传视频模式不依赖 AI Douyin、TikHub 等第三方视频解析平台；只需要你已经拿到本地视频文件，并准备好本机转写工具。

推荐本地依赖：

- `ffmpeg`：从视频中提取 16k 单声道 wav 音频。
- `whisper.cpp`：本地离线语音转文字。可使用 `whisper-cli` 或自己编译出的等价可执行文件。
- `ggml` 模型文件：例如 `ggml-small.bin`、`ggml-medium.bin`。模型文件不放入仓库，由部署环境自行配置。

上传视频模式配置示例：

```bash
export TONEPILOT_VIDEO_ASR_PROVIDER=whisper-cpp
export TONEPILOT_VIDEO_FFMPEG_BIN=ffmpeg
export TONEPILOT_VIDEO_WHISPER_CPP_BIN=whisper-cli
export TONEPILOT_VIDEO_WHISPER_MODEL=./models/ggml-small.bin
export TONEPILOT_VIDEO_LANGUAGE=zh
export TONEPILOT_VIDEO_TIMEOUT_SECONDS=900
```

调试时也可以临时使用固定字幕覆盖值，不调用本地 ASR：

```bash
export TONEPILOT_VIDEO_TRANSCRIPT_OVERRIDE="这里粘贴已经整理好的字幕或调色步骤"
```

导入入口：

```text
管理端 -> 素材导入 -> 抖音视频链接 -> 导入抖音并生成知识
管理端 -> 素材导入 -> 上传抖音视频文件 -> 上传视频并生成知识
```

## 项目结构

```text
TonePilot/
├── tonepilot-admin/                 云端管理端工程
│   ├── backend/                     Spring Boot 管理端后端 Maven 聚合工程
│   │   ├── domain/                  领域模型、领域规则和领域上下文
│   │   ├── repository/              Repository 接口和持久化端口
│   │   ├── infrastructure/          LLM、RAG、存储、数据库、观测等基础设施实现
│   │   ├── application/             Controller、DTO、用例服务和 Agent 编排
│   │   └── starter/                 启动类、全局配置、切面、资源和集成测试
│   └── frontend/                    Vue 3 管理端前端
├── clients/
│   └── lightroom-classic/
│       ├── local-runtime/           本地运行时 Maven 聚合工程
│       │   ├── domain/              Agent 会话和值对象
│       │   ├── repository/          Lightroom 文件桥接端口
│       │   ├── infrastructure/      模型、Bridge 文件协议、观测和管理端客户端
│       │   ├── application/         Agent 对话、Lightroom 用例服务和本地 API
│       │   └── starter/             启动类、配置、静态控制台和测试
│       └── plugin/                  Lightroom Classic Lua 插件源码
├── docs/                            架构说明
├── scripts/                         本地启动脚本
└── docker-compose.yml               Redis、MySQL、MinIO、Milvus 本地依赖
```

## 核心 API

插件端本地运行时：

- `GET /status`
- `GET /api/lightroom/selected-photo`
- `GET /api/runtime/config`
- `POST /api/runtime/config`
- `POST /api/lightroom-agent/chat`

管理端：

- `POST /api/admin/styles`
- `GET /api/admin/styles`
- `GET /api/admin/styles/{id}`
- `PUT /api/admin/styles/{id}`
- `DELETE /api/admin/styles/{id}`
- `POST /api/admin/style-samples/upload`
- `GET /api/admin/style-samples`
- `POST /api/admin/style-samples/{sampleId}/analyze`
- `POST /api/admin/style-samples/{sampleId}/generate-knowledge`
- `POST /api/knowledge`
- `GET /api/knowledge`
- `DELETE /api/knowledge/{id}`
- `GET /api/admin/knowledge`
- `PUT /api/admin/knowledge/{id}`
- `POST /api/admin/knowledge/{id}/approve`
- `POST /api/admin/knowledge/{id}/reject`
- `POST /api/admin/knowledge/{id}/disable`

观测与评测：

- `GET /api/observability/llm-calls`
- `GET /api/observability/audit-events`
- `POST /api/evaluation/benchmark`

## Agent 编排职责

TonePilot 分为两类 Agent 编排：

- 插件端 Local Runtime：负责 Lightroom 用户修图会话，读取当前照片状态，分析用户意图，使用用户配置的 OpenAI/Qwen 生成本轮 Develop Settings，并把任务交给 Lua 插件应用到当前照片。
- 管理端后端：负责云端知识库、样片分析、风格维护、RAG、自动评测和观测日志。它不直接控制本机 Lightroom，也不是摄影师日常修图的必需依赖。

## 验证命令

后端测试：

```bash
cd <项目目录>/tonepilot-admin/backend
mvn test
```

前端构建：

```bash
cd <项目目录>/tonepilot-admin/frontend
npm run build
```

Local Runtime 测试：

```bash
cd <项目目录>/clients/lightroom-classic/local-runtime
mvn test
```
