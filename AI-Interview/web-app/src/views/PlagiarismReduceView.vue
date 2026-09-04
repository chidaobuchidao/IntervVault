<template>
  <div class="app-shell">
    <!-- Header -->
    <header class="app-header">
      <div class="app-brand">
        <button class="btn btn-icon" @click="router.replace('/')">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <polyline points="15 18 9 12 15 6" />
          </svg>
        </button>
        <span class="brand-name">Mianmian.</span>
        <span class="brand-tag">降查重</span>
        <button class="btn btn-outline btn-switch" @click="router.replace('/paper-tools/polish')">学术润色</button>
        <button class="btn btn-outline btn-switch" @click="router.replace('/paper-tools/ai-reduce')">降AI</button>
      </div>
      <div style="display:flex;align-items:center;gap:16px;margin-left:auto;">
        <div v-if="hasOptions" class="capsule-toggle">
          <div class="capsule-slider" :style="{ width: (100 / options.length) + '%', transform: 'translateX(' + (options.findIndex(o => o.id === currentModel) * 100) + '%)' }" />
          <button v-for="opt in options" :key="opt.id" class="capsule-opt" :class="{ active: currentModel === opt.id }" @click="selectModel(opt.id)">{{ opt.label }}</button>
        </div>
        <div v-else class="capsule-toggle">
          <span class="capsule-opt active" style="cursor:default;padding:5px 12px;">{{ selectedLabel }}</span>
        </div>
        <span v-if="quotaInfo.quotaRemaining >= 0" class="quota-badge" :class="{ 'quota-low': quotaInfo.quotaRemaining <= 2 && !quotaInfo.unlimited }">
          {{ quotaInfo.unlimited ? '无限次' : `剩余 ${quotaInfo.quotaRemaining}/${quotaInfo.dailyQuota} 次` }}
        </span>
        <div v-if="hasDocument && resultText" class="export-dropdown">
          <button class="btn btn-outline" :disabled="isExporting" @click="showExport = !showExport">
            <svg v-if="!isExporting" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
              <polyline points="7 10 12 15 17 10" />
              <line x1="12" y1="15" x2="12" y2="3" />
            </svg>
            <span v-if="isExporting" class="btn-spinner"></span>
            {{ isExporting ? '转换中...' : '导出文档' }}
          </button>
          <div v-if="showExport" class="export-menu">
            <div v-if="canPreserveFormat" class="export-item" @click="exportDoc('preserve')">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="var(--accent)" stroke-width="2">
                <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
                <polyline points="14 2 14 8 20 8" />
              </svg>
              原格式导出<span class="export-badge">推荐</span>
            </div>
            <div v-if="canUsePdfToWordExport && quotaInfo.isAdmin" class="export-item" @click="exportDoc('pdf-beta')">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="var(--accent)" stroke-width="2">
                <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
                <polyline points="14 2 14 8 20 8" />
              </svg>
              PDF 转 Word 保留导出<span class="export-badge">Beta</span>
            </div>
            <div class="export-item" @click="exportDoc('standard')">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#888" stroke-width="2">
                <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
                <polyline points="14 2 14 8 20 8" />
              </svg>
              标准 Word 导出
            </div>
          </div>
        </div>
      </div>
    </header>

    <!-- Toolbar -->
    <div class="pro-toolbar">
      <div class="tabs-container">
        <div class="tab-indicator" ref="tabIndicator" />
        <div v-for="t in modeTabs" :key="t.value" class="tab-btn" :class="{ active: reduceMode === t.value }"
          :ref="el => tabRefs[t.value] = el as any" @click="setModeTab(t.value)">{{ t.label }}</div>
      </div>
      <div class="toolbar-spacer" />
      <button v-if="hasDocument" class="btn btn-outline btn-link" @click="showAdvanced = !showAdvanced">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <circle cx="12" cy="12" r="10" />
          <line x1="12" y1="8" x2="12" y2="12" />
          <line x1="12" y1="16" x2="12.01" y2="16" />
        </svg>
        高级选项
        <svg width="10" height="10" viewBox="0 0 10 10" fill="none" stroke="currentColor" stroke-width="1.5"
          :style="{ transform: showAdvanced ? 'rotate(180deg)' : 'rotate(0)' }">
          <polyline points="2 3 5 6 8 3" />
        </svg>
      </button>
      <button class="btn btn-outline btn-kb" @click="openKnowledgeBase">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20"/><path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z"/></svg>
        知识库
        <span v-if="canUseKnowledgeBase && kbPapers.length" class="kb-badge">{{ kbPapers.length }}</span>
      </button>
      <button class="btn btn-outline btn-upload" @click="triggerUpload">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
          <polyline points="17 8 12 3 7 8" />
          <line x1="12" y1="3" x2="12" y2="15" />
        </svg>
        上传文档
      </button>
      <input ref="fileInput" type="file" accept=".docx,.pdf" style="display:none" @change="handleFileUpload" />
      <button v-if="hasDocument && scanDone" class="btn btn-outline" style="border-color:transparent;box-shadow:none;" @click="rescan">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>
        重新扫描
      </button>
      <button class="btn btn-solid accent" @click="startReduce" :disabled="isStreaming || !scanDone">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <path d="M12 2v20M17 5H9.5a3.5 3.5 0 0 0 0 7h5a3.5 3.5 0 0 1 0 7H6" />
        </svg>
        {{ isStreaming ? '降重中...' : reportAnnotations.length > 0 ? '报告引导降重' : '开始降重' }}
      </button>
      <button v-if="lastRetrievedCount > 0" class="kb-hint kb-hint--button" @click="showKbHitDetails = true">命中 {{ lastRetrievedCount }} 个片段 / 来自 {{ lastRetrievedPaperCount }} 篇论文</button>
      <span v-else-if="kbSettings.autoRetrieve && kbPapers.length > 0 && !isStreaming" class="kb-hint kb-hint--idle">知识库就绪（{{ kbPapers.length }} 篇）</span>
      <span v-if="reportAnnotations.length > 0 && !isStreaming" class="report-guide-badge" title="已导入检测报告，AI将优先改写报告标注的高风险片段">AI将优先处理 {{ reportAnnotations.length }} 处报告标注</span>
    </div>

    <!-- Advanced Options -->
    <div v-if="showAdvanced" class="advanced-panel">
      <div class="advanced-grid">
        <div class="advanced-item advanced-item--grow">
          <label class="advanced-label">重复源（可选）</label>
          <input v-model="sourceRef" type="text" class="advanced-input" placeholder="粘贴被标红的重复原文或参考来源，提高检测精度" />
        </div>
        <div class="advanced-item advanced-item--grow">
          <label class="advanced-label">补充说明</label>
          <input v-model="notes" type="text" class="advanced-input" placeholder="如：第2段公式推导部分不能改动" />
        </div>
        <div class="advanced-item">
          <label class="advanced-label">导入查重报告</label>
          <button class="btn btn-outline btn-upload" @click="triggerReportUpload" :disabled="!hasDocument || reportLoading"
            style="font-size:12px;padding:6px 12px;">
            <svg v-if="reportLoading" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" class="spin-icon"><circle cx="12" cy="12" r="10" stroke-dasharray="31.4" stroke-dashoffset="10"/></svg>
            <svg v-else width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
              <polyline points="17 8 12 3 7 8" />
              <line x1="12" y1="3" x2="12" y2="15" />
            </svg>
            {{ reportLoading ? '解析中…' : reportUploaded ? '报告已导入 (' + reportAnnotationCount + '处标注)' : '上传查重报告' }}
          </button>
          <input ref="reportFileInput" type="file" accept=".docx,.pdf" style="display:none"
            @change="handleReportUpload" />
        </div>
      </div>
    </div>

    <!-- Empty -->
    <div v-if="!hasDocument" class="empty-state">
      <div class="empty-icon">
        <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="var(--accent)" stroke-width="1"
          opacity="0.3">
          <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
          <polyline points="14 2 14 8 20 8" />
        </svg>
      </div>
      <p class="empty-text">上传文档和检测报告开始检测重复风险</p>
      <p class="empty-sub">支持 .docx / .pdf，可通过高级选项补充重复源文本提高检测精度</p>
      <textarea v-model="pasteText" class="paste-area" placeholder="或直接粘贴论文正文..." rows="5" />
      <button v-if="pasteText.trim()" class="btn btn-solid accent" @click="usePastedText">使用粘贴文本</button>
    </div>

    <template v-else>
      <!-- Scanning -->
      <div v-if="isScanning" class="scanning-bar"><span class="scanning-dot" />正在检测重复风险...</div>

      <!-- Scan Results Card -->
      <div v-else-if="scanDone" class="risk-card">
        <div class="risk-card__grid">
          <div class="risk-stat">
            <span class="risk-stat__num" :style="{ color: simColor }">{{ simRate }}%</span>
            <span class="risk-stat__label">模拟查重率</span>
          </div>
          <div class="risk-stat">
            <span class="risk-stat__num">{{ repeatedCount }}</span>
            <span class="risk-stat__label">重复短语</span>
          </div>
          <div class="risk-stat">
            <span class="risk-stat__num">{{ fragmentCount }}</span>
            <span class="risk-stat__label">重叠片段</span>
          </div>
          <div class="risk-stat">
            <span class="risk-stat__num">{{ riskParaCount }}</span>
            <span class="risk-stat__label">风险段落</span>
          </div>
          <div class="risk-stat">
            <span class="risk-stat__num">{{ citationIssues }}</span>
            <span class="risk-stat__label">引用问题</span>
          </div>
        </div>
        <div v-if="scanIssues.length" class="risk-card__issues">
          <span class="risk-issue" v-for="(iss, i) in scanIssues" :key="i">{{ iss }}</span>
        </div>
        <div v-if="reportAnnotations.length > 0" class="risk-card__report-tags">
          <div class="report-tags-title">报告标注详情</div>
          <div class="report-tags-body">
            <span class="report-tag" v-for="(ann, i) in reportAnnotations.slice(0, 8)" :key="'ra-'+i"
              :class="'report-tag--' + (ann.riskLevel || 'medium')">
              {{ (ann.matchedSourceText || ann.text || '').length > 40 ? (ann.matchedSourceText || ann.text).slice(0, 40) + '…' : (ann.matchedSourceText || ann.text) }}
            </span>
            <span v-if="reportAnnotations.length > 8" class="report-tag report-tag--more">+{{ reportAnnotations.length - 8 }} 条</span>
          </div>
        </div>
        <div class="risk-card__disclaimer">
          <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/></svg>
          模拟查重仅供参考，请以学校或期刊指定的权威查重机构结果为准
        </div>
      </div>

      <!-- Matching Fragments Panel -->
      <div v-if="matchingFragments.length > 0" class="fragments-panel">
        <div class="fragments-header">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="var(--color-danger)" stroke-width="2"><path d="M16 8a6 6 0 0 1 6 6v7h-4v-7a2 2 0 0 0-2-2 2 2 0 0 0-2 2v7h-4v-7a6 6 0 0 1 6-6z"/><rect x="2" y="9" width="4" height="12"/><circle cx="4" cy="4" r="2"/></svg>
          <span>匹配片段（重叠率 {{ overlapRatio }}%）</span>
        </div>
        <div class="fragments-body">
          <div class="fragment-row" v-for="(f, i) in matchingFragments.slice(0, 5)" :key="'frag-'+i">
            <div class="fragment-col">
              <div class="fragment-label">重复源</div>
              <div class="fragment-text">{{ f.sourceExcerpt || '（未定位）' }}</div>
            </div>
            <div class="fragment-col">
              <div class="fragment-label">正文</div>
              <div class="fragment-text">{{ f.targetExcerpt }}</div>
            </div>
            <span class="fragment-len">{{ f.length }}字</span>
          </div>
        </div>
      </div>

      <!-- Workspace PC -->
      <div v-if="isDesktop" class="workspace split-50">
        <!-- LEFT: Source + SourceRef -->
        <div class="editor-pane left">
          <div class="pane-header"><span class="pane-title">原文参考</span><span class="pane-hint"><span class="legend-dot dup-frag"></span>匹配片段 <span class="legend-dot dup-phr"></span>重复短语 <span class="legend-dot dup-rep"></span>报告标注</span></div>
          <div class="editor-content">
            <template v-for="p in markedParagraphs" :key="'ref-' + p.index">
              <div v-if="p.isHeading" class="fp-heading">{{ p.originalText }}</div>
              <div v-else class="fp-body" v-html="p.markedHtml" />
            </template>
          </div>
          <div v-if="sourceRef" class="source-ref-panel">
            <div class="pane-header" style="border-top:1px solid var(--border-light);"><span
                class="pane-title">重复源</span></div>
            <div class="source-ref-body">{{ sourceRef }}</div>
          </div>
        </div>

        <!-- RIGHT: Result -->
        <div class="editor-pane right">
          <div class="pane-header">
            <span class="pane-title">降重结果</span>
            <span v-if="isStreaming" class="pane-badge live">生成中</span>
            <span v-else-if="resultText" class="pane-hint">完成</span>
          </div>
          <div class="editor-content">
            <div v-if="!resultText && !isStreaming" class="result-placeholder">
              <svg width="40" height="40" viewBox="0 0 24 24" fill="none" stroke="#ccc" stroke-width="1">
                <path d="M12 20h9" />
                <path d="M16.5 3.5a2.121 2.121 0 0 1 3 3L7 19l-4 1 1-4L16.5 3.5z" />
              </svg>
              <span>扫描完成后点击「开始降重」</span>
            </div>
            <div v-else-if="isStreaming" class="streaming-text">
              <template v-for="(line, i) in streamingLines" :key="'s-' + i">
                <p v-if="line" class="streaming-line">{{ line }}</p>
              </template>
              <span class="cursor-blink" />
            </div>
            <template v-else v-for="p in resultParagraphs" :key="'r-' + p.index">
              <div v-if="p.isHeading" class="fp-heading">{{ p.polishedText }}</div>
              <div v-else class="fp-body" contenteditable="true" @blur="onParaEdit($event, p.index)"
                @keydown="onParaKey" v-html="applyCitationChips(escapeHtml(p.polishedText))"></div>
            </template>
            <div v-if="citedReferences.length > 0" class="reference-section">
              <h4>参考文献</h4>
              <ol>
                <li v-for="(ref, i) in citedReferences" :key="i">{{ ref }}</li>
              </ol>
            </div>
          </div>
        </div>
      </div>

      <!-- Workspace Mobile: tab-switch stack -->
      <div v-if="!isDesktop" class="workspace workspace--mobile">
        <div class="mobile-pane-tabs">
          <button :class="{ active: mobilePane === 'original' }" @click="mobilePane = 'original'">原文参考</button>
          <button :class="{ active: mobilePane === 'result' }" @click="mobilePane = 'result'">降重结果</button>
        </div>

        <!-- Original pane -->
        <div class="mobile-pane" v-show="mobilePane === 'original'">
          <div class="editor-content">
            <template v-for="p in markedParagraphs" :key="'mref-'+p.index">
              <div v-if="p.isHeading" class="fp-heading">{{ p.originalText }}</div>
              <div v-else class="fp-body" v-html="p.markedHtml" />
            </template>
          </div>
          <div v-if="sourceRef" class="source-ref-panel" style="margin-top:12px;">
            <div style="font-weight:600;font-size:13px;margin-bottom:6px;">重复源</div>
            <div style="font-size:13px;color:var(--text-muted);line-height:1.6;">{{ sourceRef }}</div>
          </div>
        </div>

        <!-- Result pane -->
        <div class="mobile-pane" v-show="mobilePane === 'result'">
          <button v-if="resultText && !isStreaming" class="btn btn-outline" style="font-size:11px;padding:4px 10px;margin-bottom:8px;" @click="copyResult">
            复制结果
          </button>
          <div class="editor-content">
            <div v-if="!resultText && !isStreaming" class="result-placeholder">
              <svg width="40" height="40" viewBox="0 0 24 24" fill="none" stroke="#ccc" stroke-width="1"><path d="M12 20h9"/><path d="M16.5 3.5a2.121 2.121 0 0 1 3 3L7 19l-4 1 1-4L16.5 3.5z"/></svg>
              <span>扫描完成后点击「开始降重」</span>
            </div>
            <div v-else-if="isStreaming" class="streaming-text">
              <template v-for="(line, i) in streamingLines" :key="'sm-'+i"><p v-if="line" class="streaming-line">{{ line }}</p></template>
              <span class="cursor-blink" />
            </div>
            <template v-else v-for="p in resultParagraphs" :key="'rm-'+p.index">
              <div v-if="p.isHeading" class="fp-heading">{{ p.polishedText }}</div>
              <div v-else class="fp-body" contenteditable="true" @blur="onParaEdit($event, p.index)" @keydown="onParaKey" v-html="applyCitationChips(escapeHtml(p.polishedText))"></div>
            </template>
            <div v-if="citedReferences.length > 0" class="reference-section">
              <h4>参考文献</h4>
              <ol>
                <li v-for="(ref, i) in citedReferences" :key="'mref-'+i">{{ ref }}</li>
              </ol>
            </div>
          </div>
        </div>
      </div>
    </template>

    <p v-if="error" class="error-toast" @click="error = ''">{{ error }}</p>
    <p v-if="warnToast" class="warn-toast" @click="warnToast = ''">{{ warnToast }}</p>

    <PaperKbPanel
      :visible="showKbPanel"
      :papers="kbPapers"
      :is-loading="kbLoading"
      :importing-file="kbImporting"
      :settings="kbSettings"
      :access-allowed="canUseKnowledgeBase"
      :error="kbError"
      @close="showKbPanel = false"
      @configure-api-key="goConfigureApiKey"
      @import="handleKbImport"
      @delete="handleKbDelete"
      @clear-all="handleKbClearAll"
      @backup="handleKbBackup"
      @restore="handleKbRestore"
      @update:settings="kbSettings = $event"
    />
    <KbHitDetails
      :visible="showKbHitDetails"
      :chunks="lastRetrievedChunks"
      :optimized-text="resultText"
      @close="showKbHitDetails = false"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted, onUnmounted, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { useQuota, type QuotaInfo } from '@/composables/useQuota'
