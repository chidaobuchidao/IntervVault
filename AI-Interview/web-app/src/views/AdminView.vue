<template>
  <div class="page">
    <div class="page__inner">
      <div class="page-head">
        <button class="back-btn" @click="$router.push('/profile')">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <polyline points="15 18 9 12 15 6"/>
          </svg>
        </button>
        <span class="page-head__title">管理后台</span>
        <span class="page-head__role">Admin</span>
      </div>

      <!-- Stats -->
      <RouterLink class="usage-entry" to="/admin/token-usage">全站 Token 用量 <span>查看调用与模型消耗 →</span></RouterLink>
      <div class="stats-row">
        <div class="stat-card">
          <span class="stat-card__num">{{ status.totalUsers }}</span>
          <span class="stat-card__label">总用户</span>
        </div>
        <div class="stat-card">
          <span class="stat-card__num">{{ status.totalSessions }}</span>
          <span class="stat-card__label">总面试</span>
        </div>
        <div class="stat-card">
          <span class="stat-card__num">{{ status.usersWithKey }}</span>
          <span class="stat-card__label">已配置Key</span>
        </div>
        <div class="stat-card" :class="{ 'stat-card--ok': status.hasSystemKey, 'stat-card--warn': !status.hasSystemKey }">
          <span class="stat-card__num">{{ status.hasSystemKey ? '已配置' : '未配置' }}</span>
          <span class="stat-card__label">系统Key</span>
        </div>
      </div>

      <!-- User Table -->
      <div class="section">
        <div class="section-head">
          <h3 class="section__title">用户列表 ({{ userTotal }})</h3>
          <input class="search-input" v-model="userKeyword" placeholder="搜索昵称/用户名..." @input="debounceLoadUsers" />
        </div>
        <div class="table-wrap">
          <table class="tbl tbl--users">
            <thead>
              <tr>
                <th>ID</th><th>昵称</th><th>角色</th><th>Key</th><th>知识库</th><th>日配额</th><th>面试</th><th>时间</th><th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="u in users" :key="u.id">
                <td data-label="ID">{{ u.id }}</td>
                <td data-label="昵称" class="tbl__strong">{{ u.nickname }}</td>
                <td data-label="角色"><span class="tag" :class="u.role === '管理员' ? 'tag--admin' : 'tag--user'">{{ u.role }}</span></td>
                <td data-label="Key">{{ u.hasApiKey ? '已配置' : '未配置' }}</td>
                <td data-label="知识库">
                  <span class="tag" :class="u.hasApiKey || u.role === '管理员' || u.knowledgeBaseEnabled ? 'tag--ok' : 'tag--warn'">
                    {{ u.hasApiKey || u.role === '管理员' ? '自动开放' : (u.knowledgeBaseEnabled ? '已开放' : '未开放') }}
                  </span>
                </td>
                <td data-label="日配额">
                  <span class="quota-cell" @click="startEditQuota(u)" title="点击设置剩余次数">{{ remaining(u) }}</span>
                </td>
                <td data-label="面试">{{ u.interviewCount }}</td>
                <td data-label="时间" class="tbl__time">{{ formatTime(u.createTime) }}</td>
                <td data-label="操作" class="tbl__actions">
                  <button class="mini-btn" @click="startEditQuota(u)">剩余</button>
                  <button class="mini-btn" @click="startEditLimit(u)" :title="'日上限：' + (u.dailyQuota ?? 10)">上限</button>
                  <button class="mini-btn" @click="toggleKnowledgeBase(u)" :disabled="u.hasApiKey || u.role === '管理员'">
                    {{ u.knowledgeBaseEnabled ? '关知识库' : '开知识库' }}
                  </button>
                  <button class="mini-btn" @click="toggleAdmin(u)">{{ u.role === '管理员' ? '降级' : '升管' }}</button>
                  <button class="mini-btn mini-btn--danger" @click="deleteUser(u)">删除</button>
                </td>
              </tr>
              <tr v-if="users.length === 0"><td colspan="9" class="tbl__empty">暂无用户</td></tr>
            </tbody>
          </table>
        </div>
        <div class="pager" v-if="userTotal > userPageSize">
          <button :disabled="userPage <= 1" @click="userPage--; loadUsers()">上一页</button>
          <span>{{ userPage }} / {{ Math.ceil(userTotal / userPageSize) }}</span>
          <button :disabled="userPage >= Math.ceil(userTotal / userPageSize)" @click="userPage++; loadUsers()">下一页</button>
        </div>
      </div>

      <!-- Sessions Table -->
      <div class="section">
        <div class="section-head">
          <h3 class="section__title">面试记录 ({{ sessionTotal }})</h3>
          <input class="search-input" v-model="sessionKeyword" placeholder="搜索岗位..." @input="debounceLoadSessions" />
        </div>
        <div class="table-wrap">
          <table class="tbl">
            <thead>
              <tr>
                <th>ID</th><th>用户</th><th>岗位</th><th>分数</th><th>状态</th><th>时间</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="s in sessions" :key="s.id">
                <td data-label="ID">{{ s.id }}</td>
                <td data-label="用户" class="tbl__strong">{{ s.userName }}</td>
                <td data-label="岗位">{{ s.position }}</td>
                <td data-label="分数">{{ s.score ?? '-' }}</td>
                <td data-label="状态"><span class="tag" :class="s.status === '已结束' ? 'tag--ok' : 'tag--warn'">{{ s.status }}</span></td>
                <td data-label="时间" class="tbl__time">{{ formatTime(s.createTime) }}</td>
              </tr>
              <tr v-if="sessions.length === 0"><td colspan="6" class="tbl__empty">暂无记录</td></tr>
            </tbody>
          </table>
        </div>
        <div class="pager" v-if="sessionTotal > sessionPageSize">
          <button :disabled="sessionPage <= 1" @click="sessionPage--; loadSessions()">上一页</button>
          <span>{{ sessionPage }} / {{ Math.ceil(sessionTotal / sessionPageSize) }}</span>
          <button :disabled="sessionPage >= Math.ceil(sessionTotal / sessionPageSize)" @click="sessionPage++; loadSessions()">下一页</button>
        </div>
      </div>

      <!-- Announcements -->
      <div class="section">
        <div class="section-head">
          <h3 class="section__title">公告管理</h3>
          <button class="section-action" @click="showAnnEditor = true; editingAnn = null">新建公告</button>
        </div>
        <div class="table-wrap">
          <table class="tbl">
            <thead>
              <tr><th>ID</th><th>标题</th><th>状态</th><th>时间</th><th>操作</th></tr>
            </thead>
            <tbody>
              <tr v-for="a in announcements" :key="a.id">
                <td data-label="ID">{{ a.id }}</td>
                <td data-label="标题" class="tbl__strong">{{ a.title }}</td>
                <td data-label="状态"><span class="tag" :class="a.isPublished ? 'tag--ok' : 'tag--warn'">{{ a.isPublished ? '已发布' : '已下架' }}</span></td>
                <td data-label="时间" class="tbl__time">{{ formatTime(a.createTime) }}</td>
                <td data-label="操作" class="tbl__actions">
                  <button class="mini-btn" @click="editAnnouncement(a)">编辑</button>
                  <button class="mini-btn" @click="toggleAnnPublish(a)">{{ a.isPublished ? '下架' : '发布' }}</button>
                  <button class="mini-btn mini-btn--danger" @click="deleteAnnouncement(a)">删除</button>
                </td>
              </tr>
              <tr v-if="announcements.length === 0"><td colspan="5" class="tbl__empty">暂无公告</td></tr>
            </tbody>
          </table>
        </div>
      </div>

      <!-- Announcement editor modal -->
      <div class="modal-overlay" v-if="showAnnEditor" @click.self="showAnnEditor = false">
        <div class="modal-card modal-card--wide animate-scale-in">
          <span class="modal-title">{{ editingAnn ? '编辑公告' : '新建公告' }}</span>
          <input class="modal-input modal-input--compact" v-model="annForm.title" placeholder="公告标题" />
          <textarea class="modal-input modal-input--textarea" v-model="annForm.content" placeholder="公告内容（支持Markdown/HTML/图片链接）" rows="10"></textarea>
          <div class="modal-actions">
            <button class="btn btn--ghost" @click="showAnnEditor = false">取消</button>
            <button class="btn btn--dark" @click="saveAnnouncement">保存</button>
          </div>
        </div>
      </div>

      <!-- Quota edit modal -->
      <div class="modal-overlay" v-if="editingQuota" @click.self="editingQuota = null">
        <div class="modal-card animate-scale-in">
          <span class="modal-title">{{ editingQuota.mode === 'remaining' ? '设置剩余次数' : '设置每日上限' }} — {{ editingQuota.user.nickname }}</span>
          <p class="modal-hint" v-if="editingQuota.mode === 'remaining'">日配额上限 {{ editingQuota.user.dailyQuota ?? 10 }} 次</p>
          <input class="modal-input" v-model="editingQuota.value" type="number" min="0" @keydown.enter="saveQuota" />
          <div class="modal-actions">
            <button class="btn btn--ghost" @click="editingQuota = null">取消</button>
            <button class="btn btn--dark" @click="saveQuota">保存</button>
          </div>
        </div>
      </div>

      <!-- Actions -->
      <div class="section">
        <h3 class="section__title">操作</h3>
        <div class="actions">
          <button class="act-btn act-btn--danger" @click="clearSessions">清空面试记录</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { get, post, put, del } from '@/utils/request'

