import { defineStore } from 'pinia'
import { ref, computed } from 'vue'

interface UserInfo {
  userId: number
  nickname: string
  avatarUrl: string
  token: string
  email?: string
  needBindEmail?: boolean
}

// localStorage keys
const STORAGE_KEYS = {
  TOKEN: 'token',
  USER_ID: 'userId',
  NICKNAME: 'nickname',
  AVATAR_URL: 'avatarUrl',
  IS_ADMIN: 'isAdmin',
  EMAIL: 'email',
  NEED_BIND_EMAIL: 'needBindEmail'
} as const

export const useUserStore = defineStore('user', () => {
  const userId = ref<number>(0)
  const nickname = ref<string>('')
  const avatarUrl = ref<string>('')
  const token = ref<string>('')
  const isAdmin = ref<boolean>(false)
  const email = ref<string>('')
  const needBindEmail = ref<boolean>(false)

  const isLoggedIn = computed(() => !!token.value && userId.value > 0)

  function setUser(info: UserInfo) {
    userId.value = info.userId
    nickname.value = info.nickname
    avatarUrl.value = info.avatarUrl
    token.value = info.token
    email.value = info.email || ''
    needBindEmail.value = info.needBindEmail || false
    localStorage.setItem(STORAGE_KEYS.TOKEN, info.token)
    localStorage.setItem(STORAGE_KEYS.USER_ID, String(info.userId))
    localStorage.setItem(STORAGE_KEYS.NICKNAME, info.nickname)
    localStorage.setItem(STORAGE_KEYS.AVATAR_URL, info.avatarUrl || '')
    localStorage.setItem(STORAGE_KEYS.EMAIL, info.email || '')
    localStorage.setItem(STORAGE_KEYS.NEED_BIND_EMAIL, info.needBindEmail ? '1' : '0')
  }

  function setAdmin(admin: boolean) {
    isAdmin.value = admin
    localStorage.setItem(STORAGE_KEYS.IS_ADMIN, admin ? '1' : '0')
  }

  function clearUser() {
    userId.value = 0
    nickname.value = ''
    avatarUrl.value = ''
    token.value = ''
    isAdmin.value = false
    email.value = ''
    needBindEmail.value = false
    localStorage.removeItem(STORAGE_KEYS.TOKEN)
    localStorage.removeItem(STORAGE_KEYS.USER_ID)
    localStorage.removeItem(STORAGE_KEYS.NICKNAME)
    localStorage.removeItem(STORAGE_KEYS.AVATAR_URL)
    localStorage.removeItem(STORAGE_KEYS.IS_ADMIN)
    localStorage.removeItem(STORAGE_KEYS.EMAIL)
    localStorage.removeItem(STORAGE_KEYS.NEED_BIND_EMAIL)
  }

  function restoreToken() {
    const saved = localStorage.getItem(STORAGE_KEYS.TOKEN)
    if (saved) {
      token.value = saved
      userId.value = Number(localStorage.getItem(STORAGE_KEYS.USER_ID)) || 0
      nickname.value = localStorage.getItem(STORAGE_KEYS.NICKNAME) || ''
      avatarUrl.value = localStorage.getItem(STORAGE_KEYS.AVATAR_URL) || ''
      isAdmin.value = localStorage.getItem(STORAGE_KEYS.IS_ADMIN) === '1'
      email.value = localStorage.getItem(STORAGE_KEYS.EMAIL) || ''
      needBindEmail.value = localStorage.getItem(STORAGE_KEYS.NEED_BIND_EMAIL) === '1'
    }
  }

  return { userId, nickname, avatarUrl, token, isAdmin, email, needBindEmail, isLoggedIn, setUser, setAdmin, clearUser, restoreToken }
})
