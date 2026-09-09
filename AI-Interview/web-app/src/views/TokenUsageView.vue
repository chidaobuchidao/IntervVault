<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import TokenUsageTrend from '@/components/TokenUsageTrend.vue'
import TokenUsageRanking from '@/components/TokenUsageRanking.vue'
import { useTokenUsage } from '@/modules/token-usage/useTokenUsage'
import { featureLabels, formatNumber, type UsageDays, type UsageFilters } from '@/modules/token-usage/types'

const route = useRoute()
const admin = computed(() => route.meta.requiresAdmin === true)
const days = ref<UsageDays>(7)
const filters = reactive({ model: '', feature: '', userId: '', keySource: '' as '' | 'SYSTEM' | 'PERSONAL' })
const applied = ref<UsageFilters>({ days: 7 })
const validation = ref('')
const models = ref<string[]>([])
const { data, loading, error, load } = useTokenUsage()
const coverage = computed(() => data.value?.summary.calls
  ? ((data.value.summary.calls - data.value.summary.unknownCalls) / data.value.summary.calls * 100).toFixed(1) : null)
const filtered = computed(() => !!(applied.value.model || applied.value.feature || applied.value.userId || applied.value.keySource))
const dirty = computed(() => filters.model.trim() !== (applied.value.model || '') || filters.feature !== (applied.value.feature || '')
  || (admin.value && (filters.userId.trim() !== (applied.value.userId || '') || filters.keySource !== (applied.value.keySource || ''))))

watch(data, value => {
  if (value) models.value = [...new Set([...models.value, ...value.models.map(model => model.key)])].sort()
})
watch(admin, () => {
  validation.value = ''
  Object.assign(filters, { model: '', feature: '', userId: '', keySource: '' })
  days.value = 7
  applied.value = { days: 7 }
  models.value = []
  void load(admin.value, applied.value)
}, { immediate: true })
function apply() {
  validation.value = ''
  const userId = filters.userId.trim()
  if (admin.value && userId && (!/^[1-9]\d*$/.test(userId) || BigInt(userId) > 9223372036854775807n)) {
    validation.value = '用户 ID 需为有效的正整数'
    return
  }
  applied.value = {
    days: days.value, model: filters.model.trim(), feature: filters.feature,
    ...(admin.value ? { userId, keySource: filters.keySource } : {})
  }
  void load(admin.value, applied.value)
}
function setDays(value: UsageDays) {
  days.value = value
  applied.value = { ...applied.value, days: value }
  void load(admin.value, applied.value)
}
function reset() {
  Object.assign(filters, { model: '', feature: '', userId: '', keySource: '' })
  apply()
}
</script>

