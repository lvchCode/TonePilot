<template>
  <div class="admin-shell">
    <aside class="sidebar">
      <div class="brand">
        <div class="brand-mark">TP</div>
        <div>
          <h1>TonePilot</h1>
          <p>调色风格管理端</p>
        </div>
      </div>

      <el-menu :default-active="activeView" class="nav-menu" @select="activeView = $event">
        <el-menu-item index="styles">
          <el-icon><Operation /></el-icon>
          <span>风格库</span>
        </el-menu-item>
        <el-menu-item index="knowledge">
          <el-icon><Collection /></el-icon>
          <span>知识库</span>
        </el-menu-item>
        <el-menu-item index="materials">
          <el-icon><Files /></el-icon>
          <span>素材导入</span>
        </el-menu-item>
        <el-menu-item index="samples">
          <el-icon><Picture /></el-icon>
          <span>样片管理</span>
        </el-menu-item>
        <el-menu-item index="observability">
          <el-icon><DataLine /></el-icon>
          <span>观测评估</span>
        </el-menu-item>
      </el-menu>
    </aside>

    <main class="workspace">
      <section class="topbar">
        <div>
          <h2>{{ pageTitle }}</h2>
          <p>{{ pageSubtitle }}</p>
        </div>
        <el-tag effect="plain" round>用户端：Lightroom Classic 插件</el-tag>
      </section>

      <section v-if="activeView === 'styles'" class="grid two">
        <div class="panel">
          <div class="panel-title">
            <h3>创建风格</h3>
            <el-button :icon="Plus" type="primary" @click="createStyle">保存</el-button>
          </div>
          <el-form label-position="top">
            <el-form-item label="风格名称">
              <el-input v-model="styleForm.styleName" placeholder="例如：夜景电影感" />
            </el-form-item>
            <el-form-item label="风格编码">
              <el-input v-model="styleForm.styleCode" placeholder="例如：night_cinematic" />
            </el-form-item>
            <el-form-item label="描述">
              <el-input v-model="styleForm.description" type="textarea" :rows="4" />
            </el-form-item>
            <el-form-item label="适用场景">
              <el-input v-model="styleForm.suitableScenes" placeholder="用逗号分隔" />
            </el-form-item>
            <el-form-item label="避免场景">
              <el-input v-model="styleForm.avoidScenes" placeholder="用逗号分隔" />
            </el-form-item>
          </el-form>
        </div>

        <div class="panel">
          <div class="panel-title">
            <h3>风格列表</h3>
            <el-button :icon="Refresh" @click="loadStyles">刷新</el-button>
          </div>
          <el-table :data="styles" height="560" @row-click="selectStyle">
            <el-table-column prop="styleName" label="名称" min-width="160" />
            <el-table-column prop="styleCode" label="编码" min-width="180" />
            <el-table-column prop="status" label="状态" width="100" />
            <el-table-column width="76">
              <template #default="{ row }">
                <el-button :icon="Delete" circle text @click.stop="deleteStyle(row.id)" />
              </template>
            </el-table-column>
          </el-table>
        </div>
      </section>

      <section v-if="activeView === 'knowledge'" class="grid two">
        <div class="panel">
          <div class="panel-title">
            <h3>新增调色知识</h3>
            <el-button :icon="Plus" type="primary" @click="createKnowledge">保存</el-button>
          </div>
          <el-form label-position="top">
            <el-form-item label="标题">
              <el-input v-model="knowledgeForm.title" />
            </el-form-item>
            <el-form-item label="场景">
              <el-input v-model="knowledgeForm.scene" />
            </el-form-item>
            <el-form-item label="目标风格">
              <el-input v-model="knowledgeForm.targetStyle" />
            </el-form-item>
            <el-form-item label="常见问题">
              <el-input v-model="knowledgeForm.problems" placeholder="用逗号或换行分隔" />
            </el-form-item>
            <el-form-item label="调色策略">
              <el-input v-model="knowledgeForm.strategy" type="textarea" :rows="5" />
            </el-form-item>
          </el-form>
        </div>

        <div class="panel">
          <div class="panel-title">
            <h3>知识审核</h3>
            <div class="title-actions">
              <el-select v-model="knowledgeStatus" class="status-select" @change="loadAdminKnowledge">
                <el-option label="全部" value="" />
                <el-option label="待审核" value="pending" />
                <el-option label="已启用" value="approved" />
                <el-option label="已拒绝" value="rejected" />
              </el-select>
              <el-button :icon="Refresh" @click="loadAdminKnowledge">刷新</el-button>
            </div>
          </div>
          <el-table :data="adminKnowledge" height="560">
            <el-table-column prop="title" label="标题" min-width="180" />
            <el-table-column prop="scene" label="场景" width="130" />
            <el-table-column prop="status" label="状态" width="110" />
            <el-table-column width="138">
              <template #default="{ row }">
                <el-button :icon="CircleCheck" circle text @click="approveKnowledge(row.id)" />
                <el-button :icon="Close" circle text @click="rejectKnowledge(row.id)" />
                <el-button :icon="SwitchButton" circle text @click="disableKnowledge(row.id)" />
              </template>
            </el-table-column>
          </el-table>
        </div>
      </section>

      <section v-if="activeView === 'materials'" class="grid materials-grid">
        <div class="panel source-panel">
          <div class="panel-title">
            <h3>素材来源</h3>
            <el-button :icon="Plus" type="primary" @click="createKnowledgeSource">保存来源</el-button>
          </div>

          <el-form label-position="top">
            <el-form-item label="来源类型">
              <el-select v-model="sourceForm.sourceType">
                <el-option label="抖音调色教程" value="douyin_video" />
                <el-option label="大师调色记录" value="master_edit_record" />
                <el-option label="手工笔记" value="manual_note" />
                <el-option label="风格样片" value="style_sample" />
              </el-select>
            </el-form-item>
            <el-form-item label="来源标题">
              <el-input v-model="sourceForm.title" placeholder="例如：夜景电影感调色教程" />
            </el-form-item>
            <el-form-item label="作者">
              <el-input v-model="sourceForm.author" placeholder="教程作者或摄影师" />
            </el-form-item>
            <el-form-item label="原始链接">
              <el-input v-model="sourceForm.originalUrl" placeholder="抖音链接、作品链接或记录来源" />
            </el-form-item>
            <el-form-item label="关联风格">
              <el-select v-model="sourceForm.styleId" clearable placeholder="可选">
                <el-option
                  v-for="style in styles"
                  :key="style.id"
                  :label="style.styleName"
                  :value="style.id"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="备注">
              <el-input v-model="sourceForm.notes" type="textarea" :rows="3" placeholder="来源背景、账号信息、适合沉淀的调色方向" />
            </el-form-item>
          </el-form>

          <el-divider />

          <div class="panel-title compact">
            <h3>来源列表</h3>
            <el-button :icon="Refresh" @click="loadKnowledgeSources">刷新</el-button>
          </div>
          <el-table :data="knowledgeSources" height="260" highlight-current-row @row-click="selectKnowledgeSource">
            <el-table-column prop="title" label="来源" min-width="180" />
            <el-table-column prop="sourceType" label="类型" width="150" />
            <el-table-column prop="author" label="作者" width="120" />
          </el-table>
        </div>

        <div class="panel import-panel">
          <div class="panel-title">
            <h3>导入工作台</h3>
            <el-tag v-if="selectedKnowledgeSource" effect="plain">
              当前来源：{{ selectedKnowledgeSource.title }}
            </el-tag>
          </div>

          <el-alert
            class="inline-alert"
            type="info"
            :closable="false"
            title="推荐上传视频文件：系统会抽音频转字幕，并抽关键帧做视觉分析；只有抖音链接时，请同时粘贴字幕、摘要或调色步骤。"
          />

          <el-tabs v-model="materialImportMode" class="material-tabs">
            <el-tab-pane label="上传视频解析" name="video">
              <el-form label-position="top" class="inline-import">
                <el-form-item label="抖音视频文件">
                  <el-upload
                    :auto-upload="false"
                    :limit="1"
                    accept=".mp4,.mov,.m4v,.webm"
                    :on-change="handleDouyinVideoFileChange"
                    :on-remove="removeDouyinVideoFile"
                  >
                    <el-button :icon="Upload">选择 MP4 / MOV / WEBM</el-button>
                  </el-upload>
                </el-form-item>
                <div class="form-row">
                  <el-form-item label="视频标题">
                    <el-input v-model="douyinUploadForm.title" placeholder="例如：watchluke 蓝调忧郁感" />
                  </el-form-item>
                  <el-form-item label="作者">
                    <el-input v-model="douyinUploadForm.author" placeholder="可选" />
                  </el-form-item>
                </div>
                <el-form-item label="备注">
                  <el-input v-model="douyinUploadForm.notes" type="textarea" :rows="4" placeholder="账号、期数、你希望保留的调色要点" />
                </el-form-item>
                <el-button :icon="Upload" type="primary" :loading="uploadingDouyinVideo" @click="uploadDouyinVideo">
                  上传视频，转字幕并分析画面
                </el-button>
              </el-form>
            </el-tab-pane>

            <el-tab-pane label="链接 + 字幕摘要" name="link">
              <el-form label-position="top" class="inline-import">
                <el-form-item label="抖音分享文案或链接">
                  <el-input v-model="douyinForm.videoUrl" placeholder="粘贴抖音作品链接或完整分享文案" />
                </el-form-item>
                <div class="form-row">
                  <el-form-item label="标题">
                    <el-input v-model="douyinForm.title" placeholder="例如：城市夜景电影感教程" />
                  </el-form-item>
                  <el-form-item label="作者">
                    <el-input v-model="douyinForm.author" placeholder="可选" />
                  </el-form-item>
                </div>
                <el-form-item label="字幕 / 摘要 / 调色步骤">
                  <el-input v-model="douyinForm.notes" type="textarea" :rows="6" placeholder="必须填写：粘贴视频字幕、调色步骤或教程摘要；只贴链接无法获取画面和音频。" />
                </el-form-item>
                <el-button :icon="Upload" type="primary" :loading="importingDouyin" @click="importDouyinVideo">
                  导入链接素材并生成知识
                </el-button>
              </el-form>
            </el-tab-pane>

            <el-tab-pane label="手工素材" name="manual">
              <el-form label-position="top">
                <el-form-item label="素材类型">
                  <el-select v-model="materialForm.materialType">
                    <el-option label="字幕/转写文本" value="transcript" />
                    <el-option label="教程摘要" value="summary" />
                    <el-option label="Lightroom 参数" value="lightroom_params" />
                    <el-option label="参数变化记录" value="param_delta" />
                    <el-option label="XMP 片段" value="xmp" />
                    <el-option label="手工文本" value="manual_text" />
                  </el-select>
                </el-form-item>
                <el-form-item label="素材标题">
                  <el-input v-model="materialForm.title" placeholder="例如：第 1 段字幕摘要" />
                </el-form-item>
                <el-form-item label="语言">
                  <el-input v-model="materialForm.language" />
                </el-form-item>
                <el-form-item label="素材内容">
                  <el-input v-model="materialForm.content" type="textarea" :rows="8" placeholder="粘贴字幕、教程摘要、调色参数或 XMP 内容" />
                </el-form-item>
                <el-button :icon="Upload" type="primary" @click="importKnowledgeMaterial">导入到当前来源</el-button>
              </el-form>
            </el-tab-pane>
          </el-tabs>

          <div v-if="lastIngestionJob" class="ingestion-result">
            <strong>最近导入</strong>
            <span>来源 #{{ lastIngestionJob.sourceId }} / 素材 #{{ lastIngestionJob.materialId }}</span>
            <span>已生成待审核知识 #{{ lastIngestionJob.generatedKnowledgeId }}</span>
          </div>
        </div>

        <div class="panel materials-panel">
          <div class="panel-title">
            <h3>当前来源素材</h3>
            <el-button
              :icon="Refresh"
              :disabled="!selectedSourceId"
              @click="selectedSourceId && loadKnowledgeMaterials(selectedSourceId)"
            >
              刷新素材
            </el-button>
          </div>
          <el-table :data="knowledgeMaterials" height="320">
            <el-table-column prop="title" label="素材" min-width="220" />
            <el-table-column prop="materialType" label="类型" width="150" />
            <el-table-column prop="language" label="语言" width="100" />
            <el-table-column width="130">
              <template #default="{ row }">
                <el-button
                  size="small"
                  :loading="extractingMaterialId === row.id"
                  @click="extractKnowledge(row.id)"
                >
                  生成知识
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </section>

      <section v-if="activeView === 'samples'" class="grid two">
        <div class="panel">
          <div class="panel-title">
            <h3>上传风格样片</h3>
            <el-button :icon="Upload" type="primary" @click="uploadSample">上传</el-button>
          </div>
          <el-alert
            class="inline-alert"
            type="info"
            :closable="false"
            title="样片用于管理端沉淀风格知识；用户修图入口在 Lightroom 插件中。"
          />
          <el-form label-position="top">
            <el-form-item label="所属风格">
              <el-select v-model="sampleForm.styleId" placeholder="选择风格">
                <el-option
                  v-for="style in styles"
                  :key="style.id"
                  :label="style.styleName"
                  :value="style.id"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="样片说明">
              <el-input v-model="sampleForm.description" type="textarea" :rows="3" />
            </el-form-item>
            <el-form-item label="标签">
              <el-input v-model="sampleForm.tags" placeholder="夜景, 人像, 霓虹" />
            </el-form-item>
            <el-form-item label="成片">
              <el-upload :auto-upload="false" :limit="1" :on-change="handleSampleFileChange">
                <el-button :icon="Picture">选择文件</el-button>
              </el-upload>
            </el-form-item>
          </el-form>
        </div>

        <div class="panel">
          <div class="panel-title">
            <h3>样片列表</h3>
            <el-button :icon="Refresh" @click="loadSamples">刷新</el-button>
          </div>
          <el-table :data="samples" height="560">
            <el-table-column prop="description" label="说明" min-width="180" />
            <el-table-column prop="sampleType" label="类型" width="120" />
            <el-table-column prop="status" label="状态" width="110" />
            <el-table-column width="150">
              <template #default="{ row }">
                <el-button size="small" @click="analyzeSample(row.id)">分析</el-button>
                <el-button size="small" @click="generateKnowledge(row.id)">生成知识</el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </section>

      <section v-if="activeView === 'observability'" class="grid observability-grid">
        <div class="panel runtime-panel">
          <div class="panel-title">
            <h3>运行时调用链</h3>
            <div class="title-actions">
              <el-button :icon="Refresh" @click="loadRuntimeObservability">刷新链路</el-button>
            </div>
          </div>

          <div class="runtime-filters">
            <el-select v-model="runtimeFilters.userId" placeholder="选择运行时用户" filterable @change="loadRuntimeEvents">
              <el-option
                v-for="device in runtimeDevices"
                :key="device.deviceId"
                :label="`${device.deviceName || 'TonePilot Runtime'} / ${device.userId}`"
                :value="device.userId"
              />
            </el-select>
            <el-input v-model="runtimeFilters.sessionId" clearable placeholder="Session ID" @keyup.enter="loadRuntimeEvents" />
            <el-input v-model="runtimeFilters.traceId" clearable placeholder="Trace ID" @keyup.enter="loadRuntimeEvents" />
            <el-input v-model="runtimeFilters.eventType" clearable placeholder="事件类型" @keyup.enter="loadRuntimeEvents" />
            <el-button type="primary" @click="loadRuntimeEvents">查询</el-button>
          </div>

          <div class="runtime-overview">
            <div class="runtime-stat"><strong>{{ runtimeEventStats.sessionCount }}</strong><span>会话</span></div>
            <div class="runtime-stat"><strong>{{ runtimeEventStats.traceCount }}</strong><span>Trace</span></div>
            <div class="runtime-stat"><strong>{{ runtimeEventStats.eventCount }}</strong><span>事件</span></div>
          </div>

          <div class="runtime-view-toolbar">
            <el-radio-group v-model="runtimeEventViewMode" size="small">
              <el-radio-button label="process">关键链路</el-radio-button>
              <el-radio-button label="all">全部事件</el-radio-button>
            </el-radio-group>
            <span>默认隐藏状态检查、文件读取等轮询噪声。</span>
          </div>

          <div v-if="runtimeEventViewMode === 'process'" class="runtime-executions">
            <button
              v-for="execution in runtimeExecutions"
              :key="execution.id"
              type="button"
              class="runtime-execution"
              @click="selectRuntimeEvent(execution.event)"
            >
              <strong>{{ execution.title }}</strong>
              <span>{{ execution.summary }}</span>
              <div class="execution-stages">
                <el-tag
                  v-for="stage in execution.stages"
                  :key="stage.key"
                  :type="stage.done ? 'success' : 'info'"
                  size="small"
                  effect="plain"
                >
                  {{ stage.label }}
                </el-tag>
              </div>
            </button>
          </div>

          <el-tree
            class="runtime-tree"
            :data="runtimeTree"
            node-key="id"
            default-expand-all
            highlight-current
            empty-text="暂无运行时事件"
            @node-click="selectRuntimeTreeNode"
          >
            <template #default="{ data }">
              <div class="runtime-tree-node" :class="`node-${data.type}`">
                <span class="tree-title">{{ data.label }}</span>
                <el-tag v-if="data.badge" :type="runtimeNodeTagType(data.type)" size="small" effect="plain">
                  {{ data.badge }}
                </el-tag>
                <span v-if="data.summary" class="tree-summary">{{ data.summary }}</span>
              </div>
            </template>
          </el-tree>

          <div class="runtime-detail">
            <div class="detail-heading">
              <span>事件详情</span>
              <el-tag v-if="selectedRuntimeEvent" size="small" effect="plain">{{ eventTypeLabel(selectedRuntimeEvent.eventType) }}</el-tag>
            </div>
            <pre>{{ selectedRuntimePayload || '选择一条运行时事件后查看完整 payload。' }}</pre>
          </div>
        </div>

        <div class="panel">
          <div class="panel-title">
            <h3>LLM 调用</h3>
            <el-button :icon="Refresh" @click="loadObservability">刷新</el-button>
          </div>
          <el-table :data="llmCalls" height="260">
            <el-table-column prop="provider" label="模型厂商" width="120" />
            <el-table-column prop="model" label="模型" width="150" />
            <el-table-column prop="purpose" label="用途" min-width="160" />
            <el-table-column prop="success" label="成功" width="90" />
          </el-table>

          <el-divider />

          <div class="panel-title compact">
            <h3>审计事件</h3>
            <el-button :icon="DataAnalysis" @click="runBenchmark">运行评估</el-button>
          </div>
          <el-table :data="auditEvents" height="260">
            <el-table-column prop="eventType" label="事件" width="150" />
            <el-table-column prop="actor" label="来源" width="130" />
            <el-table-column prop="summary" label="摘要" min-width="220" />
          </el-table>
          <div v-if="benchmarkSummary" class="benchmark-box">
            {{ benchmarkSummary }}
          </div>
        </div>
      </section>
    </main>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { UploadFile } from 'element-plus'