interface Status { totalUsers: number; totalSessions: number; hasSystemKey: boolean; usersWithKey: number }
interface UserRow { id: number; nickname: string; role: string; hasApiKey: boolean; knowledgeBaseEnabled: boolean; dailyQuota: number; quotaUsed: number; createTime: string; interviewCount: number }
interface SessionRow { id: number; userName: string; position: string; score: number | null; status: string; createTime: string }

const router = useRouter()
const userStore = useUserStore()

const status = ref<Status>({ totalUsers: 0, totalSessions: 0, hasSystemKey: false, usersWithKey: 0 })
const users = ref<UserRow[]>([])
const sessions = ref<SessionRow[]>([])
const userTotal = ref(0); const userPage = ref(1); const userPageSize = 20; const userKeyword = ref('')
const sessionTotal = ref(0); const sessionPage = ref(1); const sessionPageSize = 20; const sessionKeyword = ref('')
let userTimer: any = null, sessionTimer: any = null

function debounceLoadUsers() {
  clearTimeout(userTimer); userTimer = setTimeout(() => { userPage.value = 1; loadUsers() }, 300)
}
function debounceLoadSessions() {
  clearTimeout(sessionTimer); sessionTimer = setTimeout(() => { sessionPage.value = 1; loadSessions() }, 300)
}