<template>
  <main class="usage-page">
    <nav class="usage-nav" aria-label="页面导航"><RouterLink :to="admin ? '/admin' : '/profile'">← {{ admin ? '管理后台' : '个人中心' }}</RouterLink><span>{{ admin ? 'ADMIN / USAGE' : 'ACCOUNT / USAGE' }}</span></nav>
    <header class="usage-header">
      <div><p class="eyebrow">{{ admin ? '全站统计' : '我的使用记录' }}</p><h1>{{ admin ? '全站 Token 用量' : 'Token 用量' }}</h1><p class="intro">每一次调用，都有迹可循。</p></div>
      <div class="period" role="group" aria-label="统计时间范围"><button v-for="value in ([7, 30, 90] as const)" :key="value" :aria-pressed="days === value" @click="setDays(value)">{{ value }} 天</button></div>
    </header>

    <form class="filters" aria-label="用量筛选" @submit.prevent="apply">
      <label class="model-field"><span>模型</span><input v-model="filters.model" list="usage-models" maxlength="255" placeholder="全部模型" /><datalist id="usage-models"><option v-for="model in models" :key="model" :value="model" /></datalist></label>
      <label><span>功能</span><select v-model="filters.feature"><option value="">全部功能</option><option v-for="(name, key) in featureLabels" :key="key" :value="key">{{ name }}</option></select></label>
      <template v-if="admin"><label><span>用户 ID</span><input v-model="filters.userId" inputmode="numeric" maxlength="19" placeholder="全部用户" :aria-invalid="!!validation" :aria-describedby="validation ? 'filter-validation' : undefined" /></label><label><span>Key 来源</span><select v-model="filters.keySource"><option value="">全部来源</option><option value="SYSTEM">系统 Key</option><option value="PERSONAL">个人 Key</option></select></label></template>
      <div class="filter-actions"><button class="apply-btn" type="submit">应用筛选</button><button class="reset-btn" type="button" @click="reset">重置</button></div>
      <p v-if="validation" id="filter-validation" class="validation" role="alert">{{ validation }}</p>
      <p v-else-if="dirty" class="filter-note">筛选条件已修改，点击“应用筛选”更新统计。</p>
    </form>

    <div v-if="loading" class="loading-state" role="status" aria-live="polite"><div class="skeleton skeleton-total" /><div class="skeleton skeleton-chart" /><p>正在汇总用量…</p></div>
    <section v-else-if="error" class="state-card" role="alert"><span class="state-symbol">!</span><h2>用量暂时无法加载</h2><p>{{ error }}</p><button class="apply-btn" @click="load(admin, applied)">重新加载</button></section>
    <template v-else-if="data">
      <div class="report-meta"><span>{{ data.startDate }} 至 {{ data.daily.at(-1)?.date || data.endDate }} · {{ data.timezone }}</span><span>{{ filtered ? '已应用筛选' : (admin ? '全站全部调用' : '仅我的调用') }}</span></div>
      <section class="overview" aria-label="用量概览">
        <div class="total-card"><span class="total-label">已知总 Token</span><strong>{{ formatNumber(data.summary.totalTokens) }}</strong><span class="total-foot">{{ days }} 天累计 <span>INPUT + OUTPUT</span></span></div>
        <div class="stat-grid">
          <div class="stat"><span><i class="input-dot" />已知输入 Token</span><strong>{{ formatNumber(data.summary.inputTokens) }}</strong></div>
          <div class="stat"><span><i class="output-dot" />已知输出 Token</span><strong>{{ formatNumber(data.summary.outputTokens) }}</strong></div>
          <div class="stat"><span>调用次数</span><strong>{{ formatNumber(data.summary.calls) }}<small>次</small></strong></div>
          <div class="stat"><span>未返回用量</span><strong>{{ formatNumber(data.summary.unknownCalls) }}<small>次</small></strong></div>
        </div>
      </section>
      <aside class="coverage" :class="{ 'coverage--partial': data.summary.unknownCalls > 0 }"><div><strong>用量覆盖率 {{ coverage === null ? '暂无' : `${coverage}%` }}</strong><p v-if="data.summary.unknownCalls">{{ formatNumber(data.summary.unknownCalls) }} 次调用未返回完整用量，实际消耗可能高于图表统计。</p><p v-else>{{ data.summary.calls ? '当前调用均已返回用量。' : '还没有可统计的调用。' }}</p></div><span>失败 / 中断 <b>{{ formatNumber(data.summary.failedCalls) }}</b> 次</span></aside>
      <section v-if="data.summary.calls === 0" class="state-card empty-state"><span class="empty-chart" aria-hidden="true"><i /><i /><i /></span><h2>{{ filtered ? '当前筛选下暂无调用' : '用量记录从这里开始' }}</h2><p>{{ filtered ? '尝试调整时间范围、模型或功能。' : '使用面试、简历或论文工具后，可在这里查看记录。' }}</p><button v-if="filtered" class="apply-btn" @click="reset">清除筛选</button><RouterLink v-else class="apply-btn" :to="admin ? '/admin' : '/profile'">{{ admin ? '返回管理后台' : '返回个人中心' }}</RouterLink></section>
      <template v-else><TokenUsageTrend :days="data.daily" /><div class="rankings"><TokenUsageRanking title="模型排行" :groups="data.models" /><TokenUsageRanking title="功能排行" :groups="data.features" :labels="featureLabels" /><TokenUsageRanking v-if="admin" class="user-ranking" title="用户排行" :groups="data.users || []" users /></div></template>
      <footer class="usage-footer"><span>统计说明</span><p>仅统计本应用发出的 AI 调用，记录从功能上线后开始积累。Token 以供应商返回的用量为准，未知用量不计入合计。失败或中断的调用可能仍产生 Token。本页不计算费用。</p></footer>
    </template>
  </main>