import {
  CircleCheck,
  Close,
  Collection,
  DataAnalysis,
  DataLine,
  Delete,
  Files,
  Operation,
  Picture,
  Plus,
  Refresh,
  SwitchButton,
  Upload
} from '@element-plus/icons-vue'
import { api, unwrap } from './api'


type RuntimeTreeNode = {
  id: string
  type: 'user' | 'session' | 'trace' | 'event'
  label: string
  badge?: string
  summary?: string
  event?: any
  children?: RuntimeTreeNode[]
}

const activeView = ref('styles')
const styles = ref<any[]>([])
const adminKnowledge = ref<any[]>([])
const knowledgeSources = ref<any[]>([])
const knowledgeMaterials = ref<any[]>([])
const samples = ref<any[]>([])
const llmCalls = ref<any[]>([])
const auditEvents = ref<any[]>([])
const runtimeDevices = ref<any[]>([])
const runtimeEvents = ref<any[]>([])
const selectedRuntimeEvent = ref<any | undefined>()
const selectedRuntimePayload = ref('')
const runtimeEventViewMode = ref<'process' | 'all'>('process')
const knowledgeStatus = ref('')
const selectedSourceId = ref<number | undefined>()
const materialImportMode = ref('video')
const lastIngestionJob = ref<any | undefined>()
const extractingMaterialId = ref<number | undefined>()
const importingDouyin = ref(false)
const uploadingDouyinVideo = ref(false)
const douyinVideoFile = ref<File | undefined>()
const sampleFile = ref<File | undefined>()
const benchmarkSummary = ref('')
const runtimeFilters = reactive({
  userId: '',
  sessionId: '',
  traceId: '',
  eventType: '',
  limit: 200
})