async function loadUsers() {
  const res = await get<any>(`/api/admin/users?page=${userPage.value}&pageSize=${userPageSize}&keyword=${encodeURIComponent(userKeyword.value)}`)
  if (res.data) { users.value = res.data.list || []; userTotal.value = res.data.total || 0 }
}
async function loadSessions() {
  const res = await get<any>(`/api/admin/sessions?page=${sessionPage.value}&pageSize=${sessionPageSize}&keyword=${encodeURIComponent(sessionKeyword.value)}`)
  if (res.data) { sessions.value = res.data.list || []; sessionTotal.value = res.data.total || 0 }
}

onMounted(async () => {
  if (!userStore.isAdmin) {
    router.replace('/profile')
    return
  }
  try {
    const [s, u, ss] = await Promise.all([
      get<Status>('/api/admin/status'),
      get<any>(`/api/admin/users?page=1&pageSize=${userPageSize}`),
      get<any>(`/api/admin/sessions?page=1&pageSize=${sessionPageSize}`)
    ])
    if (s.data) status.value = s.data
    if (u.data) { users.value = u.data.list || []; userTotal.value = u.data.total || 0 }
    if (ss.data) { sessions.value = ss.data.list || []; sessionTotal.value = ss.data.total || 0 }
    loadAnnouncements()
  } catch {
    router.replace('/profile')
  }
})

