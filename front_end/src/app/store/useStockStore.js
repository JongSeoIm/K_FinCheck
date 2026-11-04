import { create } from 'zustand'
import { apiFetch, goToLogin } from '@/app/lib/api'

// 관심 종목은 로그인한 계정의 DB 목록을 그대로 따릅니다. (브라우저 저장은 더 이상 쓰지 않습니다.)
const useStockStore = create((set, get) => ({
  watchlist: [],
  prices: {},
  user: null, // { userId, loginId, nickname } 또는 null
  authReady: false,

  // 서버가 보낸 최신 시세 (종목코드 → 시세). Polling·Push 가 같은 곳에 넣습니다.
  quotes: {},
  feedMode: 'polling', // 'polling' | 'push'
  feedStatus: 'connecting', // 'connecting' | 'connected' | 'stale' | 'disconnected'

  setFeedMode: (mode) => {
    try {
      window.localStorage.setItem('kfc-feed-mode', mode)
    } catch {
      // 저장이 막힌 환경에서는 이번 접속에서만 유지합니다.
    }
    set({ feedMode: mode, feedStatus: 'connecting' })
  },
  setFeedStatus: (status) => set((state) => (state.feedStatus === status ? state : { feedStatus: status })),

  // seq 가 이미 받은 값보다 크지 않으면 중복·역순 메시지로 보고 버립니다.
  setQuotes: (incoming) => {
    if (!incoming) return
    set((state) => {
      let next = null
      for (const [code, quote] of Object.entries(incoming)) {
        const current = state.quotes[code]
        if (!current || quote.seq > current.seq) {
          if (!next) next = { ...state.quotes }
          next[code] = quote
        }
      }
      return next ? { quotes: next } : state
    })
  },

  // 화면이 열릴 때 로그인 상태를 확인하고, 로그인돼 있으면 관심 종목을 불러옵니다.
  loadSession: async () => {
    const me = await apiFetch('/api/auth/me')
    if (me.ok && me.data?.authenticated) {
      set({
        user: { userId: me.data.userId, loginId: me.data.loginId, nickname: me.data.nickname },
        authReady: true,
      })
      const list = await apiFetch('/api/watchlist')
      set({ watchlist: list.ok && Array.isArray(list.data) ? list.data : [] })
    } else {
      set({ user: null, watchlist: [], authReady: true })
    }
  },

  logout: async () => {
    await apiFetch('/api/auth/logout', { method: 'POST' })
    // 로그아웃하면 화면의 목록도 비웁니다.
    set({ user: null, watchlist: [] })
  },

  addToWatchlist: async (symbol) => {
    if (!get().authReady) await get().loadSession()
    if (!get().user) {
      goToLogin()
      return { ok: false, error: '로그인이 필요합니다.' }
    }
    const result = await apiFetch('/api/watchlist', {
      method: 'POST',
      body: { stockCode: symbol.toUpperCase() },
    })
    if (result.status === 401) {
      set({ user: null, watchlist: [] })
      goToLogin()
      return { ok: false, error: '로그인이 필요합니다.' }
    }
    if (!result.ok) {
      return { ok: false, error: result.data?.error || '관심 종목을 저장하지 못했습니다.' }
    }
    set({ watchlist: result.data })
    return { ok: true }
  },

  removeFromWatchlist: async (symbol) => {
    if (!get().user) return { ok: false, error: '로그인이 필요합니다.' }
    const result = await apiFetch(`/api/watchlist/${encodeURIComponent(symbol)}`, { method: 'DELETE' })
    if (result.status === 401) {
      set({ user: null, watchlist: [] })
      return { ok: false, error: '로그인이 필요합니다.' }
    }
    if (!result.ok) {
      return { ok: false, error: result.data?.error || '관심 종목을 삭제하지 못했습니다.' }
    }
    set({ watchlist: result.data })
    return { ok: true }
  },

  setPrice: (symbol, price) => {
    set((state) => ({
      prices: { ...state.prices, [symbol]: price },
    }))
  },
}))

export default useStockStore
