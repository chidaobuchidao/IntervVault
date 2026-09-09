<template>
  <div class="page">
    <div class="page__inner stagger">
      <!-- Head -->
      <div class="profile-head">
        <div class="profile-avatar" v-if="!userStore.avatarUrl">
          <span class="profile-avatar__letter">{{ (userStore.nickname || '?')[0] }}</span>
        </div>
        <img class="profile-avatar profile-avatar--img" v-else :src="userStore.avatarUrl" alt="" />
        <span class="profile-name">{{ userStore.nickname || '未登录' }}</span>
        <span class="profile-id">ID: {{ userStore.userId }}</span>
      </div>

      <!-- Stats -->
      <div class="stats-row">
        <div class="stat-card">
          <span class="stat-num">{{ stats.practiceCount }}</span>
          <span class="stat-label">刷题数</span>
        </div>
        <div class="stat-card">
          <span class="stat-num">{{ stats.interviewCount }}</span>
          <span class="stat-label">面试次数</span>
        </div>
        <div class="stat-card">
          <span class="stat-num">{{ stats.wrongCount }}</span>
          <span class="stat-label">错题积累</span>
        </div>
      </div>

      <!-- Quota -->
      <div class="quota-banner" v-if="quota && !quota.isAdmin && !quota.hasApiKey">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/></svg>
        <span>剩余免费次数：<strong>{{ quota.quotaRemaining ?? 0 }}</strong> 次 · <a href="#" @click.prevent="openAiModal">配置 API Key</a> 后无限</span>
      </div>
      <div class="quota-banner quota-banner--ok" v-else-if="quota && !quota.isAdmin && quota.hasApiKey">
        <span>已配置个人 API Key，不限使用次数</span>
      </div>

      <!-- Menu -->
      <div class="menu-group">
        <button class="menu-item" @click="$router.push('/token-usage')">
          <span class="menu-item__label">Token 用量</span>
          <span class="menu-item__hint">查看使用记录</span>
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#CCC" stroke-width="2"><polyline points="9 18 15 12 9 6"/></svg>
        </button>
        <button class="menu-item" @click="$router.push('/interview/history')">
          <span class="menu-item__label">面试历史</span>
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#CCC" stroke-width="2">
            <polyline points="9 18 15 12 9 6"/>
          </svg>
        </button>
        <button class="menu-item" @click="$router.push('/practice')">
          <span class="menu-item__label">自由刷题</span>
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#CCC" stroke-width="2">
            <polyline points="9 18 15 12 9 6"/>
          </svg>
        </button>
        <button class="menu-item" @click="$router.push('/wrong-book')">
          <span class="menu-item__label">错题本</span>
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#CCC" stroke-width="2">
            <polyline points="9 18 15 12 9 6"/>
          </svg>
        </button>
      </div>

      <div class="menu-group">
        <button class="menu-item" @click="openAiModal">
          <span class="menu-item__label">AI 模型配置</span>
          <span class="menu-item__hint" v-if="!aiKeyConfigured">未配置</span>
          <span class="menu-item__hint menu-item__hint--ok" v-else>{{ aiProvider }}</span>
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#CCC" stroke-width="2">
            <polyline points="9 18 15 12 9 6"/>
          </svg>
        </button>
      </div>

      <div class="menu-group">
        <button class="menu-item" @click="$router.push('/admin')" v-if="quota?.isAdmin">
          <span class="menu-item__label">管理后台</span>
          <span class="menu-item__hint menu-item__hint--ok">Admin</span>
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#CCC" stroke-width="2">
            <polyline points="9 18 15 12 9 6"/>
          </svg>
        </button>
      </div>

      <div class="menu-group">
        <button class="menu-item menu-item--danger" @click="handleLogout">
          <span class="menu-item__label">退出登录</span>
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#CCC" stroke-width="2">
            <polyline points="9 18 15 12 9 6"/>
          </svg>
        </button>
      </div>

      <!-- AI Config Modal -->
      <div class="modal-overlay" v-if="showAiKey" @click.self="showAiKey = false">
        <div class="modal-card animate-scale-in">
          <span class="modal-title">AI 模型配置</span>

          <!-- Provider grid -->
          <div class="provider-grid" v-if="!loadingProviders">
            <button
              v-for="p in providers"
              :key="p.id"
              class="provider-card"
              :class="{ 'provider-card--active': selectedProvider === p.id }"
              @click="onProviderChange(p.id)"
            >
              {{ p.name }}
            </button>
          </div>
          <div class="provider-grid-loading" v-else>
            <span>加载中...</span>
          </div>

          <!-- Config fields -->
          <div class="config-section" v-if="providers.length">
            <!-- API Key -->
            <label class="field-label">API Key</label>
            <div class="input-with-toggle">
              <input
                class="modal-input"
                v-model="apiKey"
                :type="showApiKey ? 'text' : 'password'"
                placeholder="请输入 API Key"
              />
              <button class="toggle-eye" type="button" @click="showApiKey = !showApiKey">
                <svg v-if="!showApiKey" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/>
                </svg>
                <svg v-else width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19m-6.72-1.07a3 3 0 1 1-4.24-4.24"/>
                  <line x1="1" y1="1" x2="23" y2="23"/>
                </svg>
              </button>
            </div>

            <!-- Model -->
            <label class="field-label">模型</label>
            <select
              v-if="!isCustom && activePreset && activePreset.models.length"
              class="modal-input modal-select"
              v-model="selectedModel"
            >
              <option v-for="m in activePreset.models" :key="m" :value="m">{{ m }}</option>
            </select>
            <input
              v-else
              class="modal-input"
              v-model="selectedModel"
              placeholder="请输入模型名称"
            />

            <!-- Custom endpoint -->
            <template v-if="isCustom">
              <label class="field-label">API 地址</label>
              <input
                class="modal-input"
                v-model="customEndpoint"
                placeholder="https://your-api-endpoint.com/v1"
              />
            </template>

            <!-- Key URL link -->
            <a
              v-if="!isCustom && activePreset?.keyUrl"
              class="modal-apikey-link"
              :href="activePreset.keyUrl"
              target="_blank"
            >
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"/><polyline points="15 3 21 3 21 9"/><line x1="10" y1="14" x2="21" y2="3"/></svg>
              获取 {{ activePreset.name }} API Key
            </a>
          </div>

          <!-- Actions -->
          <div class="modal-actions">
            <button class="btn btn--danger" @click="clearAiConfig">清除配置</button>
            <div class="modal-actions__right">
              <button class="btn btn--ghost" @click="showAiKey = false">取消</button>
              <button class="btn btn--dark" @click="saveAiConfig">保存</button>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { get, put } from '@/utils/request'