import { useModelToggle } from '@/composables/useModelToggle'
import { useResponsive } from '@/composables/useResponsive'
import { usePaperStore } from '@/composables/usePaperStore'
import { usePaperKbPanel } from '@/composables/usePaperKbPanel'
import { readJsonResponse } from '@/utils/httpResponse'
import { authFetch } from '@/utils/authFetch'
import { isDocxFile, isPdfFile } from '@/utils/documentFile'
import { preserveOriginalSpacing } from '@/utils/spacingGuard'
import { renderCitations } from '@/modules/paper-kb'
import type { CitedChunk } from '@/modules/paper-kb'
import PaperKbPanel from '@/components/PaperKbPanel.vue'
import KbHitDetails from '@/components/KbHitDetails.vue'

const router = useRouter()
const { fetchQuota, checkQuota } = useQuota()
const { isDesktop } = useResponsive()
const { currentModel, options, hasOptions, selectedLabel, selectModel } = useModelToggle()
const mobilePane = ref<'original' | 'result'>('original')
const paperStore = usePaperStore()

// aiModel replaced by currentModel from useModelToggle
const reduceMode = ref('medium')
const quotaInfo = ref<QuotaInfo>({ hasApiKey: false, isAdmin: false, knowledgeBaseEnabled: false, unlimited: false, dailyQuota: 10, quotaUsed: 0, quotaRemaining: 10 })
const canUseKnowledgeBase = computed(() => quotaInfo.value.knowledgeBaseEnabled || quotaInfo.value.hasApiKey || quotaInfo.value.isAdmin)
const notes = ref('')
const showAdvanced = ref(false)
const showExport = ref(false)
const isExporting = ref(false)
const isStreaming = ref(false)
const isScanning = ref(false)
const scanDone = ref(false)
const hasDocument = ref(false)
const error = ref('')
const warnToast = ref('')
let warnTimer: ReturnType<typeof setTimeout> | null = null
/** Toast 显示时长（毫秒） */
const TOAST_DURATION_MS = 5000
/** PDF 图片版成功提示自动清除时长（毫秒） */
const PDF_IMAGE_SUCCESS_MSG_DURATION_MS = 4000
function showWarn(msg: string) { warnToast.value = msg; if (warnTimer) clearTimeout(warnTimer); warnTimer = setTimeout(() => { warnToast.value = '' }, TOAST_DURATION_MS) }
const storedFile = ref<File | null>(null)
const canPreserveFormat = computed(() => isDocxFile(storedFile.value))
const canUsePdfToWordExport = computed(() => isPdfFile(storedFile.value))
const sourceText = ref('')
const sourceRef = ref('')
const resultText = ref('')
const paragraphData = ref<any[]>([])
const reducedParagraphs = ref<Map<number, string>>(new Map())
let abortController: AbortController | null = null