interface AnnRow { id: number; title: string; content: string; isPublished: number; createTime: string }
const announcements = ref<AnnRow[]>([])
const showAnnEditor = ref(false)
const editingAnn = ref<AnnRow | null>(null)
const annForm = ref({ title: '', content: '' })

async function loadAnnouncements() {
  try { const r = await get<AnnRow[]>('/api/admin/announcements'); if (r.data) announcements.value = r.data } catch {}
}
function editAnnouncement(a: AnnRow) { editingAnn.value = a; annForm.value = { title: a.title, content: a.content }; showAnnEditor.value = true }
async function saveAnnouncement() {
  if (!annForm.value.title.trim()) return
  if (editingAnn.value) {
    await put(`/api/admin/announcement/${editingAnn.value.id}`, annForm.value)
  } else {
    await post('/api/admin/announcement', annForm.value)
  }
  showAnnEditor.value = false
  await loadAnnouncements()
}
async function toggleAnnPublish(a: AnnRow) {
  await post(`/api/admin/announcement/${a.id}/publish`)
  a.isPublished = a.isPublished ? 0 : 1
}
async function deleteAnnouncement(a: AnnRow) {
  if (!confirm(`确定删除公告「${a.title}」？`)) return
  await del(`/api/admin/announcement/${a.id}`)
  announcements.value = announcements.value.filter(x => x.id !== a.id)
}

function formatTime(t: string): string {
  if (!t) return '-'
  return t.replace('T', ' ').substring(0, 19)
}

const editingQuota = ref<{ user: UserRow; value: string; mode: 'remaining' | 'limit' } | null>(null)

function remaining(u: UserRow): number {
  return Math.max(0, (u.dailyQuota ?? 10) - (u.quotaUsed ?? 0))
}

function startEditQuota(u: UserRow) {
  editingQuota.value = { user: u, value: String(remaining(u)), mode: 'remaining' }
}

function startEditLimit(u: UserRow) {
  editingQuota.value = { user: u, value: String(u.dailyQuota ?? 10), mode: 'limit' }
}

async function saveQuota() {
  if (!editingQuota.value) return
  const { user, value, mode } = editingQuota.value
  if (!/^\d+$/.test(value)) return
  const num = parseInt(value)
  if (mode === 'remaining') {
    await post('/api/admin/set-quota', { userId: user.id, remaining: num })
    user.quotaUsed = Math.max(0, (user.dailyQuota ?? 10) - num)
  } else {
    await post('/api/admin/set-limit', { userId: user.id, limit: num })
    user.dailyQuota = num
  }
  editingQuota.value = null
}

async function toggleAdmin(u: UserRow) {
  if (!confirm(`确定${u.role === '管理员' ? '取消' : '设为'}管理员：${u.nickname}？`)) return
  await post('/api/admin/toggle-admin', { userId: u.id })
  u.role = u.role === '管理员' ? '普通用户' : '管理员'
}

async function toggleKnowledgeBase(u: UserRow) {
  if (u.hasApiKey || u.role === '管理员') return
  await post<{ enabled: boolean }>('/api/admin/toggle-knowledge-base', { userId: u.id })
  u.knowledgeBaseEnabled = !u.knowledgeBaseEnabled
}

async function deleteUser(u: UserRow) {
  if (!confirm(`确定删除用户：${u.nickname}？此操作不可撤销！`)) return
  await post('/api/admin/delete-user', { userId: u.id })
  await loadUsers()
  const s = await get<Status>('/api/admin/status'); if (s.data) status.value = s.data
}

async function clearSessions() {
  if (!confirm('确定清空所有面试记录？')) return
  await post('/api/admin/clear-sessions')
  status.value.totalSessions = 0
  sessionTotal.value = 0; sessions.value = []
  await loadSessions()
}
</script>