/* ---- Types ---- */
interface ProviderPreset {
  id: string
  name: string
  models: string[]
  defaultModel: string
  website?: string
  keyUrl?: string
}

interface AiConfig {
  provider: string
  apiKey: string
  model: string
  preferredModel?: string
  customEndpoint?: string
}

interface Stats {
  practiceCount: number
  interviewCount: number
  wrongCount: number
}

/* ---- Core state ---- */
const router = useRouter()
const userStore = useUserStore()

const showAiKey = ref(false)
const aiKeyConfigured = ref(false)
const aiProvider = ref('')
const quota = ref<{ hasApiKey: boolean; isAdmin: boolean; dailyQuota: number; quotaUsed: number; quotaRemaining: number } | null>(null)
const stats = ref<Stats>({ practiceCount: 0, interviewCount: 0, wrongCount: 0 })

/* ---- AI modal state ---- */
const providers = ref<ProviderPreset[]>([])
const selectedProvider = ref('deepseek')
const apiKey = ref('')
const selectedModel = ref('')
const customEndpoint = ref('')
const showApiKey = ref(false)
const loadingProviders = ref(false)

const activePreset = computed(() => providers.value.find(p => p.id === selectedProvider.value))
const isCustom = computed(() => selectedProvider.value === 'custom')

/* ---- Data fetching ---- */
async function fetchProfileData() {
  try {
    const [sRes, qRes] = await Promise.all([
      get<Stats>('/api/user/stats'),
      get<{ hasApiKey: boolean; isAdmin: boolean; dailyQuota: number; quotaUsed: number; quotaRemaining: number }>('/api/user/quota')
    ])
    if (sRes.data) stats.value = sRes.data
    if (qRes.data) {
      quota.value = qRes.data
      userStore.setAdmin(qRes.data.isAdmin === true)
    }
  } catch {}
}

const PROVIDER_DISPLAY_NAMES: Record<string, string> = {
  deepseek: 'DeepSeek',
  qwen: '通义千问',
  doubao: '豆包',
  zhipu: '智谱',
  custom: '自定义'
}

async function fetchAiStatus() {
  try {
    const res = await get<AiConfig>('/api/user/ai-config')
    if (res.data && res.data.provider) {
      aiKeyConfigured.value = true
      const matched = providers.value.find(p => p.id === res.data!.provider)
      aiProvider.value = matched ? matched.name : (PROVIDER_DISPLAY_NAMES[res.data.provider] || res.data.provider)
    } else {
      aiKeyConfigured.value = false
      aiProvider.value = ''
    }
  } catch {
    aiKeyConfigured.value = false
  }
}