// === Knowledge Base ===
const {
  papers: kbPapers, isLoading: kbLoading, importingFile: kbImporting,
  showKbPanel, settings: kbSettings, error: kbError,
  lastRetrievedCount, lastRetrievedPaperCount, lastRetrievedChunks,
  handleImport: handleKbImport, handleDelete: handleKbDelete,
  handleClearAll: handleKbClearAll, handleBackup: handleKbBackup,
  handleRestore: handleKbRestore, retrieveRaw: kbRetrieveRaw,
} = usePaperKbPanel('plagiarism_reduce', { canUse: () => canUseKnowledgeBase.value })
const showKbHitDetails = ref(false)

function openKnowledgeBase() {
  if (!canUseKnowledgeBase.value) {
    showWarn('知识库需要配置自己的 AI API Key，或由管理员单独开放后才能使用。')
    return
  }
  showKbPanel.value = true
}

function goConfigureApiKey() {
  showKbPanel.value = false
  router.push('/profile')
}

// === Citation chips ===
function applyCitationChips(html: string): string {
  return html.replace(/\[(\d+)\]/g, (match, numStr) => {
    const index = parseInt(numStr, 10)
    if (index < 1 || index > 50) return match
    return `<span class="cite-chip" data-cite-index="${index}">${index}</span>`
  })
}

const citedReferences = computed(() => {
  if (!lastRetrievedChunks.value.length) return []
  const citedChunks: CitedChunk[] = lastRetrievedChunks.value.map((c, i) => ({
    index: i + 1,
    paperTitle: c.paperTitle,
    section: c.section,
    content: c.content,
  }))
  const allText = resultText.value
  const { references } = renderCitations(allText, citedChunks)
  return references
})

// Scan results
const repeatedPhrases = ref<[string, number][]>([])
const longSentences = ref<string[]>([])
const riskParagraphs = ref<string[]>([])
const scanRiskParagraphs = ref<string[]>([])   // 扫描检测到的
const reportRiskParagraphs = ref<string[]>([]) // 报告导入的
const similarityRate = ref(0)
const citationIssues = ref(0)
const scanIssues = ref<string[]>([])
const matchingFragments = ref<any[]>([])
const overlapRatio = ref(0)
const simulatedRate = ref(0)

const modeTabs = [
  { value: 'light', label: '轻度降重' },
  { value: 'medium', label: '中度降重' },
  { value: 'deep', label: '深度降重' },
]
const tabRefs: Record<string, HTMLElement | null> = {}
const tabIndicator = ref<HTMLElement | null>(null)
function setModeTab(v: string) { reduceMode.value = v; nextTick(() => moveIndicator(v)) }
function moveIndicator(v?: string) {
  const val = v || reduceMode.value
  const indicator = tabIndicator.value; const tab = tabRefs[val]
  if (!indicator || !tab) return
  const p = indicator.parentElement; if (!p) return
  const pr = p.getBoundingClientRect(); const tr = tab.getBoundingClientRect()
  indicator.style.width = `${tr.width}px`; indicator.style.transition = 'all 0.4s var(--spring-bounce)'
  indicator.style.transform = `translateX(${tr.left - pr.left}px)`
}
onMounted(async () => { nextTick(() => moveIndicator()); const q = await fetchQuota(); if (q) quotaInfo.value = q })
onMounted(async () => {
  if (hasDocument.value) return
  const doc = paperStore.load()
  if (!doc?.sourceText) return
  sourceText.value = doc.sourceText
  paragraphData.value = doc.paragraphs?.length ? doc.paragraphs : fallbackParagraphs(doc.sourceText)
  reducedParagraphs.value = new Map()
  for (const p of paragraphData.value) reducedParagraphs.value.set(p.index, p.text || '')
  hasDocument.value = true
  storedFile.value = await paperStore.loadFile()
  runScan(doc.sourceText)
})
let _resizeObs3: ResizeObserver | null = null
onMounted(() => {
  _resizeObs3 = new ResizeObserver(() => moveIndicator())
  const parent = tabIndicator.value?.parentElement
  if (parent) _resizeObs3.observe(parent)
})
onUnmounted(() => _resizeObs3?.disconnect())
watch(reduceMode, () => nextTick(() => moveIndicator()))

