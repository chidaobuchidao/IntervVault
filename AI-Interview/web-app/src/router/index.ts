import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '@/stores/user'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/',
      name: 'home',
      component: () => import('@/views/HomeView.vue')
    },
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/LoginView.vue')
    },
    {
      path: '/interview',
      name: 'interview',
      component: () => import('@/views/InterviewView.vue'),
      meta: { requiresAuth: true }
    },
    {
      path: '/interview/report',
      name: 'interview-report',
      component: () => import('@/views/InterviewReportView.vue'),
      meta: { requiresAuth: true }
    },
    {
      path: '/interview/history',
      name: 'interview-history',
      component: () => import('@/views/InterviewHistoryView.vue'),
      meta: { requiresAuth: true }
    },
    {
      path: '/resume/upload',
      name: 'resume-upload',
      component: () => import('@/views/ResumeUploadView.vue'),
      meta: { requiresAuth: true }
    },
    {
      path: '/resume/report',
      name: 'resume-report',
      component: () => import('@/views/ResumeReportView.vue'),
      meta: { requiresAuth: true }
    },
    {
      path: '/resume/history',
      name: 'resume-history',
      component: () => import('@/views/ResumeHistoryView.vue'),
      meta: { requiresAuth: true }
    },
    {
      path: '/questions',
      name: 'questions',
      component: () => import('@/views/QuestionsView.vue'),
      meta: { requiresAuth: true }
    },
    {
      path: '/questions/:id',
      name: 'question-detail',
      component: () => import('@/views/QuestionDetailView.vue'),
      meta: { requiresAuth: true }
    },
    {
      path: '/practice',
      name: 'practice',
      component: () => import('@/views/PracticeView.vue'),
      meta: { requiresAuth: true }
    },
    {
      path: '/practice/do',
      name: 'practice-do',
      component: () => import('@/views/PracticeDoView.vue'),
      meta: { requiresAuth: true }
    },
    {
      path: '/wrong-book',
      name: 'wrong-book',
      component: () => import('@/views/WrongBookView.vue'),
      meta: { requiresAuth: true }
    },
    {
      path: '/profile',
      name: 'profile',
      component: () => import('@/views/ProfileView.vue'),
      meta: { requiresAuth: true }
    },
    {
      path: '/token-usage',
      name: 'token-usage',
      component: () => import('@/views/TokenUsageView.vue'),
      meta: { requiresAuth: true }
    },
    {
      path: '/admin/token-usage',
      name: 'admin-token-usage',
      component: () => import('@/views/TokenUsageView.vue'),
      meta: { requiresAuth: true, requiresAdmin: true }
    },
    {
      path: '/admin',
      name: 'admin',
      component: () => import('@/views/AdminView.vue'),
      meta: { requiresAuth: true, requiresAdmin: true }
    },
    {
      path: '/paper-tools',
      meta: { requiresAuth: true },
      children: [
        { path: 'polish', name: 'Polish', component: () => import('@/views/PolishView.vue') },
        { path: 'ai-reduce', name: 'AiReduce', component: () => import('@/views/AiReduceView.vue') },
        { path: 'plagiarism-reduce', name: 'PlagiarismReduce', component: () => import('@/views/PlagiarismReduceView.vue') },
      ]
    }
  ],
  scrollBehavior() {
    return { top: 0 }
  }
})

router.beforeEach((to, _from, next) => {
  const userStore = useUserStore()
  // Restore persisted token so guard works on direct page loads
  if (!userStore.token) {
    userStore.restoreToken()
  }

  // Already logged in → redirect /login away to home
  if (to.path === '/login' && userStore.token) {
    next('/')
    return
  }

  // Public routes
  if (to.path === '/login' || to.path === '/') {
    next()
    return
  }

  // Auth required
  if (!userStore.token) {
    next('/login')
    return
  }

  // Admin required
  if (to.meta.requiresAdmin && !userStore.isAdmin) {
    next('/profile')
    return
  }

  next()
})

export default router
