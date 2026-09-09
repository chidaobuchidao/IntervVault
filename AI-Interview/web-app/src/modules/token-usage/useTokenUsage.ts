import { onBeforeUnmount, ref } from 'vue'
import { get } from '@/utils/request'
import type { TokenUsage, UsageFilters } from './types'

export function useTokenUsage() {
  const data = ref<TokenUsage | null>(null)
  const loading = ref(false)
  const error = ref('')
  let requestId = 0
  onBeforeUnmount(() => { requestId++ })

  async function load(admin: boolean, filters: UsageFilters) {
    const id = ++requestId
    loading.value = true
    error.value = ''
    // Clear old results so the displayed totals never describe a previous filter.
    data.value = null
    try {
      const params: Record<string, unknown> = {
        days: filters.days, model: filters.model, feature: filters.feature
      }
      if (admin) Object.assign(params, { userId: filters.userId, keySource: filters.keySource })
      const response = await get<TokenUsage>(`/api/${admin ? 'admin' : 'user'}/token-usage`, params)
      if (response.code !== 200 || !response.data) throw new Error(response.message || '暂时无法获取用量')
      if (id === requestId) data.value = response.data
    } catch (cause) {
      if (id === requestId) error.value = cause instanceof Error ? cause.message : '网络异常，请稍后重试'
    } finally {
      if (id === requestId) loading.value = false
    }
  }
  return { data, loading, error, load }
}