const simRate = computed(() => Math.round(simulatedRate.value * 10) / 10)
const simColor = computed(() => simRate.value >= 50 ? '#EF4444' : simRate.value >= 25 ? '#F59E0B' : '#22C55E')
const repeatedCount = computed(() => repeatedPhrases.value.length)
const riskParaCount = computed(() => riskParagraphs.value.length)
const fragmentCount = computed(() => matchingFragments.value.length)

const streamingLines = computed(() => resultText.value ? resultText.value.split('\n').filter(l => l !== undefined) : [''])

function escapeHtml(s: string) { return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;') }

const markedParagraphs = computed(() => {
  if (!paragraphData.value.length) return []
  return paragraphData.value.map((p: any) => {
    const isHeading = !!(p.styleId && /heading/i.test(p.styleId))
    const text = p.text || ''
    let html = escapeHtml(text)

    // 三级匹配：精确 → 半段 → 前缀
    for (const frag of matchingFragments.value) {
      const target = frag.targetExcerpt || ''
      if (!target || target.length < 4) continue

      // Level 1: 精确匹配
      const escaped = escapeHtml(target)
      if (html.includes(escaped)) {
        html = html.split(escaped).join(`<mark class="dup-mark">${escaped}</mark>`)
        continue
      }

      // Level 2: 取前50%做半段匹配
      const half = Math.floor(target.length / 2)
      const prefix = target.substring(0, Math.max(half, 10))
      const escapedHalf = escapeHtml(prefix)
      if (html.includes(escapedHalf)) {
        html = html.split(escapedHalf).join(`<mark class="dup-mark">${escapedHalf}</mark>`)
        continue
      }

      // Level 3: 取前30字符做前缀匹配
      const short = target.substring(0, Math.min(30, target.length))
      const escapedShort = escapeHtml(short)
      if (short.length >= 10 && html.includes(escapedShort)) {
        html = html.split(escapedShort).join(`<mark class="dup-mark">${escapedShort}</mark>`)
      }
    }

    // 各类型独立匹配，互不阻塞
    if (html === escapeHtml(text)) {
      // 报告标注段落（紫色）
      for (const rp of reportRiskParagraphs.value) {
        const short = rp.length > 30 ? rp.slice(0, 30) : rp
        const escapedRP = escapeHtml(short)
        if (html.includes(escapedRP)) {
          html = html.split(escapedRP).join(`<mark class="dup-mark dup-report">${escapedRP}</mark>`)
        }
      }
      // 扫描风险段落（红色）— 独立运行，不受紫色影响
      let htmlAfterReport = html
      for (const rp of scanRiskParagraphs.value) {
        const short = rp.length > 30 ? rp.slice(0, 30) : rp
        const escapedRP = escapeHtml(short)
        if (htmlAfterReport.includes(escapedRP)) {
          htmlAfterReport = htmlAfterReport.split(escapedRP).join(`<mark class="dup-mark">${escapedRP}</mark>`)
        }
      }
      // 重复短语（橙色）— 独立运行
      let htmlAfterAll = htmlAfterReport
      for (const [phrase] of repeatedPhrases.value) {
        const escapedPh = escapeHtml(phrase)
        if (phrase.length >= 4 && htmlAfterAll.includes(escapedPh)) {
          htmlAfterAll = htmlAfterAll.split(escapedPh).join(`<mark class="dup-mark dup-phrase">${escapedPh}</mark>`)
        }
      }
      html = htmlAfterAll
    }

    return { index: p.index, originalText: text, isHeading, markedHtml: html }
  })
})

const resultParagraphs = computed(() => {
  if (!resultText.value) return []
  if (!paragraphData.value.length) return resultText.value.split(/\n{2,}/).filter(p => p.trim()).map((t, i) => ({ index: i, originalText: '', polishedText: t.trim(), isHeading: false }))
  return paragraphData.value.map((p: any) => ({ index: p.index, originalText: p.text || '', polishedText: reducedParagraphs.value.get(p.index) ?? p.text ?? '', isHeading: !!(p.styleId && /heading/i.test(p.styleId)) }))
})

// Upload + Scan
const pasteText = ref('')
const fileInput = ref<HTMLInputElement | null>(null)
function triggerUpload() { fileInput.value?.click() }

// Report upload
const reportFileInput = ref<HTMLInputElement | null>(null)
const reportUploaded = ref(false)
const reportLoading = ref(false)
const reportAnnotations = ref<any[]>([])
const reportAnnotationCount = computed(() => reportAnnotations.value.length)
function triggerReportUpload() { if (hasDocument.value) reportFileInput.value?.click() }

async function handleReportUpload(e: Event) {
  const reportFile = (e.target as HTMLInputElement).files?.[0]; if (!reportFile) return
  const fd = new FormData(); fd.append('file', reportFile)
  if (sourceText.value) fd.append('sourceText', sourceText.value)
  reportLoading.value = true
  try {
    const res = await authFetch('/api/paper/report-analyze', { method: 'POST', body: fd })
    const data = await readJsonResponse(res, '报告解析失败'); if (!res.ok) throw new Error(data.error || '解析失败')
    reportAnnotations.value = data.annotations || []
    if (reportAnnotations.value.length === 0) {
      const isPdf = reportFile.name.toLowerCase().endsWith('.pdf')
      error.value = isPdf
        ? '报告解析无标注：该PDF可能为图片版，请从检测平台导出Word格式(.docx)后重新上传'
        : '报告解析无标注：未检测到高亮/批注标记，请确认报告格式'
      return
    }
    reportUploaded.value = true
    // 兜底提示
    if (data.fallbackUsed) {
      error.value = '报告为图片版PDF，已通过云端AI解析完成识别'
      setTimeout(() => { if (error.value === '报告为图片版PDF，已通过云端AI解析完成识别') error.value = '' }, PDF_IMAGE_SUCCESS_MSG_DURATION_MS)
    }
    // Merge report annotations
    if (reportAnnotations.value.length > 0) {
      const newReportParas: string[] = []
      for (const ann of reportAnnotations.value) {
        const matchedText = ann.matchedSourceText || ann.text
        if (matchedText && matchedText.length > 10) {
          newReportParas.push(ann.riskLevel === 'high' ? matchedText : (matchedText.length > 50 ? matchedText.substring(0, 50) + '…' : matchedText))
        }
      }
      reportRiskParagraphs.value = newReportParas
      riskParagraphs.value = [...scanRiskParagraphs.value, ...newReportParas]
      scanIssues.value = [newReportParas.length + ' 处报告标注已合并，左侧原文中以紫色下划线标出', ...scanIssues.value]
    }
  } catch (e: any) { error.value = '报告解析失败: ' + (e.message || String(e)) }
  finally { reportLoading.value = false; (e.target as HTMLInputElement).value = '' }
}

function fallbackParagraphs(text: string): any[] {
  if (!text?.trim()) return []
  return text.split(/\n{2,}/).filter(p => p.trim().length > 3).map((t, i) => ({ index: i, text: t.trim() }))
}

function usePastedText() {
  const text = pasteText.value.trim()
  if (!text) return
  sourceText.value = text
  paragraphData.value = fallbackParagraphs(text)
  reducedParagraphs.value = new Map()
  for (const p of paragraphData.value) reducedParagraphs.value.set(p.index, p.text)
  hasDocument.value = true; resultText.value = ''; scanDone.value = false
  runScan(text)
}

async function handleFileUpload(e: Event) {
  const uploadedFile = (e.target as HTMLInputElement).files?.[0]; if (!uploadedFile) return
  if (uploadedFile.name.toLowerCase().endsWith('.pdf')) showWarn('PDF格式可能导致导出效果不佳，建议上传Word文档(.docx)以保证最佳导出效果')
  storedFile.value = uploadedFile
    ; (e.target as HTMLInputElement).value = ''
  scanDone.value = false; resultText.value = ''
  reportUploaded.value = false; reportAnnotations.value = []; reportRiskParagraphs.value = []
  const fd = new FormData(); fd.append('file', uploadedFile)
  try {
    const res = await authFetch('/api/paper/upload', { method: 'POST', body: fd })
    const data = await readJsonResponse(res, '文件上传失败'); if (!res.ok) throw new Error(data.error || '上传失败')
    if (!data.fullText || data.fullText.trim().length < 10) {
      error.value = '文档解析失败：未提取到有效文本内容。请检查文档是否为扫描版图片，或尝试另存为标准 .docx 格式。'
      return
    }
    sourceText.value = data.fullText
    paragraphData.value = (data.paragraphs?.length ? data.paragraphs : fallbackParagraphs(data.fullText))
    reducedParagraphs.value = new Map(); resultText.value = ''; hasDocument.value = true; scanDone.value = false
    paperStore.save({ sourceText: data.fullText, paragraphs: paragraphData.value, fileName: uploadedFile.name, timestamp: Date.now() }, uploadedFile)
    for (const p of paragraphData.value) reducedParagraphs.value.set(p.index, p.text || '')
    await runScan(data.fullText)
  } catch (e: any) { error.value = '解析失败: ' + (e.message || String(e)) }
}

async function runScan(text: string) {
  isScanning.value = true
  try {
    const res = await authFetch('/api/plagiarism-reduce/scan', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ text, sourceText: sourceRef.value }) })
    const data = await res.json()
    const rep = data.repetition || {}
    repeatedPhrases.value = (rep.repeatedPhrases || []).map((e: any) => [String(e.key || e[0] || ''), Number(e.value || e[1] || 1)])
    longSentences.value = rep.longSentences || []
    scanRiskParagraphs.value = rep.riskParagraphs || []
    riskParagraphs.value = [...scanRiskParagraphs.value, ...reportRiskParagraphs.value]
    similarityRate.value = data.similarity?.similarity || 0
    simulatedRate.value = data.simulatedRate || 0
    matchingFragments.value = data.overlap?.topFragments || []
    overlapRatio.value = data.overlap?.overlapRatio || 0
    const cit = data.citations || {}
    citationIssues.value = (cit.issues || []).length
    scanIssues.value = (rep.riskParagraphs || []).slice(0, 3).map((s: string) => '风险段落: ' + (s.length > 50 ? s.slice(0, 50) + '...' : s))
    if (simulatedRate.value > 0) scanIssues.value.unshift(`模拟查重率 ${simRate.value}%`)
    if (overlapRatio.value > 0) scanIssues.value.unshift(`与重复源重叠率 ${overlapRatio.value}%`)
    if (citationIssues.value > 0) scanIssues.value.push(`${citationIssues.value} 处引用格式问题`)
    scanDone.value = true
  } catch (e: any) { error.value = '扫描失败: ' + (e.message || String(e)) } finally { isScanning.value = false }
}