</template>

<style scoped>
.usage-page { max-width: 1152px; margin: 0 auto; padding: 30px 36px 54px; color: #202722; }
.usage-nav { display: flex; justify-content: space-between; align-items: center; color: #555d57; font-size: 12px; padding-bottom: 26px; border-bottom: 1px solid var(--border-medium); } .usage-nav a { min-height: 44px; display: inline-flex; align-items: center; } .usage-nav > span { font: 10px var(--font-mono); letter-spacing: 1.5px; }
.usage-header { display: flex; align-items: flex-end; justify-content: space-between; gap: 24px; padding: 35px 0 29px; } .eyebrow { font-size: 11px; color: #646b66; letter-spacing: 2px; margin-bottom: 8px; } h1 { font-size: clamp(28px, 4vw, 38px); font-weight: 600; letter-spacing: -1.5px; line-height: 1.3; } .intro { color: #666c67; font-size: 13px; margin-top: 11px; }
.period { display: flex; padding: 4px; border: 1px solid #dce0db; border-radius: 10px; background: #eaece7; flex-shrink: 0; } .period button { min-height: 38px; padding: 0 18px; font-size: 12px; border-radius: 7px; color: #4d554e; } .period button[aria-pressed=true] { background: #202722; color: #fff; }
.filters { display: flex; align-items: flex-end; flex-wrap: wrap; gap: 14px; padding: 20px; background: var(--bg-paper); border: 1px solid var(--border-medium); border-radius: 14px; margin-bottom: 24px; } .filters label { display: flex; flex-direction: column; gap: 7px; flex: 1 1 130px; min-width: 0; } .filters .model-field { flex: 1.5 1 180px; } label > span { font-size: 11px; color: #565e57; } input, select { width: 100%; height: 42px; padding: 0 11px; border: 1px solid #d9dfd9; border-radius: 7px; background: #fff; color: #27302a; font-family: inherit; font-size: 12px; } input::placeholder { color: #69706b; }
.filter-actions { display: flex; gap: 10px; } .apply-btn { display: inline-flex; align-items: center; justify-content: center; min-height: 42px; padding: 10px 18px; color: #fff; background: #202722; border-radius: 7px; font-size: 12px; } .apply-btn:hover { background: #3b463e; } .reset-btn { color: #555e57; min-height: 42px; padding: 0 8px; font-size: 12px; } .filter-note, .validation { flex-basis: 100%; font-size: 12px; } .filter-note { color: #626a64; } .validation { color: #a52c20; }
.report-meta { display: flex; flex-wrap: wrap; justify-content: space-between; gap: 7px; font-size: 11px; color: #616962; margin-bottom: 12px; }
.overview { display: grid; grid-template-columns: 1fr 1.25fr; gap: 18px; } .total-card { border-radius: 15px; background: #202722; color: #fff; padding: 28px; display: flex; flex-direction: column; justify-content: space-between; min-width: 0; } .total-label { font-size: 12px; color: #d4ddd5; } .total-card > strong { font-family: var(--font-mono); font-weight: 400; font-size: clamp(26px, 4vw, 42px); letter-spacing: -1.6px; margin: 18px 0; overflow-wrap: anywhere; } .total-foot { display: flex; justify-content: space-between; gap: 10px; font-size: 10px; color: #c2cec4; } .total-foot > span { font: 9px var(--font-mono); letter-spacing: 1px; }
.stat-grid { display: grid; grid-template-columns: 1fr 1fr; background: var(--bg-paper); border: 1px solid var(--border-medium); border-radius: 15px; overflow: hidden; } .stat { padding: 22px; min-width: 0; } .stat:nth-child(odd) { border-right: 1px solid var(--border-medium); } .stat:nth-child(-n+2) { border-bottom: 1px solid var(--border-medium); } .stat > span { display: flex; align-items: center; gap: 6px; font-size: 11px; color: #5e6760; } .stat strong { display: block; margin-top: 8px; font: 24px var(--font-mono); overflow-wrap: anywhere; } .stat small { font: 11px var(--font-sans); padding-left: 6px; color: #626b64; } .stat i { width: 7px; height: 7px; border-radius: 2px; } .input-dot { background: #167d8d; } .output-dot { background: #547bd3; }
.coverage { display: flex; justify-content: space-between; align-items: center; gap: 20px; margin: 16px 0 28px; padding: 15px 18px; border: 1px solid #dce4dd; border-radius: 9px; background: #edf1ed; } .coverage strong { font-size: 12px; font-weight: 500; } .coverage p { font-size: 11px; color: #5a645c; margin-top: 3px; } .coverage > span { font-size: 11px; flex-shrink: 0; color: #576159; } .coverage b { font-family: var(--font-mono); font-weight: 500; } .coverage--partial { background: #f7f0e5; border-color: #e8dcc6; }
.rankings { display: grid; grid-template-columns: 1fr 1fr; gap: 20px; margin-top: 20px; } .user-ranking { grid-column: 1 / -1; } .usage-footer { display: flex; gap: 28px; margin-top: 28px; padding-top: 22px; border-top: 1px solid var(--border-medium); color: #666e67; font-size: 11px; } .usage-footer > span { flex-shrink: 0; } .usage-footer p { max-width: 850px; line-height: 1.9; }
.state-card { text-align: center; padding: 60px 22px; border: 1px solid var(--border-medium); border-radius: 15px; background: var(--bg-paper); } .state-card h2 { font-size: 20px; font-weight: 500; margin: 14px 0 8px; } .state-card p { font-size: 13px; color: #646d65; margin-bottom: 22px; overflow-wrap: anywhere; } .state-symbol { display: inline-flex; align-items: center; justify-content: center; width: 40px; height: 40px; border: 1px solid #ad776e; border-radius: 50%; color: #9e4433; font-family: var(--font-serif); font-size: 25px; } .empty-chart { display: inline-flex; gap: 6px; align-items: flex-end; height: 42px; } .empty-chart i { width: 12px; background: #c1d4d1; height: 22px; border-radius: 3px 3px 0 0; } .empty-chart i:nth-child(2) { height: 40px; background: #167d8d; } .empty-chart i:last-child { height: 30px; background: #547bd3; }
.skeleton { background: #e3e7e1; border-radius: 15px; animation: breathe 1.4s ease-in-out infinite alternate; } .skeleton-total { height: 212px; } .skeleton-chart { height: 350px; margin-top: 24px; } .loading-state p { font-size: 13px; padding-top: 12px; color: #59645c; } @keyframes breathe { to { opacity: .45; } }
:is(button, a, input, select):focus-visible { outline: 2px solid #167d8d; outline-offset: 3px; }
@media (max-width: 760px) { .usage-page { padding: 18px 20px 40px; } .usage-nav { padding-bottom: 12px; } .usage-header { padding-top: 27px; } .overview { grid-template-columns: 1fr; } .total-card { padding: 24px; } .total-card > strong { font-size: 38px; margin: 12px 0; } .rankings { grid-template-columns: 1fr; } .stat { padding: 19px; } .stat strong { font-size: 22px; } .coverage { align-items: flex-start; flex-direction: column; gap: 9px; } .usage-footer { flex-direction: column; gap: 7px; } input, select { font-size: 16px; } }
@media (max-width: 480px) { .usage-page { padding-left: 16px; padding-right: 16px; } .usage-header { align-items: flex-start; flex-direction: column; gap: 20px; } .period { width: 100%; } .period button { flex: 1; } .filters { padding: 16px; gap: 12px; } .filters .model-field { flex-basis: 100%; } .filter-actions { width: 100%; } .apply-btn { flex: 1; } .intro { margin-top: 8px; } .stat { padding: 16px 12px; } .stat strong { font-size: 20px; } }
@media (prefers-reduced-motion: reduce) { .skeleton { animation: none; } }
</style>
