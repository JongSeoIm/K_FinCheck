import { create } from 'zustand'
import { accountApi } from '../lib/accountApi'

const useAuthStore = create((set) => ({
  user: null,
  ready: false,
  setUser: user => set({ user, ready: true }),
  refresh: async () => {
    try {
      const user = await accountApi('/api/auth/me')
      set({ user, ready: true })
      return user
    } catch {
      set({ user: null, ready: true })
      return null
    }
  },
}))
export default useAuthStore