function rescan() {
  scanDone.value = false
  resultText.value = ''
  reducedParagraphs.value = new Map()
  if (paragraphData.value.length) {
    for (const p of paragraphData.value) reducedParagraphs.value.set(p.index, p.text)
  }
  runScan(sourceText.value)  // 保留 reportRiskParagraphs，重新扫描后自动合并
}

// SSE Reduce
async function startReduce() {
  if (!hasDocument.value || isStreaming.value || !scanDone.value) return
  // 客户端额度预检
  const needed = currentModel.value.includes('pro') ? 2 : 1
  const qr = checkQuota(needed, `今日免费次数不足（需 ${needed} 次），请配置 API Key 后继续使用`)
  if (!qr.ok) { error.value = qr.msg || '配额不足'; return }
  if (!isDesktop.value) mobilePane.value = 'result'

  const taggedText = paragraphData.value.length > 0 ? paragraphData.value.map((p: any) => `[P${p.index}] ${p.text || ''}`).join('\n\n') : `[P0] ${sourceText.value}`
  const annotationPayload = reportAnnotations.value.length > 0
    ? reportAnnotations.value.map((a: any) => ({ text: a.matchedSourceText || a.text, riskLevel: a.riskLevel || 'medium' }))
    : []
  const contextChunks = await kbRetrieveRaw(taggedText, {
    sourceText: sourceRef.value,
    notes: notes.value,
    annotations: annotationPayload.map((a: any) => `${a.text} ${a.riskLevel || ''}`),
  })
  resultText.value = ''; error.value = ''; isStreaming.value = true; abortController = new AbortController()
  try {
    const res = await authFetch('/api/plagiarism-reduce/run', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ text: taggedText, sourceText: sourceRef.value, mode: reduceMode.value, model: currentModel.value, annotations: annotationPayload, contextChunks }), signal: abortController.signal })
    if (!res.ok) {
      if (res.status === 429) { const d = await res.json().catch(() => ({ error: '配额已用完' })); throw new Error(d.error || '今日免费次数已用完') }
      if (res.status >= 500) throw new Error('服务异常，请稍后重试')
      throw new Error(`请求失败: ${res.status}`)
    }
    const reader = res.body?.getReader(); if (!reader) { isStreaming.value = false; return }
    const decoder = new TextDecoder(); let buffer = '', raw = ''
    while (true) {
      const { done, value } = await reader.read(); if (done) break
      buffer += decoder.decode(value, { stream: true }); const lines = buffer.split('\n'); buffer = lines.pop() || ''
      for (const line of lines) {
        if (line.startsWith('event:') || line.startsWith('id:') || line.startsWith('retry:')) continue
        if (line.startsWith('data:')) { const d = line.length > 5 && line[5] === ' ' ? line.slice(6) : line.slice(5); const dt = d.trim(); if (dt === 'finish' || dt === '[DONE]') break; if (dt.startsWith('{') || dt.startsWith('"')) continue; raw += d; resultText.value = raw }
      }
    }
    if (raw && paragraphData.value.length > 0) { const parsed = parseMarked(raw); reducedParagraphs.value = parsed; resultText.value = serializeMarkedParagraphs(parsed) }
    fetchQuota(true).then(q => { if (q) quotaInfo.value = q })
  } catch (e: unknown) {
    if (e instanceof DOMException && e.name === 'AbortError') return
    if (e instanceof TypeError && e.message.includes('NetworkError')) {
      error.value = '网络连接中断，请检查网络后重试'
    } else {
      error.value = '降重请求失败: ' + (e instanceof Error ? e.message : String(e))
    }
  } finally { isStreaming.value = false }
}

function parseMarked(resp: string): Map<number, string> {
  const r = new Map<number, string>(); const re = /\[P(\d+)\]\s*([\s\S]*?)(?=\[P\d+\]|$)/g; let m
  while ((m = re.exec(resp)) !== null) { const index = parseInt(m[1]); r.set(index, preserveParagraphSpacing(index, m[2].trim())) }
  if (r.size === 0 && resp.trim()) { const parts = resp.split(/\n{2,}/).filter(p => p.trim()); const paras = paragraphData.value; if (paras.length > 0 && parts.length > 0) { for (let i = 0; i < Math.min(paras.length, parts.length); i++) r.set(paras[i].index, preserveParagraphSpacing(paras[i].index, parts[i].trim())) } else r.set(0, resp.trim()) }
  return r
}

function preserveParagraphSpacing(index: number, text: string): string {
  const original = paragraphData.value.find((p: any) => p.index === index)?.text ?? ''
  return preserveOriginalSpacing(original, text)
}

function serializeMarkedParagraphs(paragraphs: Map<number, string>): string {
  return paragraphData.value.map((p: any) => `[P${p.index}] ${paragraphs.get(p.index) ?? p.text ?? ''}`).join('\n\n')
}

function copyResult() { if (resultText.value) { navigator.clipboard.writeText(resultText.value).catch(() => {}) } }
function onParaEdit(e: FocusEvent, idx: number) { const t = (e.target as HTMLElement).innerText?.trim() || ''; if (t) reducedParagraphs.value.set(idx, t) }
function onParaKey(e: KeyboardEvent) { if (e.key === 'Tab') { e.preventDefault(); const n = (e.target as HTMLElement).nextElementSibling as HTMLElement | null; if (n?.contentEditable === 'true') n.focus() } }