const NOISE_EVENT_TYPES = new Set([
  'api.status.request',
  'api.selected_photo.request',
  'api.file.request'
])

const runtimeEventLabels: Record<string, string> = {
  'agent.thought': '主 Agent 判断',
  'agent.final': 'Agent 完成',
  'agent.error': 'Agent 异常',
  'agent.intent.analyzed': '意图分析',
  'model.request': '模型请求',
  'model.response': '模型返回',
  'model.parse.analysis_only': '模型解析',
  'llm.request': '模型请求',
  'llm.response': '模型返回',
  'lightroom.apply.submitted': 'Lightroom 工具提交',
  'lightroom.apply.status.finished': 'Lightroom 工具结果',
  'lightroom.apply.status.pending': 'Lightroom 工具处理中',
  'tool.lightroom.apply.result': 'Lightroom 工具结果',
  'api.selected_photo.request': '读取当前照片',
  'api.status.request': '运行时状态检查',
  'api.file.request': '预览文件读取'
}

const currentRuntimeDevice = computed(() => runtimeDevices.value.find(device => device.userId === runtimeFilters.userId))
const visibleRuntimeEvents = computed(() => runtimeEventViewMode.value === 'all'
  ? runtimeEvents.value
  : runtimeEvents.value.filter(event => !NOISE_EVENT_TYPES.has(event.eventType))
)
const runtimeExecutions = computed(() => buildRuntimeExecutionSummaries(visibleRuntimeEvents.value))
const selectedKnowledgeSource = computed(() => knowledgeSources.value.find(source => source.id === selectedSourceId.value))

