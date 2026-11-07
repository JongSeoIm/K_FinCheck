import { create } from 'zustand'
import { authApi } from '@/api/authApi'

// 로그인 상태 (브라우저에 저장하지 않는다. 서버 세션이 기준이고 화면을 열 때 /api/auth/me 로 확인)
const useAuthStore = create((set) => ({
  user: null, // { userId, loginId, nickname, createdAt }
  checked: false, // 서버에 로그인 상태를 한 번 확인했는지

  fetchMe: async () => {
    try {
      const user = await authApi.me()
      set({ user, checked: true })
    } catch {
      // 서버에 연결하지 못하면 로그인 안 한 상태로 보여준다
      set({ user: null, checked: true })
    }
  },

  login: async (body) => {
    const user = await authApi.login(body)
    set({ user, checked: true })
    return user
  },

  // 가입만으로는 로그인되지 않는다
  signup: (body) => authApi.signup(body),

  logout: async () => {
    try {
      await authApi.logout()
    } finally {
      set({ user: null, checked: true })
    }
  },
}))

export default useAuthStore