async function exportDoc(mode: string) {
  showExport.value = false
  if (mode === 'preserve' && !canPreserveFormat.value) {
    error.value = 'PDF 暂不支持原格式导出，已改用标准 Word 导出。原格式和图片保留目前仅支持 DOCX。'
    mode = 'standard'
  }
  const allParas = paragraphData.value.map((p: any) => ({ index: p.index, text: reducedParagraphs.value.get(p.index) ?? p.text ?? '', originalText: p.text ?? '' }))
  const changedParas = allParas.filter((p: any) => (reducedParagraphs.value.get(p.index) ?? '').trim())
  if (mode === 'pdf-beta' && storedFile.value && canUsePdfToWordExport.value) {
    isExporting.value = true
    const fd = new FormData()
    fd.append('file', storedFile.value)
    fd.append('mappings', JSON.stringify({ fileName: 'plagiarism-reduced', paragraphs: allParas }))
    try {
      const res = await authFetch('/api/paper-export/pdf-to-docx-preserve-format', { method: 'POST', body: fd })
      if (res.ok) { const blob = await res.blob(); const url = URL.createObjectURL(blob); const a = document.createElement('a'); a.href = url; a.download = 'plagiarism-reduced.docx'; a.click(); URL.revokeObjectURL(url); return }
      const errData = await res.json().catch(() => null)
      error.value = (errData?.detail || errData?.error || 'PDF 转 Word 保留导出失败') + '。是否改用标准导出？'
      if (!confirm(error.value)) { error.value = ''; return }
    } catch (e: any) {
      error.value = 'PDF 转 Word 导出失败: ' + (e.message || String(e)) + '。是否改用标准导出？'
      if (!confirm(error.value)) { error.value = ''; return }
    } finally {
      isExporting.value = false
    }
  }
  if (mode === 'preserve' && storedFile.value && canPreserveFormat.value) {
    const fd = new FormData()
    fd.append('file', storedFile.value)
    fd.append('mappings', JSON.stringify({ fileName: 'plagiarism-reduced', paragraphs: changedParas }))
    try {
      const res = await authFetch('/api/paper-export/preserve-format', { method: 'POST', body: fd })
      if (res.ok) { const blob = await res.blob(); const url = URL.createObjectURL(blob); const a = document.createElement('a'); a.href = url; a.download = 'plagiarism-reduced.docx'; a.click(); URL.revokeObjectURL(url); return }
      const errData = await res.json().catch(() => null)
      error.value = (errData?.detail || errData?.error || '格式保留导出失败') + '。是否改用标准导出？'
      if (!confirm(error.value)) { error.value = ''; return }
    } catch (e: any) {
      error.value = '导出失败: ' + (e.message || String(e)) + '。是否改用标准导出？'
      if (!confirm(error.value)) { error.value = ''; return }
    }
  }
  try {
    const res = await authFetch('/api/paper-export/standard', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ fileName: 'plagiarism-reduced', paragraphs: allParas }) })
    if (res.ok) { const blob = await res.blob(); const url = URL.createObjectURL(blob); const a = document.createElement('a'); a.href = url; a.download = 'plagiarism-reduced.docx'; a.click(); URL.revokeObjectURL(url); return }
    error.value = '标准导出也失败了，请重试'
  } catch (e: any) { error.value = '导出失败: ' + (e.message || String(e)) }
}
</script>

<style scoped>
.app-shell {
  --accent: #D9750A;
  --accent-hover: #C26300;
  --color-success: #22C55E;
  --color-danger: #EF4444;
  --color-warning: #F59E0B;
  --border-light: rgba(0, 0, 0, 0.06);
  --border-medium: rgba(0, 0, 0, 0.12);
  --shadow-sm: 0 2px 8px rgba(0, 0, 0, 0.04);
  --shadow-md: 0 12px 32px rgba(0, 0, 0, 0.08);
  --spring: cubic-bezier(0.25, 1, 0.4, 1);
  --spring-bounce: cubic-bezier(0.34, 1.56, 0.64, 1);
  --bg-canvas: #F3EFE8;
  --bg-paper: #FDFCFB;
  --bg-surface: #F5F4F1;
  --text-main: #141413;
  --text-muted: #555;
  --text-light: #999;
  max-width: 1280px;
  height: calc(100vh - 48px);
  margin: 24px auto;
  background: var(--bg-paper);
  border-radius: 20px;
  box-shadow: 0 24px 48px rgba(0, 0, 0, 0.08), 0 0 0 1px var(--border-light);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  font-family: 'Inter', 'PingFang SC', sans-serif;
}

.app-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 24px;
  border-bottom: 1px solid var(--border-light);
  background: var(--bg-paper);
  z-index: 20;
  flex-shrink: 0;
}

.app-brand {
  display: flex;
  align-items: center;
  gap: 12px;
}

.brand-name {
  font-family: Georgia, serif;
  font-size: 18px;
  font-weight: 600;
}

.brand-tag {
  font-size: 10px;
  font-weight: 600;
  color: var(--accent);
  background: rgba(217, 117, 10, 0.08);
  padding: 4px 10px;
  border-radius: 100px;
  letter-spacing: 1px;
}

.btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 8px 16px;
  border-radius: 10px;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s var(--spring);
  border: 1px solid transparent;
  font-family: inherit;
}

.btn:active {
  transform: scale(0.96);
}

.btn-icon {
  width: 36px;
  height: 36px;
  padding: 0;
  border-radius: 10px;
  border-color: var(--border-medium);
  background: var(--bg-paper);
  color: var(--text-muted);
}

.btn-icon:hover {
  background: var(--bg-surface);
  color: var(--text-main);
}

.btn-outline {
  background: var(--bg-paper);
  border-color: var(--border-medium);
  color: var(--text-main);
}

.btn-outline:hover {
  background: var(--bg-surface);
}

.btn-solid {
  border: 1px solid var(--border-medium);
  background: var(--bg-paper);
  color: #141413;
  box-shadow: var(--shadow-sm);
}

.btn-solid.accent {
  background: var(--accent);
  color: #fff;
  border-color: var(--accent);
}

.btn-solid:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.btn-upload {
  font-weight: 600;
  color: var(--accent);
  border-color: rgba(217, 117, 10, 0.25);
}

.btn-upload:hover {
  background: rgba(217, 117, 10, 0.06);
  border-color: var(--accent);
  color: var(--accent-hover);
}

.btn-switch {
  font-size: 11px;
  padding: 5px 12px;
  color: var(--text-muted);
  margin-left: 8px;
}

.btn-switch:hover {
  color: var(--accent);
  border-color: var(--accent);
}