const runtimeEventStats = computed(() => {
  const sessions = new Set<string>()
  const traces = new Set<string>()
  visibleRuntimeEvents.value.forEach(event => {
    sessions.add(event.sessionId || '无会话')
    traces.add(payloadTraceId(event) || '无 Trace')
  })
  return {
    sessionCount: sessions.size,
    traceCount: traces.size,
    eventCount: visibleRuntimeEvents.value.length
  }
})

const runtimeTree = computed<RuntimeTreeNode[]>(() => buildRuntimeTree())


const styleForm = reactive({
  styleName: '夜景电影感',
  styleCode: 'night_cinematic',
  description: '压住高光、提亮暗部、增强对比和空气感，适合城市夜景与霓虹人像。',
  suitableScenes: '城市夜景, 霓虹, 夜景人像',
  avoidScenes: '高调日系, 儿童写真'
})

const knowledgeForm = reactive({
  title: '夜景电影感调色策略',
  scene: '城市夜景',
  targetStyle: '夜景电影感',
  problems: '灯光高光过亮, 暗部细节不足, 绿色偏脏',
  strategy: '降低高光保留灯牌细节\n轻微提升阴影恢复暗部\n增加对比和去朦胧\n降低绿色饱和度并增加暗角聚焦'
})

const sourceForm = reactive({
  sourceType: 'douyin_video',
  title: '夜景电影感调色教程',
  author: '',
  originalUrl: '',
  styleId: undefined as number | undefined,
  notes: ''
})