async function openAiModal() {
  showAiKey.value = true
  showApiKey.value = false
  loadingProviders.value = true
  try {
    const pRes = await get<ProviderPreset[]>('/api/user/ai-providers')
    if (pRes.data) providers.value = pRes.data
  } catch {}
  loadingProviders.value = false

  try {
    const cRes = await get<AiConfig>('/api/user/ai-config')
    if (cRes.data && cRes.data.provider) {
      const cfg = cRes.data
      selectedProvider.value = providers.value.some(p => p.id === cfg.provider) ? cfg.provider : 'deepseek'
      apiKey.value = cfg.apiKey || ''
      selectedModel.value = cfg.model || cfg.preferredModel || activePreset.value?.defaultModel || ''
      customEndpoint.value = cfg.customEndpoint || ''
    } else {
      resetModalFields()
    }
  } catch {
    resetModalFields()
  }
}

function resetModalFields() {
  selectedProvider.value = 'deepseek'
  apiKey.value = ''
  selectedModel.value = activePreset.value?.defaultModel || ''
  customEndpoint.value = ''
}

function onProviderChange(id: string) {
  selectedProvider.value = id
  if (id === 'custom') {
    customEndpoint.value = ''
  }
  selectedModel.value = activePreset.value?.defaultModel || ''
}

async function saveAiConfig() {
  if (!apiKey.value.trim()) return
  try {
    await put('/api/user/ai-config', {
      provider: selectedProvider.value,
      apiKey: apiKey.value,
      model: selectedModel.value,
      preferredModel: selectedModel.value,
      customEndpoint: isCustom.value ? customEndpoint.value : undefined
    })
    aiKeyConfigured.value = true
    const matched = providers.value.find(p => p.id === selectedProvider.value)
    aiProvider.value = matched ? matched.name : selectedProvider.value
    showAiKey.value = false
  } catch {
    alert('保存失败')
  }
}

async function clearAiConfig() {
  try {
    await put('/api/user/ai-config', { provider: '', apiKey: '', model: '' })
    aiKeyConfigured.value = false
    aiProvider.value = ''
    showAiKey.value = false
  } catch {
    alert('清除失败')
  }
}

onMounted(() => {
  fetchProfileData()
  fetchAiStatus()
})

function handleLogout() {
  userStore.clearUser()
  router.push('/login')
}
</script>

<style scoped>
.page { min-height: 100vh; background: var(--bg-canvas); }
.page__inner { max-width: 500px; margin: 0 auto; padding: 0 20px; }

/* Head */
.profile-head {
  padding: 48px 0 32px;
  display: flex; flex-direction: column; align-items: center;
}
.profile-avatar {
  width: 80px; height: 80px;
  border-radius: 24px;
  background: var(--bg-surface);
  border: 1px solid var(--border-medium);
  display: flex; align-items: center; justify-content: center;
  margin-bottom: 16px;
  overflow: hidden;
}
.profile-avatar--img { object-fit: cover; }
.profile-avatar__letter {
  font-family: var(--font-serif);
  font-size: 32px; font-weight: 600;
  color: var(--text-main);
}
.profile-name { font-size: 20px; font-weight: 600; }
.profile-id { font-size: 12px; color: var(--text-light); margin-top: 4px; }

/* Stats */
.stats-row {
  display: grid; grid-template-columns: 1fr 1fr 1fr; gap: 12px;
  margin-bottom: 36px;
}
.stat-card {
  background: var(--bg-paper);
  border: 1px solid var(--border-light);
  border-radius: var(--radius-lg);
  padding: 18px 12px;
  text-align: center;
  box-shadow: var(--shadow-sm);
}
.stat-num {
  font-size: 24px; font-weight: 700; display: block; margin-bottom: 4px;
  font-family: var(--font-serif);
}
.stat-label {
  font-size: 12px; color: var(--text-light);
}

/* Menu */
.menu-group {
  background: var(--bg-paper);
  border: 1px solid var(--border-light);
  border-radius: var(--radius-lg);
  overflow: hidden;
  margin-bottom: 16px;
}
.menu-item {
  width: 100%;
  padding: 16px;
  display: flex; align-items: center; gap: 12px;
  font-size: 15px; color: var(--text-main);
  background: none; border: none; cursor: pointer;
  transition: background 0.15s;
}
.menu-item:not(:last-child) {
  border-bottom: 1px solid var(--border-light);
}
.menu-item:hover { background: var(--bg-surface); }
.menu-item__label { flex: 1; text-align: left; }
.menu-item__hint { font-size: 13px; color: var(--text-light); }
.menu-item__hint--ok { color: var(--color-success); }
.menu-item--danger .menu-item__label { color: var(--color-danger); }

