export type UsageDays = 7 | 30 | 90
export interface UsageStats {
  calls: number
  inputTokens: number
  outputTokens: number
  totalTokens: number
  unknownCalls: number
  failedCalls: number
}
export interface UsageDay extends UsageStats { date: string }
export interface UsageGroup extends UsageStats { key: string }
export interface TokenUsage {
  timezone: string
  startDate: string
  endDate: string
  summary: UsageStats
  daily: UsageDay[]
  models: UsageGroup[]
  features: UsageGroup[]
  users?: UsageGroup[] | null
}
export interface UsageFilters {
  days: UsageDays
  model?: string
  feature?: string
  userId?: string
  keySource?: '' | 'SYSTEM' | 'PERSONAL'
}
export const featureLabels: Record<string, string> = {
  INTERVIEW: '模拟面试', RESUME: '简历分析', PAPER_POLISH: '论文润色',
  PAPER_REDUCE: '论文降重', AI_REDUCE: '降低 AI 痕迹', EVIDENCE: '证据检索', OTHER: '其他'
}
export const formatNumber = (value: number) => value.toLocaleString('zh-CN')
export const compactNumber = (value: number) => value >= 1_000_000
  ? `${+(value / 1_000_000).toFixed(1)}M`
  : value >= 1000 ? `${+(value / 1000).toFixed(1)}k` : String(value)