const materialForm = reactive({
  materialType: 'transcript',
  title: '字幕摘要',
  content: '',
  language: 'zh-CN'
})

const douyinForm = reactive({
  videoUrl: '',
  title: '抖音调色教程',
  author: '',
  notes: '',
  styleId: undefined as number | undefined
})

const douyinUploadForm = reactive({
  title: '上传抖音调色教程',
  author: '',
  notes: '',
  styleId: undefined as number | undefined
})

const sampleForm = reactive({
  styleId: undefined as number | undefined,
  description: '夜景样片，灯牌高光明显，暗部有可恢复细节。',
  tags: '夜景, 电影感, 霓虹'
})

const pageTitle = computed(() => {
  if (activeView.value === 'knowledge') return '调色知识库'
  if (activeView.value === 'materials') return '调色素材导入'
  if (activeView.value === 'samples') return '风格样片'
  if (activeView.value === 'observability') return '观测与评估'
  return '风格库'
})

const pageSubtitle = computed(() => {
  if (activeView.value === 'knowledge') return '维护 Agent 可检索的场景策略、参数经验和审核状态'
  if (activeView.value === 'materials') return '登记抖音教程、大师调色记录、手工笔记等来源，并抽取成待审核知识'
  if (activeView.value === 'samples') return '上传管理员样片，分析风格并生成可审核知识'
  if (activeView.value === 'observability') return '追踪本地运行时用户输入、Agent 决策、大模型回复和 Lightroom 工具调用'
  return '维护用户端插件可引用的调色风格定义'
})

watch(activeView, async value => {
  if (value === 'materials') {
    await Promise.all([loadStyles(), loadKnowledgeSources()])
  }
  if (value === 'samples') {
    await Promise.all([loadStyles(), loadSamples()])
  }
  if (value === 'observability') {
    await loadObservability()
  }
})

function selectStyle(row: any) {
  sampleForm.styleId = row.id
  sourceForm.styleId = row.id
  douyinForm.styleId = row.id
  douyinUploadForm.styleId = row.id
}

async function createStyle() {
  await unwrap(api.post('/api/admin/styles', {
    styleName: styleForm.styleName,
    styleCode: styleForm.styleCode,
    description: styleForm.description,
    suitableScenes: splitValues(styleForm.suitableScenes),
    avoidScenes: splitValues(styleForm.avoidScenes),
    status: 'enabled'
  }))
  ElMessage.success('风格已保存')
  await loadStyles()
}

async function loadStyles() {
  styles.value = await unwrap(api.get('/api/admin/styles'))
  if (!sampleForm.styleId && styles.value.length > 0) {
    sampleForm.styleId = styles.value[0].id
  }
}