<style scoped>
.usage-entry { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 8px; min-height: 54px; margin-bottom: 20px; padding: 14px 18px; border: 1px solid var(--border-medium); border-radius: 10px; background: var(--bg-paper); font-size: 14px; }
.usage-entry span { font-size: 12px; color: var(--text-muted); }
.usage-entry:focus-visible { outline: 2px solid #167d8d; outline-offset: 3px; }
.page {
  min-height: 100vh;
  background: var(--bg-canvas);
}

.page__inner {
  max-width: 920px;
  margin: 0 auto;
  padding: 0 20px 36px;
}

.page-head {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 0 22px;
}

.back-btn {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--text-main);
  flex-shrink: 0;
  transition: background var(--duration-fast), transform var(--duration-fast);
}

.back-btn:hover {
  background: rgba(20,20,19,0.06);
  transform: translateX(-1px);
}

.page-head__title {
  font-family: var(--font-serif);
  font-size: 20px;
  font-weight: 600;
  flex: 1;
  line-height: 1.2;
}

.page-head__role,
.tag {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  white-space: nowrap;
  border-radius: var(--radius-full);
  font-weight: 600;
}

.page-head__role {
  min-height: 28px;
  padding: 4px 12px;
  font-size: 12px;
  background: rgba(217,117,10,0.1);
  color: var(--accent);
}

.stats-row {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 28px;
}

.stat-card {
  position: relative;
  min-height: 96px;
  overflow: hidden;
  background: var(--bg-paper);
  border: 1px solid var(--border-light);
  border-radius: var(--radius-lg);
  padding: 16px 16px 15px;
  box-shadow: var(--shadow-sm);
}

.stat-card::before {
  content: "";
  position: absolute;
  inset: 0 auto 0 0;
  width: 4px;
  background: rgba(20,20,19,0.12);
}

.stat-card__num {
  display: block;
  margin-bottom: 6px;
  font-size: 24px;
  font-weight: 700;
  line-height: 1;
  letter-spacing: 0;
}

.stat-card__label {
  font-size: 12px;
  color: var(--text-light);
}

.stat-card--ok::before { background: var(--color-success); }
.stat-card--warn::before { background: var(--color-danger); }
.stat-card--ok .stat-card__num { color: var(--color-success); }
.stat-card--warn .stat-card__num { color: var(--color-danger); }

.section {
  margin-bottom: 24px;
}

.section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  margin-bottom: 12px;
}

.section__title {
  margin: 0;
  font-family: var(--font-serif);
  font-size: 16px;
  font-weight: 600;
  line-height: 1.3;
}

.section-action {
  min-height: 34px;
  padding: 0 15px;
  border-radius: var(--radius-full);
  background: var(--bg-dark);
  color: #fff;
  font-size: 13px;
  font-weight: 600;
  transition: opacity var(--duration-fast), transform var(--duration-fast);
}

.section-action:hover {
  opacity: 0.88;
  transform: translateY(-1px);
}

.search-input {
  width: min(240px, 100%);
  min-height: 36px;
  padding: 0 12px;
  border: 1px solid var(--border-medium);
  border-radius: var(--radius-md);
  outline: none;
  background: var(--bg-paper);
  color: var(--text-main);
  font-size: 13px;
}

.search-input:focus {
  border-color: var(--accent);
  box-shadow: 0 0 0 3px rgba(217,117,10,0.12);
}

.search-input::placeholder {
  color: var(--text-light);
}

.table-wrap {
  overflow-x: auto;
  background: var(--bg-paper);
  border: 1px solid var(--border-light);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-sm);
}

.tbl {
  width: 100%;
  border-collapse: collapse;
  table-layout: fixed;
  font-size: 13px;
}

.tbl--users {
  min-width: 880px;
}

.tbl th {
  padding: 11px 14px;
  border-bottom: 1px solid var(--border-light);
  background: var(--bg-surface);
  color: var(--text-light);
  font-size: 11px;
  font-weight: 700;
  text-align: left;
}

.tbl td {
  padding: 12px 14px;
  border-bottom: 1px solid var(--border-light);
  color: var(--text-muted);
  vertical-align: middle;
}

.tbl tr:last-child td {
  border-bottom: none;
}

.tbl tbody tr {
  transition: background var(--duration-fast);
}

