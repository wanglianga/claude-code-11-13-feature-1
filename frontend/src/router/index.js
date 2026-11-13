import { createRouter, createWebHashHistory } from 'vue-router'

const routes = [
  { path: '/login', name: 'login', component: () => import('../views/LoginView.vue') },
  { path: '/', redirect: '/subjects' },
  { path: '/subjects', name: 'subjects', component: () => import('../views/SubjectView.vue') },
  { path: '/tickets', name: 'tickets', component: () => import('../views/TicketListView.vue') },
  { path: '/tickets/:id', name: 'ticket-detail', component: () => import('../views/TicketDetailView.vue'), props: true },
  { path: '/holds', name: 'holds', component: () => import('../views/HoldView.vue') },
  { path: '/lineage', name: 'lineage', component: () => import('../views/LineageView.vue') }
]

const router = createRouter({ history: createWebHashHistory(), routes })

router.beforeEach((to) => {
  const authed = !!localStorage.getItem('rtbf-auth')
  if (!authed && to.name !== 'login') {
    return { name: 'login' }
  }
})

export default router