async function deleteStyle(id: number) {
  await unwrap(api.delete(`/api/admin/styles/${id}`))
  ElMessage.success('风格已删除')
  await loadStyles()
}

async function createKnowledge() {
  await unwrap(api.post('/api/knowledge', {
    title: knowledgeForm.title,
    scene: knowledgeForm.scene,
    targetStyle: knowledgeForm.targetStyle,
    problems: splitValues(knowledgeForm.problems),
    strategy: splitValues(knowledgeForm.strategy),
    paramRanges: {
      highlights: '-30 ~ -60',
      shadows: '+10 ~ +35',
      dehaze: '+3 ~ +15',
      greenSaturation: '-10 ~ -30'
    }
  }))
  ElMessage.success('知识已保存')
  await loadAdminKnowledge()
}

async function loadAdminKnowledge() {
  const params = knowledgeStatus.value ? { status: knowledgeStatus.value } : undefined
  adminKnowledge.value = await unwrap(api.get('/api/admin/knowledge', { params }))
}

async function approveKnowledge(id: number) {
  await unwrap(api.post(`/api/admin/knowledge/${id}/approve`))
  await loadAdminKnowledge()
}

async function rejectKnowledge(id: number) {
  await unwrap(api.post(`/api/admin/knowledge/${id}/reject`))
  await loadAdminKnowledge()
}

async function disableKnowledge(id: number) {
  await unwrap(api.post(`/api/admin/knowledge/${id}/disable`))
  await loadAdminKnowledge()
}

async function createKnowledgeSource() {
  const source = await unwrap<any>(api.post('/api/admin/knowledge-sources', {
    sourceType: sourceForm.sourceType,
    title: sourceForm.title,
    author: sourceForm.author,
    originalUrl: sourceForm.originalUrl,
    styleId: sourceForm.styleId,
    notes: sourceForm.notes
  }))
  selectedSourceId.value = source.id
  ElMessage.success('素材来源已保存')
  await loadKnowledgeSources()
}

async function loadKnowledgeSources() {
  knowledgeSources.value = await unwrap(api.get('/api/admin/knowledge-sources'))
  if (!selectedSourceId.value && knowledgeSources.value.length > 0) {
    selectedSourceId.value = knowledgeSources.value[0].id
  }
  if (selectedSourceId.value) {
    await loadKnowledgeMaterials(selectedSourceId.value)
  }
}

async function selectKnowledgeSource(row: any) {
  selectedSourceId.value = row.id
  sourceForm.styleId = row.styleId
  await loadKnowledgeMaterials(row.id)
}

async function loadKnowledgeMaterials(sourceId: number) {
  knowledgeMaterials.value = await unwrap(api.get(`/api/admin/knowledge-sources/${sourceId}/materials`))
}

async function importKnowledgeMaterial() {
  if (!selectedSourceId.value) {
    ElMessage.warning('请先选择或创建一个素材来源')
    return
  }
  const material = await unwrap<any>(api.post(`/api/admin/knowledge-sources/${selectedSourceId.value}/materials`, {
    materialType: materialForm.materialType,
    title: materialForm.title,
    content: materialForm.content,
    language: materialForm.language
  }))
  lastIngestionJob.value = { sourceId: selectedSourceId.value, materialId: material.id, generatedKnowledgeId: '-' }
  materialForm.content = ''
  ElMessage.success('素材已导入')
  await loadKnowledgeMaterials(selectedSourceId.value)
}

async function importDouyinVideo() {
  if (!douyinForm.videoUrl.trim()) {
    ElMessage.warning('请先填写抖音视频链接')
    return
  }
  if (!douyinForm.notes.trim()) {
    ElMessage.warning('链接导入需要粘贴字幕、摘要或调色步骤；只有视频文件请使用上传视频解析')
    return
  }
  importingDouyin.value = true
  try {
    const job = await unwrap<any>(api.post('/api/admin/knowledge-sources/douyin-imports', {
      videoUrl: douyinForm.videoUrl,
      title: douyinForm.title,
      author: douyinForm.author,
      styleId: douyinForm.styleId || sourceForm.styleId,
      notes: douyinForm.notes
    }))
    lastIngestionJob.value = job
    selectedSourceId.value = job.sourceId
    ElMessage.success(`已生成待审核知识 #${job.generatedKnowledgeId}`)
    douyinForm.videoUrl = ''
    douyinForm.notes = ''
    await Promise.all([loadKnowledgeSources(), loadAdminKnowledge()])
  } finally {
    importingDouyin.value = false
  }
}

function handleDouyinVideoFileChange(file: UploadFile) {
  douyinVideoFile.value = file.raw
}

function removeDouyinVideoFile() {
  douyinVideoFile.value = undefined
}

async function uploadDouyinVideo() {
  if (!douyinVideoFile.value) {
    ElMessage.warning('请先选择一个视频文件')
    return
  }
  uploadingDouyinVideo.value = true
  try {
    const formData = new FormData()
    formData.append('file', douyinVideoFile.value)
    formData.append('title', douyinUploadForm.title)
    formData.append('author', douyinUploadForm.author)
    formData.append('notes', douyinUploadForm.notes)
    const styleId = douyinUploadForm.styleId || sourceForm.styleId
    if (styleId) formData.append('styleId', String(styleId))
    const job = await unwrap<any>(api.post('/api/admin/knowledge-sources/douyin-video-uploads', formData))
    lastIngestionJob.value = job
    selectedSourceId.value = job.sourceId
    ElMessage.success(`已生成待审核知识 #${job.generatedKnowledgeId}`)
    douyinVideoFile.value = undefined
    douyinUploadForm.notes = ''
    await Promise.all([loadKnowledgeSources(), loadAdminKnowledge()])
  } finally {
    uploadingDouyinVideo.value = false
  }
}