.tbl tbody tr:hover {
  background: rgba(217,117,10,0.035);
}

.tbl__strong {
  color: var(--text-main);
  font-weight: 600;
}

.tbl__time {
  color: var(--text-light);
  font-size: 12px;
  white-space: nowrap;
}

.tbl__empty {
  padding: 34px !important;
  color: var(--text-light);
  text-align: center;
}

.tbl--users th:nth-child(1),
.tbl--users td:nth-child(1) { width: 46px; }
.tbl--users th:nth-child(2),
.tbl--users td:nth-child(2) { width: 106px; }
.tbl--users th:nth-child(3),
.tbl--users td:nth-child(3) { width: 74px; }
.tbl--users th:nth-child(4),
.tbl--users td:nth-child(4) { width: 82px; }
.tbl--users th:nth-child(5),
.tbl--users td:nth-child(5) { width: 90px; }
.tbl--users th:nth-child(6),
.tbl--users td:nth-child(6),
.tbl--users th:nth-child(7),
.tbl--users td:nth-child(7) { width: 64px; }
.tbl--users th:nth-child(8),
.tbl--users td:nth-child(8) { width: 142px; }
.tbl--users th:nth-child(9),
.tbl--users td:nth-child(9) { width: 210px; }

.tag {
  min-height: 22px;
  padding: 2px 8px;
  font-size: 11px;
}

.tag--admin {
  background: rgba(217,117,10,0.1);
  color: var(--accent);
}

.tag--user {
  background: var(--bg-surface);
  color: var(--text-muted);
}

.tag--ok {
  background: rgba(34,197,94,0.1);
  color: var(--color-success);
}

.tag--warn {
  background: rgba(217,117,10,0.1);
  color: var(--accent);
}

.quota-cell {
  display: inline-flex;
  min-width: 32px;
  min-height: 28px;
  align-items: center;
  justify-content: center;
  border: 1px dashed var(--border-medium);
  border-radius: var(--radius-sm);
  cursor: pointer;
  color: var(--text-main);
  font-weight: 600;
}

.quota-cell:hover {
  border-color: var(--accent);
  color: var(--accent);
  background: rgba(217,117,10,0.06);
}

.tbl__actions {
  white-space: normal;
}

.tbl__actions .mini-btn {
  margin: 3px 3px 3px 0;
}

.mini-btn {
  min-height: 28px;
  padding: 0 9px;
  border: 1px solid var(--border-light);
  border-radius: var(--radius-sm);
  background: var(--bg-surface);
  color: var(--text-muted);
  font-size: 11px;
  transition: background var(--duration-fast), border-color var(--duration-fast), color var(--duration-fast);
}

.mini-btn:hover:not(:disabled) {
  border-color: var(--accent);
  background: rgba(217,117,10,0.06);
  color: var(--accent);
}

.mini-btn:disabled {
  cursor: not-allowed;
  opacity: 0.45;
}

.mini-btn--danger:hover:not(:disabled) {
  border-color: var(--color-danger);
  background: rgba(239,68,68,0.06);
  color: var(--color-danger);
}

.actions {
  display: flex;
  gap: 10px;
}

.act-btn {
  min-height: 38px;
  padding: 0 18px;
  border-radius: var(--radius-full);
  font-size: 13px;
  font-weight: 600;
  transition: opacity var(--duration-fast), transform var(--duration-fast);
}

.act-btn:hover {
  opacity: 0.88;
  transform: translateY(-1px);
}

.act-btn--danger {
  background: var(--color-danger);
  color: #fff;
}

.modal-overlay {
  position: fixed;
  inset: 0;
  z-index: 200;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
  background: rgba(20,20,19,0.5);
  backdrop-filter: blur(4px);
}

.modal-card {
  width: min(360px, 100%);
  max-height: calc(100vh - 40px);
  overflow-y: auto;
  padding: 28px 24px;
  border-radius: var(--radius-xl);
  background: var(--bg-paper);
  box-shadow: var(--shadow-xl);
}

.modal-card--wide {
  width: min(560px, 100%);
}