.btn-kb { position: relative; font-weight: 600; color: var(--accent); border-color: rgba(217,117,10,0.25); }
.btn-kb:hover { background: rgba(217,117,10,0.06); border-color: var(--accent); color: var(--accent-hover); }
.kb-badge { font-size: 10px; min-width: 16px; height: 16px; display: inline-flex; align-items: center; justify-content: center; background: var(--accent); color: #fff; border-radius: 100px; padding: 0 4px; margin-left: 4px; }
.kb-hint { font-size: 11px; color: var(--accent); white-space: nowrap; }
.kb-hint--button { border: 0; background: transparent; padding: 0; cursor: pointer; font-family: inherit; }
.kb-hint--button:hover { text-decoration: underline; }
.kb-hint--idle { color: var(--text-light); }

.export-dropdown {
  position: relative;
}

.export-menu {
  position: absolute;
  top: calc(100% + 6px);
  right: 0;
  background: var(--bg-paper);
  border: 1px solid var(--border-medium);
  border-radius: 12px;
  box-shadow: var(--shadow-md);
  min-width: 200px;
  z-index: 100;
  overflow: hidden;
}

.export-item {
  padding: 10px 14px;
  font-size: 12px;
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 8px;
  border-bottom: 1px solid var(--border-light);
  transition: background 0.15s;
}

.export-item:last-child {
  border-bottom: none;
}

.export-item:hover {
  background: var(--bg-surface);
}

.export-badge {
  font-size: 10px;
  padding: 2px 8px;
  border-radius: 100px;
  background: rgba(217, 117, 10, 0.08);
  color: var(--accent);
  font-weight: 600;
  margin-left: auto;
}

.pro-toolbar {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 12px 24px;
  background: var(--bg-surface);
  border-bottom: 1px solid var(--border-light);
  z-index: 10;
  flex-shrink: 0;
}

.toolbar-spacer {
  flex: 1;
}

.spin-icon {
  animation: spin 1s linear infinite;
}

@keyframes spin {
  from { transform: rotate(0deg) }
  to { transform: rotate(360deg) }
}

.btn-spinner {
  width: 14px;
  height: 14px;
  border: 2px solid var(--border-medium);
  border-top-color: var(--accent);
  border-radius: 50%;
  animation: spin 0.6s linear infinite;
}

.report-guide-badge {
  font-size: 11px;
  color: rgba(147, 51, 234, 0.85);
  background: rgba(147, 51, 234, 0.06);
  padding: 4px 12px;
  border-radius: 100px;
  white-space: nowrap;
  border: 1px solid rgba(147, 51, 234, 0.15);
}

.tabs-container {
  position: relative;
  display: flex;
  align-items: center;
  background: rgba(0, 0, 0, 0.04);
  padding: 4px;
  border-radius: 10px;
}

.tab-btn {
  position: relative;
  z-index: 2;
  padding: 6px 16px;
  font-size: 13px;
  font-weight: 500;
  color: var(--text-muted);
  cursor: pointer;
  user-select: none;
  transition: color 0.3s;
}

.tab-btn.active {
  color: var(--text-main);
  font-weight: 600;
}

.tab-indicator {
  position: absolute;
  top: 4px;
  bottom: 4px;
  left: 0;
  background: #fff;
  border-radius: 6px;
  z-index: 1;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.06);
  transition: all 0.4s var(--spring-bounce);
}

.btn-link {
  border-color: transparent;
  color: var(--text-muted);
  font-weight: 500;
  gap: 5px;
}

.btn-link:hover {
  color: var(--accent);
  background: transparent;
}

.advanced-panel {
  padding: 14px 24px;
  background: var(--bg-surface);
  border-bottom: 1px solid var(--border-light);
  flex-shrink: 0;
  animation: slideDown 0.25s var(--spring);
}

@keyframes slideDown {
  from {
    opacity: 0;
    transform: translateY(-8px)
  }

  to {
    opacity: 1;
    transform: translateY(0)
  }
}

.advanced-grid {
  display: flex;
  gap: 14px;
  align-items: flex-end;
}

.advanced-item {
  display: flex;
  flex-direction: column;
  gap: 5px;
}

.advanced-item--grow {
  flex: 1;
}

.advanced-label {
  font-size: 11px;
  font-weight: 600;
  color: var(--text-light);
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.advanced-input {
  padding: 7px 10px;
  border-radius: 8px;
  border: 1px solid var(--border-medium);
  background: #fff;
  font-size: 13px;
  font-family: inherit;
  color: var(--text-main);
  outline: none;
}

.advanced-input::placeholder {
  color: var(--text-light);
}

.advanced-input:focus {
  border-color: var(--accent);
}

.empty-state {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 16px;
  background: var(--bg-canvas);
}

.empty-icon {
  width: 80px;
  height: 80px;
  border-radius: 20px;
  background: rgba(217, 117, 10, 0.04);
  display: flex;
  align-items: center;
  justify-content: center;
}

.empty-text {
  font-family: Georgia, serif;
  font-size: 18px;
  color: var(--text-muted);
}

.empty-sub {
  font-size: 13px;
  color: var(--text-light);
}

.paste-area {
  width: 100%;
  max-width: 480px;
  background: var(--bg-surface);
  border: 1px solid var(--border-light);
  border-radius: 10px;
  padding: 12px 14px;
  font-family: var(--font-sans);
  font-size: 13px;
  color: var(--text-main);
  resize: vertical;
  outline: none;
  line-height: 1.7;
}

.paste-area:focus {
  border-color: var(--accent);
}

.paste-area::placeholder {
  color: var(--text-light);
}

.scanning-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  justify-content: center;
  padding: 14px 24px;
  font-size: 13px;
  color: var(--accent);
  background: var(--bg-surface);
  border-bottom: 1px solid var(--border-light);
  flex-shrink: 0;
}

.scanning-dot {
  width: 8px;
  height: 8px;
  background: var(--accent);
  border-radius: 50%;
  animation: pulse 1s infinite;
}

@keyframes pulse {

  0%,
  100% {
    opacity: 1
  }

  50% {
    opacity: 0.3
  }
}

.risk-card {
  padding: 18px 24px;
  border-bottom: 1px solid var(--border-light);
  flex-shrink: 0;
  background: rgba(217, 117, 10, 0.02);
}

.risk-card__grid {
  display: flex;
  gap: 28px;
  margin-bottom: 10px;
}

.risk-stat {
  display: flex;
  flex-direction: column;
}

.risk-stat__num {
  font-size: 22px;
  font-weight: 700;
  font-family: Georgia, serif;
}

.risk-stat__label {
  font-size: 11px;
  color: var(--text-light);
  margin-top: 2px;
}

.risk-card__issues {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 10px;
}

.risk-card__disclaimer {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 11px;
  color: var(--text-light);
  padding-top: 10px;
  border-top: 1px solid var(--border-light);
}

.risk-issue {
  font-size: 11px;
  background: rgba(217, 117, 10, 0.06);
  color: var(--text-muted);
  padding: 3px 10px;
  border-radius: 100px;
}

.risk-card__report-tags {
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px solid var(--border-light);
}

.report-tags-title {
  font-size: 11px;
  font-weight: 600;
  color: rgba(147, 51, 234, 0.8);
  margin-bottom: 8px;
}

.report-tags-body {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.report-tag {
  font-size: 11px;
  padding: 4px 10px;
  border-radius: 6px;
  line-height: 1.5;
  max-width: 320px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.report-tag--high {
  background: rgba(239, 68, 68, 0.08);
  color: var(--color-danger);
  border: 1px solid rgba(239, 68, 68, 0.2);
}

.report-tag--medium {
  background: rgba(147, 51, 234, 0.06);
  color: rgba(147, 51, 234, 0.9);
  border: 1px solid rgba(147, 51, 234, 0.15);
}

.report-tag--low {
  background: rgba(34, 197, 94, 0.06);
  color: var(--color-success);
  border: 1px solid rgba(34, 197, 94, 0.15);
}

.report-tag--more {
  background: var(--bg-surface);
  color: var(--text-light);
  border: 1px solid var(--border-light);
}

.workspace {
  flex: 1;
  min-height: 0;
  display: grid;
  background: var(--bg-canvas);
  overflow: hidden;
}

.workspace.split-50 {
  grid-template-columns: 1fr 1fr;
}

.editor-pane {
  display: flex;
  flex-direction: column;
  background: var(--bg-paper);
  min-height: 0;
  min-width: 0;
  overflow: hidden;
}

.editor-pane.left {
  border-right: 1px solid var(--border-light);
}

.editor-pane.right {
  border-left: 1px solid var(--border-light);
}

.pane-header {
  padding: 14px 24px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  border-bottom: 1px solid var(--border-light);
  flex-shrink: 0;
}

.pane-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--text-muted);
  text-transform: uppercase;
  letter-spacing: 1px;
}

.pane-hint {
  font-size: 11px;
  color: var(--text-light);
  display: flex;
  align-items: center;
  gap: 4px;
}

.legend-dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 2px;
}

.legend-dot.dup-frag {
  background: rgba(239, 68, 68, 0.35);
}

.legend-dot.dup-phr {
  background: rgba(245, 158, 11, 0.35);
}

.legend-dot.dup-rep {
  background: rgba(147, 51, 234, 0.45);
}

.pane-badge {
  font-size: 11px;
  font-weight: 600;
  display: flex;
  align-items: center;
  gap: 5px;
}

.pane-badge.live {
  color: var(--accent);
}

.pane-badge.live::before {
  content: '';
  width: 6px;
  height: 6px;
  background: var(--accent);
  border-radius: 50%;
  animation: pulse 1s infinite;
}

.editor-content {
  flex: 1;
  min-height: 0;
  min-width: 0;
  padding: 24px 32px;
  overflow-y: auto;
  overflow-x: hidden;
}

.editor-content::-webkit-scrollbar {
  width: 5px;
}