async function extractKnowledge(materialId: number) {
  if (!selectedSourceId.value) return
  extractingMaterialId.value = materialId
  try {
    await unwrap(api.post(`/api/admin/knowledge-sources/${selectedSourceId.value}/materials/${materialId}/extract`, {}))
    ElMessage.success('已生成待审核知识')
    await Promise.all([loadKnowledgeMaterials(selectedSourceId.value), loadAdminKnowledge()])
  } finally {
    extractingMaterialId.value = undefined
  }
}

async function uploadSample() {
  if (!sampleForm.styleId) {
    ElMessage.warning('请先选择所属风格')
    return
  }
  if (!sampleFile.value) {
    ElMessage.warning('请选择一张成片样片')
    return
  }
  const formData = new FormData()
  formData.append('styleId', String(sampleForm.styleId))
  formData.append('sampleType', 'final_only')
  formData.append('sourceType', 'manual_upload')
  formData.append('description', sampleForm.description)
  splitValues(sampleForm.tags).forEach(tag => formData.append('tags', tag))
  formData.append('finalImage', sampleFile.value)
  await unwrap(api.post('/api/admin/style-samples/upload', formData))
  ElMessage.success('样片已上传')
  await loadSamples()
}

async function loadSamples() {
  const params = sampleForm.styleId ? { styleId: sampleForm.styleId } : undefined
  samples.value = await unwrap(api.get('/api/admin/style-samples', { params }))
}

function handleSampleFileChange(file: UploadFile) {
  sampleFile.value = file.raw
}

async function analyzeSample(id: number) {
  await unwrap(api.post(`/api/admin/style-samples/${id}/analyze`))
  ElMessage.success('样片分析已完成')
  await loadSamples()
}

async function generateKnowledge(id: number) {
  await unwrap(api.post(`/api/admin/style-samples/${id}/generate-knowledge`))
  ElMessage.success('已生成待审核知识')
  await loadAdminKnowledge()
}

async function loadObservability() {
  const [calls, events] = await Promise.all([
    unwrap<any[]>(api.get('/api/observability/llm-calls', { params: { limit: 50 } })),
    unwrap<any[]>(api.get('/api/observability/audit-events', { params: { limit: 50 } }))
  ])
  llmCalls.value = calls
  auditEvents.value = events
}

async function loadRuntimeObservability() {
  runtimeDevices.value = await unwrap<any[]>(api.get('/api/runtime/devices'))
  if (!runtimeFilters.userId && runtimeDevices.value.length > 0) {
    runtimeFilters.userId = runtimeDevices.value[0].userId
  }
  await loadRuntimeEvents()
}

async function loadRuntimeEvents() {
  if (!runtimeFilters.userId) {
    runtimeEvents.value = []
    selectedRuntimeEvent.value = undefined
    selectedRuntimePayload.value = ''
    return
  }
  runtimeEvents.value = await unwrap<any[]>(api.get('/api/runtime/events', {
    params: {
      userId: runtimeFilters.userId,
      sessionId: runtimeFilters.sessionId || undefined,
      traceId: runtimeFilters.traceId || undefined,
      eventType: runtimeFilters.eventType || undefined,
      limit: runtimeFilters.limit
    }
  }))
  selectedRuntimeEvent.value = runtimeEvents.value[0]
  selectedRuntimePayload.value = selectedRuntimeEvent.value ? prettyPayload(selectedRuntimeEvent.value) : ''
}

function buildRuntimeTree(): RuntimeTreeNode[] {
  if (!runtimeFilters.userId) return []
  const device = currentRuntimeDevice.value
  const root: RuntimeTreeNode = {
    id: `user:${runtimeFilters.userId}`,
    type: 'user',
    label: `用户：${runtimeFilters.userId}`,
    badge: device?.deviceName || 'TonePilot Local Runtime',
    summary: device?.lastSeenAt ? `最近在线：${formatTime(device.lastSeenAt)}` : ''
  }
  const sessionMap = new Map<string, RuntimeTreeNode>()
  visibleRuntimeEvents.value.forEach((event, eventIndex) => {
    const sessionId = event.sessionId || '无会话'
    if (!sessionMap.has(sessionId)) {
      sessionMap.set(sessionId, {
        id: `session:${sessionId}`,
        type: 'session',
        label: `会话：${sessionId}`,
        badge: 'Session',
        children: []
      })
    }
    const sessionNode = sessionMap.get(sessionId)!
    const traceId = payloadTraceId(event) || '无 Trace'
    let traceNode = sessionNode.children!.find(child => child.id === `trace:${sessionId}:${traceId}`)
    if (!traceNode) {
      traceNode = {
        id: `trace:${sessionId}:${traceId}`,
        type: 'trace',
        label: `Trace：${traceId}`,
        badge: 'Trace',
        children: []
      }
      sessionNode.children!.push(traceNode)
    }
    traceNode.children!.push({
      id: `event:${event.id || event.createdAt || eventIndex}`,
      type: 'event',
      label: `${formatTime(event.createdAt)} · ${eventTypeLabel(event.eventType)}`,
      badge: event.eventType,
      summary: runtimeSummary(event),
      event
    })
  })
  root.children = Array.from(sessionMap.values()).map(session => ({
    ...session,
    summary: `${countEventChildren(session)} 个事件`
  }))
  return [root]
}