.modal-title {
  display: block;
  margin-bottom: 16px;
  font-family: var(--font-serif);
  font-size: 18px;
  font-weight: 700;
}

.modal-hint {
  margin-bottom: 12px;
  color: var(--text-light);
  font-size: 13px;
}

.modal-input {
  width: 100%;
  min-height: 44px;
  margin-bottom: 16px;
  padding: 12px 14px;
  border: 1px solid var(--border-light);
  border-radius: var(--radius-md);
  outline: none;
  background: var(--bg-surface);
  color: var(--text-main);
  font-family: inherit;
  font-size: 15px;
}

.modal-input--compact {
  margin-bottom: 8px;
}

.modal-input--textarea {
  resize: vertical;
  font-family: var(--font-mono);
  font-size: 13px;
  line-height: 1.6;
}

.modal-input:focus {
  border-color: var(--accent);
  box-shadow: 0 0 0 3px rgba(217,117,10,0.12);
}

.modal-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}

.modal-actions .btn {
  min-height: 42px;
  padding: 0 22px;
  border-radius: var(--radius-full);
  font-size: 14px;
  font-weight: 600;
}

.modal-actions .btn--ghost {
  background: var(--bg-surface);
  color: var(--text-muted);
}

.modal-actions .btn--dark {
  background: var(--bg-dark);
  color: #fff;
}

.pager {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  margin-top: 14px;
  color: var(--text-muted);
  font-size: 13px;
}

.pager button {
  min-height: 34px;
  padding: 0 14px;
  border: 1px solid var(--border-medium);
  border-radius: var(--radius-sm);
  background: var(--bg-paper);
  color: var(--text-main);
  font-size: 13px;
}

.pager button:hover:not(:disabled) {
  border-color: var(--accent);
  color: var(--accent);
}

.pager button:disabled {
  cursor: not-allowed;
  opacity: 0.35;
}

@media (max-width: 860px) {
  .stats-row {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 760px) {
  .page__inner {
    padding: 0 16px 32px;
  }

  .page-head {
    padding: 8px 0 18px;
  }

  .back-btn {
    width: 40px;
    height: 40px;
  }

  .page-head__title {
    font-size: 20px;
  }

  .stats-row {
    gap: 10px;
    margin-bottom: 24px;
  }

  .stat-card {
    min-height: 88px;
    padding: 14px 14px 13px;
  }

  .stat-card__num {
    font-size: 22px;
  }

  .section {
    margin-bottom: 22px;
  }

  .section-head {
    align-items: stretch;
    flex-direction: column;
  }

  .search-input {
    width: 100%;
    min-height: 40px;
  }

  .table-wrap {
    overflow: visible;
    border: none;
    border-radius: 0;
    background: transparent;
    box-shadow: none;
  }

  .tbl,
  .tbl--users {
    min-width: 0;
  }

  .tbl thead {
    display: none;
  }

  .tbl,
  .tbl tbody,
  .tbl tr,
  .tbl td {
    display: block;
    width: 100% !important;
  }

  .tbl tr {
    margin-bottom: 10px;
    overflow: hidden;
    border: 1px solid var(--border-light);
    border-radius: var(--radius-lg);
    background: var(--bg-paper);
    box-shadow: var(--shadow-sm);
  }

  .tbl tbody tr:hover {
    background: var(--bg-paper);
  }

  .tbl td {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 14px;
    min-height: 44px;
    padding: 10px 14px;
  }

  .tbl td::before {
    content: attr(data-label);
    flex: 0 0 auto;
    color: var(--text-light);
    font-size: 12px;
    font-weight: 700;
  }

  .tbl__actions {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
    justify-content: flex-end;
  }

  .tbl__actions .mini-btn {
    margin: 0;
  }

  .tbl__empty {
    display: block;
  }

  .tbl__empty::before {
    content: none;
  }

  .mini-btn {
    min-height: 34px;
  }
}

@media (max-width: 420px) {
  .modal-actions {
    flex-direction: column-reverse;
  }

  .modal-actions .btn {
    width: 100%;
  }
}
</style>
