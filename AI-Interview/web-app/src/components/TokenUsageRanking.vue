<script setup lang="ts">
import { computed } from 'vue'
import { formatNumber, type UsageGroup } from '@/modules/token-usage/types'
const props = defineProps<{ title: string; groups: UsageGroup[]; labels?: Record<string, string>; users?: boolean }>()
const peak = computed(() => Math.max(1, ...props.groups.map(group => group.totalTokens)))
const name = (key: string) => props.users ? (key === 'unassigned' ? '未归属用户' : `用户 ${key}`) : props.labels?.[key] || key || '未标记'
</script>

<template>
  <section class="ranking" :aria-label="title">
    <div class="ranking-head"><h2>{{ title }}</h2><span>已知 Token · 前 20 项</span></div>
    <p v-if="!groups.length" class="ranking-empty">当前筛选下暂无数据</p>
    <ol v-else>
      <li v-for="(group, index) in groups" :key="group.key">
        <div class="rank-line"><span class="rank-index">{{ String(index + 1).padStart(2, '0') }}</span><span class="rank-name">{{ name(group.key) }}</span><strong>{{ formatNumber(group.totalTokens) }}</strong></div>
        <div class="rank-track" aria-hidden="true"><span :style="{ width: `${group.totalTokens / peak * 100}%` }" /></div>
        <p>{{ formatNumber(group.calls) }} 次调用<span v-if="group.unknownCalls"> · {{ formatNumber(group.unknownCalls) }} 次未知用量</span></p>
      </li>
    </ol>
  </section>
</template>

<style scoped>
.ranking { padding: 26px 28px; border: 1px solid var(--border-medium); border-radius: 18px; background: var(--bg-paper); min-width: 0; }
.ranking-head { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 8px; margin-bottom: 25px; } h2 { font-size: 17px; } .ranking-head > span { color: #656a66; font-size: 11px; }
ol { list-style: none; display: grid; gap: 23px; } .rank-line { display: flex; gap: 10px; align-items: baseline; font-size: 13px; } .rank-index { font-family: var(--font-mono); color: #626a64; font-size: 11px; } .rank-name { flex: 1; min-width: 0; overflow-wrap: anywhere; } strong { font-family: var(--font-mono); font-size: 14px; font-weight: 500; }
.rank-track { margin: 9px 0 5px 24px; height: 5px; background: #e9eeeb; border-radius: 5px; overflow: hidden; } .rank-track span { display: block; height: 100%; background: #167d8d; border-radius: inherit; }
li p { margin-left: 24px; font-size: 11px; color: #676c68; } .ranking-empty { color: #676c68; font-size: 13px; padding: 20px 0; }
@media (max-width: 600px) { .ranking { padding: 22px 18px; } }
</style>
