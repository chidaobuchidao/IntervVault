<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { compactNumber, formatNumber, type UsageDay } from '@/modules/token-usage/types'

const props = defineProps<{ days: UsageDay[] }>()
const selectedDate = ref('')
watch(() => props.days, days => { selectedDate.value = days.at(-1)?.date || '' }, { immediate: true })
const selected = computed(() => props.days.find(day => day.date === selectedDate.value))
const peak = computed(() => Math.max(1, ...props.days.map(day => day.inputTokens + day.outputTokens)))
const chartWidth = computed(() => Math.max(650, props.days.length * 24 + 64))
const step = computed(() => (chartWidth.value - 64) / Math.max(1, props.days.length))
const barWidth = computed(() => Math.min(38, step.value * 0.66))
const height = (value: number) => value / peak.value * 180
const label = (day: UsageDay) => `${day.date}，输入 ${formatNumber(day.inputTokens)}，输出 ${formatNumber(day.outputTokens)}，调用 ${day.calls} 次，未知用量 ${day.unknownCalls} 次`
function move(event: KeyboardEvent, index: number) {
  const offset = event.key === 'ArrowRight' ? 1 : event.key === 'ArrowLeft' ? -1 : 0
  if (!offset) return
  event.preventDefault()
  const next = Math.max(0, Math.min(props.days.length - 1, index + offset))
  selectedDate.value = props.days[next]!.date
  const target = event.currentTarget as HTMLElement
  ;(target.parentElement?.children[next] as HTMLElement | undefined)?.focus()
}
</script>

<template>
  <section class="trend" aria-labelledby="trend-title">
    <div class="trend-head">
      <div><h2 id="trend-title">每日用量</h2><p>供应商已返回的 Token 数</p></div>
      <div class="legend"><span><i class="input-dot" />输入</span><span><i class="output-dot" />输出</span></div>
    </div>
    <div class="chart-scroll" role="region" aria-label="每日 Token 趋势，可横向滚动" tabindex="0">
      <svg :viewBox="`0 0 ${chartWidth} 250`" :style="{ minWidth: `${chartWidth}px` }" class="chart" role="group" aria-label="输入与输出 Token 堆叠柱状图">
        <g aria-hidden="true">
          <template v-for="fraction in [0, 0.5, 1]" :key="fraction">
            <line x1="52" :x2="chartWidth - 12" :y1="206 - fraction * 180" :y2="206 - fraction * 180" stroke="#e5e7e5" stroke-dasharray="3 4" />
            <text x="44" :y="210 - fraction * 180" text-anchor="end">{{ compactNumber(peak * fraction) }}</text>
          </template>
        </g>
        <g>
          <g v-for="(day, index) in days" :key="day.date" class="day-bar" role="button" :tabindex="selectedDate === day.date ? 0 : -1"
            :aria-label="label(day)" :aria-pressed="selectedDate === day.date"
            @focus="selectedDate = day.date" @click="selectedDate = day.date"
            @keydown.enter.prevent="selectedDate = day.date" @keydown.space.prevent="selectedDate = day.date" @keydown="move($event, index)">
            <title>{{ label(day) }}</title>
            <rect class="bar-hit" :x="52 + index * step" y="14" :width="step" height="200" rx="4" :fill="selectedDate === day.date ? '#edf1ef' : 'transparent'" />
            <rect :x="52 + index * step + (step - barWidth) / 2" :y="206 - height(day.inputTokens)" :width="barWidth" :height="height(day.inputTokens)" fill="#167d8d" />
            <rect :x="52 + index * step + (step - barWidth) / 2" :y="206 - height(day.inputTokens + day.outputTokens)" :width="barWidth" :height="height(day.outputTokens)" fill="#547bd3" rx="2" />
            <circle v-if="day.unknownCalls" :cx="52 + (index + 0.5) * step" cy="218" r="2.5" fill="#986522" aria-hidden="true" />
            <text v-if="days.length <= 7 || index % Math.ceil(days.length / 10) === 0 || index === days.length - 1" :x="52 + (index + 0.5) * step" y="240" text-anchor="middle" aria-hidden="true">{{ day.date.slice(5).replace('-', '/') }}</text>
          </g>
        </g>
      </svg>
    </div>
    <div v-if="selected" class="day-detail" aria-live="polite" aria-atomic="true">
      <strong>{{ selected.date }}</strong><span>输入 <b>{{ formatNumber(selected.inputTokens) }}</b></span><span>输出 <b>{{ formatNumber(selected.outputTokens) }}</b></span><span>{{ formatNumber(selected.calls) }} 次调用</span><span v-if="selected.unknownCalls">{{ formatNumber(selected.unknownCalls) }} 次未知用量</span>
    </div>
    <p class="chart-help">点按柱形或使用左右方向键查看每日数值。棕色圆点表示当天有未知用量。</p>
    <details class="data-table"><summary>查看每日明细表</summary><div class="table-scroll" tabindex="0" role="region" aria-label="每日明细，可横向滚动"><table><caption>每日 Token 与调用明细</caption><thead><tr><th scope="col">日期</th><th scope="col">输入</th><th scope="col">输出</th><th scope="col">合计</th><th scope="col">调用</th><th scope="col">未知</th><th scope="col">失败</th></tr></thead><tbody><tr v-for="day in days" :key="day.date"><th scope="row">{{ day.date }}</th><td>{{ formatNumber(day.inputTokens) }}</td><td>{{ formatNumber(day.outputTokens) }}</td><td>{{ formatNumber(day.totalTokens) }}</td><td>{{ formatNumber(day.calls) }}</td><td>{{ formatNumber(day.unknownCalls) }}</td><td>{{ formatNumber(day.failedCalls) }}</td></tr></tbody></table></div></details>
  </section>