function buildRuntimeExecutionSummaries(events: any[]) {
  const groups = new Map<string, any[]>()
  events.forEach(event => {
    const key = `${event.sessionId || '无会话'}:${payloadTraceId(event) || '无 Trace'}`
    if (!groups.has(key)) groups.set(key, [])
    groups.get(key)!.push(event)
  })

  return Array.from(groups.entries()).map(([key, group]) => {
    const latest = group[0]
    const first = group[group.length - 1] || latest
    const payloads = group.map(runtimePayload)
    const title = runtimeExecutionTitle(group, payloads)
    const summary = runtimeExecutionSummary(group, payloads)
    return {
      id: `execution:${key}`,
      title,
      summary,
      event: latest || first,
      stages: [
        { key: 'user', label: '用户输入', done: hasRuntimeStep(group, ['agent.request.received', 'agent.started']) },
        { key: 'agent', label: 'Agent 判断', done: hasRuntimeStep(group, ['agent.thought', 'agent.intent.analyzed']) },
        { key: 'model', label: '模型返回', done: hasRuntimeStep(group, ['model.response', 'llm.response']) },
        { key: 'tool', label: 'Lightroom 工具', done: hasRuntimeStep(group, ['tool.result', 'lightroom.apply.submitted', 'agent.tool.submitted']) },
        { key: 'final', label: '完成结果', done: hasRuntimeStep(group, ['agent.final', 'lightroom.apply.status.finished']) }
      ]
    }
  }).filter(item => item.title || item.summary)
}

function hasRuntimeStep(events: any[], steps: string[]) {
  return events.some(event => steps.includes(event.eventType))
}

function runtimeExecutionTitle(events: any[], payloads: any[]) {
  const firstMessage = payloads.map(payload => payload.details?.message || payload.details?.userMessage || payload.message).find(Boolean)
  const finalPayload = payloads.find(payload => payload.step === 'agent.final' || payload.type === 'agent.final')
  const finalTitle = finalPayload?.details?.title || finalPayload?.title
  return String(firstMessage || finalTitle || eventTypeLabel(events[0]?.eventType || '') || '运行时请求').slice(0, 80)
}

function runtimeExecutionSummary(events: any[], payloads: any[]) {
  const thought = payloads.find(payload => payload.type === 'agent.thought' || payload.step === 'agent.thought')
  const content = thought?.content || thought?.details?.content
  if (content) return String(content).slice(0, 120)
  const finalPayload = payloads.find(payload => payload.type === 'agent.final' || payload.step === 'agent.final')
  const finalMessage = finalPayload?.content || finalPayload?.details?.content
  if (finalMessage) return String(finalMessage).slice(0, 120)
  return runtimeSummary(events[0] || {})
}

function countEventChildren(node: RuntimeTreeNode): number {
  if (node.type === 'event') return 1
  return (node.children || []).reduce((total, child) => total + countEventChildren(child), 0)
}

function eventTypeLabel(type: string) {
  return runtimeEventLabels[type] || type || '未知事件'
}

function runtimeNodeTagType(type: RuntimeTreeNode['type']) {
  if (type === 'user') return 'primary'
  if (type === 'session') return 'success'
  if (type === 'trace') return 'warning'
  return 'info'
}

function selectRuntimeTreeNode(node: RuntimeTreeNode) {
  if (node.event) {
    selectRuntimeEvent(node.event)
  }
}

function payloadTraceId(row: any) {
  return runtimePayload(row).traceId || ''
}

function selectRuntimeEvent(row: any) {
  selectedRuntimeEvent.value = row
  selectedRuntimePayload.value = prettyPayload(row)
}

function runtimePayload(row: any) {
  try {
    return JSON.parse(row.payloadJson || '{}')
  } catch {
    return {}
  }
}

function runtimeSummary(row: any) {
  const payload = runtimePayload(row)
  const details = payload.details || payload
  if (details.responseBody) return String(details.responseBody).slice(0, 90)
  if (details.userMessage) return String(details.userMessage).slice(0, 90)
  if (details.message) return String(details.message).slice(0, 90)
  if (details.error) return String(details.error).slice(0, 90)
  if (details.analysis) return JSON.stringify(details.analysis).slice(0, 90)
  return JSON.stringify(details).slice(0, 90)
}

function prettyPayload(row: any) {
  return JSON.stringify(runtimePayload(row), null, 2)
}

function formatTime(value: string) {
  if (!value) return '-'
  return new Date(value).toLocaleString('zh-CN', { hour12: false })
}

async function runBenchmark() {
  const report = await unwrap<any>(api.post('/api/evaluation/benchmark', {}))
  benchmarkSummary.value = `评估完成：${report.caseResults?.length ?? 0} 个用例，综合分 ${report.averageScore ?? '-'}`
}

function splitValues(value: string) {
  return value.split(/[\n,，]+/).map(item => item.trim()).filter(Boolean)
}

onMounted(async () => {
  await Promise.all([loadStyles(), loadAdminKnowledge(), loadKnowledgeSources()])
})
</script>