.editor-content::-webkit-scrollbar-thumb {
  background: var(--border-light);
  border-radius: 3px;
}

.fp-heading {
  margin: 16px 0 6px;
  line-height: 1.3;
  color: var(--text-main);
  user-select: none;
  font-weight: 700;
  overflow-wrap: break-word;
  word-break: break-word;
}

.fp-body {
  margin: 0 0 6px;
  line-height: 1.9;
  color: var(--text-main);
  outline: none;
  border-radius: 4px;
  padding: 4px 8px;
  margin-left: -8px;
  margin-right: -8px;
  transition: background 0.15s;
  min-height: 1.5em;
  overflow-wrap: break-word;
  word-break: break-word;
}

.fp-body[contenteditable="true"]:hover {
  background: rgba(217, 117, 10, 0.03);
}

.fp-body[contenteditable="true"]:focus {
  background: rgba(217, 117, 10, 0.06);
  box-shadow: 0 0 0 2px rgba(217, 117, 10, 0.15);
  outline: none;
  border-radius: 6px;
}

:deep(.dup-mark) {
  background: rgba(239, 68, 68, 0.15);
  border-bottom: 2px solid rgba(239, 68, 68, 0.4);
  padding: 0 2px;
  border-radius: 2px;
}

:deep(.dup-phrase) {
  background: rgba(245, 158, 11, 0.12);
  border-bottom: 2px solid rgba(245, 158, 11, 0.35);
  padding: 0 2px;
  border-radius: 2px;
}

:deep(.dup-report) {
  background: rgba(147, 51, 234, 0.1);
  border-bottom: 2px solid rgba(147, 51, 234, 0.45);
  padding: 0 2px;
  border-radius: 2px;
}

.source-ref-panel {
  flex-shrink: 0;
}

.source-ref-body {
  padding: 14px 24px;
  font-family: Georgia, 'Noto Serif SC', serif;
  font-size: 13px;
  line-height: 1.8;
  color: var(--text-muted);
  max-height: 140px;
  overflow-y: auto;
  background: rgba(0, 0, 0, 0.02);
}

.streaming-text {
  font-family: Georgia, 'Noto Serif SC', serif;
  font-size: 14px;
  line-height: 1.9;
  color: var(--text-main);
  white-space: pre-wrap;
}

.streaming-line {
  margin: 0 0 4px;
}

.cursor-blink {
  display: inline-block;
  width: 2px;
  height: 16px;
  background: var(--accent);
  vertical-align: text-bottom;
  margin-left: 2px;
  animation: blink 0.8s infinite;
}

@keyframes blink {

  0%,
  100% {
    opacity: 1
  }

  50% {
    opacity: 0
  }
}

.result-placeholder {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  gap: 12px;
  color: var(--text-light);
  font-size: 13px;
}

.capsule-toggle {
  position: relative;
  display: inline-flex;
  border: 1px solid var(--border-medium);
  border-radius: 100px;
  overflow: hidden;
  background: var(--bg-surface);
}

.capsule-slider {
  position: absolute;
  top: 0;
  left: 0;
  height: 100%;
  background: #141413;
  border-radius: 100px;
  transition: transform 0.35s cubic-bezier(0.34, 1.56, 0.64, 1);
  z-index: 0;
}

.capsule-opt {
  position: relative;
  z-index: 1;
  padding: 5px 16px;
  font-size: 12px;
  font-weight: 500;
  border: none;
  background: transparent;
  color: var(--text-muted);
  cursor: pointer;
  transition: color 0.25s;
  font-family: inherit;
  white-space: nowrap;
}

.capsule-opt.active {
  color: #fff;
}

.quota-badge {
  font-size: 11px;
  color: var(--text-muted);
  background: var(--bg-surface);
  padding: 4px 12px;
  border-radius: 100px;
  white-space: nowrap;
  border: 1px solid var(--border-light);
}

.quota-badge.quota-low {
  color: var(--color-danger);
  border-color: rgba(239, 68, 68, 0.25);
  background: rgba(239, 68, 68, 0.04);
}

.fragments-panel {
  padding: 14px 24px;
  border-bottom: 1px solid var(--border-light);
  flex-shrink: 0;
  background: rgba(239, 68, 68, 0.02);
}

.fragments-header {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  font-weight: 600;
  color: var(--text-muted);
  margin-bottom: 10px;
}

.fragments-body {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-height: 200px;
  overflow-y: auto;
}

.fragment-row {
  display: flex;
  gap: 12px;
  align-items: flex-start;
  padding: 8px 10px;
  background: var(--bg-surface);
  border-radius: 8px;
  border: 1px solid var(--border-light);
}

.fragment-col {
  flex: 1;
  min-width: 0;
}

.fragment-label {
  font-size: 10px;
  font-weight: 600;
  color: var(--text-light);
  text-transform: uppercase;
  letter-spacing: 0.5px;
  margin-bottom: 4px;
}

.fragment-text {
  font-size: 12px;
  color: var(--text-main);
  line-height: 1.6;
  word-break: break-all;
}

.fragment-len {
  font-size: 10px;
  color: var(--text-light);
  background: var(--bg-paper);
  padding: 2px 8px;
  border-radius: 100px;
  white-space: nowrap;
  flex-shrink: 0;
  margin-top: 2px;
}

.error-toast {
  position: fixed;
  bottom: 24px;
  left: 50%;
  transform: translateX(-50%);
  background: var(--color-danger);
  color: #fff;
  padding: 10px 24px;
  border-radius: 10px;
  font-size: 13px;
  cursor: pointer;
  z-index: 200;
}
.warn-toast {
  position: fixed;
  bottom: 24px;
  left: 50%;
  transform: translateX(-50%);
  background: var(--accent);
  color: #fff;
  padding: 10px 24px;
  border-radius: 10px;
  font-size: 13px;
  cursor: pointer;
  z-index: 200;
}
@media (max-width: 767px) {
  .app-header { padding:10px 14px; flex-wrap:wrap; gap:8px; }
  .app-brand { gap:6px; }
  .brand-name { font-size:18px; }
  .brand-tag { font-size:9px; padding:3px 8px; }
  .btn-switch { font-size:10px; padding:4px 8px; margin-left:0; }
  .btn { padding:6px 10px; font-size:12px; }
  .btn-icon { width:32px; height:32px; }
  .quota-badge { font-size:10px; padding:2px 8px; }
  .export-dropdown .btn { padding:5px 8px; font-size:11px; gap:4px; }
  .export-menu { right:0; top:calc(100% + 6px); bottom:auto; min-width:160px; max-width:calc(100vw - 32px); }
  .pro-toolbar { flex-wrap:wrap; gap:8px; padding:10px 16px; }
  .tabs-container { width:100%; overflow-x:auto; }
  .advanced-panel { padding:12px 16px; }
  .advanced-grid { display:grid; grid-template-columns:1fr 1fr; gap:10px; }
  .advanced-item:last-child { grid-column:1 / -1; }
  .app-shell { max-width:100%; height:100vh; margin:0; border-radius:0; }
  .risk-card__grid { grid-template-columns:repeat(3,1fr); gap:8px; }
  .fragments-panel { margin:0 0 10px; }
}
.workspace--mobile { display:flex; flex-direction:column; flex:1; min-height:0; background:var(--bg-canvas); overflow:hidden; }

/* Citation chips */
:deep(.cite-chip) {
  display: inline-flex; align-items: center; justify-content: center;
  min-width: 1.2em; height: 1.2em; padding: 0 0.3em; margin: 0 0.1em;
  font-size: 0.7em; font-weight: 600; line-height: 1;
  color: #4f6ef7; background: rgba(79, 110, 247, 0.1);
  border: 1px solid rgba(79, 110, 247, 0.3); border-radius: 0.3em;
  cursor: pointer; vertical-align: super; transition: background 0.15s;
}
:deep(.cite-chip:hover) { background: rgba(79, 110, 247, 0.2); }

.reference-section { margin-top: 1.5em; padding-top: 1em; border-top: 1px solid #e8e8e8; }
.reference-section h4 { font-size: 0.95em; margin-bottom: 0.5em; color: #333; }
.reference-section ol { padding-left: 1.5em; font-size: 0.85em; color: #555; line-height: 1.8; }
.reference-section li { margin-bottom: 0.3em; }
</style>