</template>

<style scoped>
.trend { padding: 28px; border: 1px solid var(--border-medium); border-radius: 18px; background: var(--bg-paper); }
.trend-head { display: flex; justify-content: space-between; gap: 16px; align-items: center; margin-bottom: 24px; }
h2 { font-size: 18px; letter-spacing: -.4px; } p { color: #626663; font-size: 12px; margin-top: 4px; }
.legend, .legend span { display: flex; align-items: center; gap: 7px; font-size: 12px; color: #505653; } .legend { gap: 18px; }
i { width: 9px; height: 9px; border-radius: 2px; } .input-dot { background: #167d8d; } .output-dot { background: #547bd3; }
.chart-scroll { overflow-x: auto; } .chart { width: 100%; display: block; height: 250px; }
text { font-size: 11px; fill: #616761; font-family: var(--font-mono); } .day-bar { cursor: pointer; outline: none; } .day-bar:focus-visible .bar-hit { stroke: #141413; stroke-width: 2; }
.day-detail { margin-top: 16px; padding: 13px 16px; display: flex; flex-wrap: wrap; gap: 10px 24px; background: #f0f3f2; border-radius: 8px; font-size: 12px; color: #505653; min-height: 46px; }
.day-detail strong, .day-detail b { font-family: var(--font-mono); color: #242b28; font-weight: 500; } .chart-help { margin-top: 10px; }
.data-table { font-size: 12px; margin-top: 16px; } summary { cursor: pointer; min-height: 44px; display: list-item; align-content: center; } .table-scroll { overflow: auto; max-height: 350px; } table { width: 100%; border-collapse: collapse; white-space: nowrap; } caption { text-align: left; padding-bottom: 8px; color: #626663; } th, td { padding: 10px; border-bottom: 1px solid #e5e7e5; text-align: right; } th:first-child { text-align: left; }
@media (max-width: 600px) { .trend { padding: 20px 16px; } .trend-head { align-items: start; } .legend { gap: 10px; padding-top: 6px; } .day-detail { gap: 8px 16px; } }
</style>