.menu-item svg:last-child { flex-shrink: 0; }
.modal-overlay {
  position: fixed; inset: 0; z-index: 100;
  background: rgba(20,20,19,0.5);
  display: flex; align-items: center; justify-content: center;
  backdrop-filter: blur(4px);
}
.modal-card {
  background: var(--bg-paper); border-radius: var(--radius-xl);
  padding: 32px 28px; max-width: 480px; width: calc(100% - 40px);
  box-shadow: var(--shadow-xl);
}
.modal-title {
  font-family: var(--font-serif); font-size: 18px; font-weight: 600;
  display: block; margin-bottom: 20px;
}

/* Provider grid */
.provider-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 10px;
  margin-bottom: 20px;
}
@media (min-width: 520px) {
  .provider-grid { grid-template-columns: repeat(5, 1fr); }
}
.provider-grid-loading {
  text-align: center; padding: 16px 0;
  font-size: 13px; color: var(--text-light);
  margin-bottom: 20px;
}
.provider-card {
  padding: 12px 8px;
  border: 1.5px solid var(--border-light);
  border-radius: var(--radius-md);
  background: var(--bg-surface);
  font-size: 13px; font-weight: 500;
  color: var(--text-main);
  cursor: pointer;
  transition: all 0.15s;
  text-align: center;
}
.provider-card:hover {
  border-color: var(--text-muted);
}
.provider-card--active {
  border-color: var(--accent);
  background: rgba(217,117,10,0.06);
  color: var(--accent);
  font-weight: 600;
}

/* Config section */
.config-section { margin-bottom: 4px; }
.field-label {
  display: block;
  font-size: 12px; font-weight: 600;
  color: var(--text-light);
  margin-bottom: 6px;
  text-transform: uppercase;
  letter-spacing: 0.04em;
}
.modal-input {
  width: 100%; padding: 12px 14px; margin-bottom: 14px;
  border: 1px solid var(--border-light); border-radius: var(--radius-md);
  font-size: 14px; outline: none; font-family: inherit;
  background: var(--bg-surface); transition: border-color 0.15s;
  box-sizing: border-box;
}
.modal-input:focus { border-color: var(--accent); }
.modal-select {
  appearance: none;
  cursor: pointer;
  background-image: url("data:image/svg+xml,%3Csvg width='12' height='8' viewBox='0 0 12 8' fill='none' xmlns='http://www.w3.org/2000/svg'%3E%3Cpath d='M1 1.5L6 6.5L11 1.5' stroke='%23999' stroke-width='1.5' stroke-linecap='round' stroke-linejoin='round'/%3E%3C/svg%3E");
  background-repeat: no-repeat;
  background-position: right 14px center;
  padding-right: 36px;
}

/* API key input with toggle */
.input-with-toggle {
  position: relative; margin-bottom: 14px;
}
.input-with-toggle .modal-input {
  padding-right: 42px;
  margin-bottom: 0;
}
.toggle-eye {
  position: absolute; right: 12px; top: 50%; transform: translateY(-50%);
  background: none; border: none; cursor: pointer;
  color: var(--text-light); padding: 2px;
  display: flex; align-items: center; justify-content: center;
  transition: color 0.15s;
}
.toggle-eye:hover { color: var(--text-main); }

.modal-apikey-link {
  display: inline-flex; align-items: center; gap: 6px;
  margin-bottom: 14px;
  font-size: 12px; color: var(--accent);
  text-decoration: none;
  transition: opacity 0.15s;
}
.modal-apikey-link:hover { opacity: 0.8; }

/* Modal actions */
.modal-actions {
  display: flex; gap: 10px; margin-top: 20px;
  align-items: center;
}
.modal-actions__right {
  margin-left: auto;
  display: flex; gap: 10px;
}
.modal-actions .btn {
  padding: 10px 24px; border-radius: 100px;
  font-size: 14px; cursor: pointer; border: none;
  font-family: inherit;
  transition: opacity 0.15s;
}
.modal-actions .btn:hover { opacity: 0.85; }
.modal-actions .btn--ghost { background: var(--bg-surface); color: var(--text-muted); }
.modal-actions .btn--dark { background: var(--bg-dark); color: #fff; }
.modal-actions .btn--danger {
  background: none; color: var(--color-danger, #e74c3c);
  padding-left: 12px; padding-right: 12px;
  font-size: 13px;
}

.quota-banner {
  display: flex; align-items: center; gap: 8px;
  padding: 12px 16px;
  margin: 0 0 16px;
  background: rgba(217,117,10,0.06);
  border: 1px solid rgba(217,117,10,0.12);
  border-radius: var(--radius-md);
  font-size: 13px; color: var(--accent); line-height: 1.5;
}
.quota-banner strong { color: var(--text-main); }
.quota-banner a { color: var(--accent); font-weight: 600; }
.quota-banner--ok {
  background: rgba(34,197,94,0.05);
  border-color: rgba(34,197,94,0.12);
  color: var(--color-success);
}

</style>
