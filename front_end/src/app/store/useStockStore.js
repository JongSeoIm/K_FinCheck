import { create } from 'zustand'
import { persist } from 'zustand/middleware'
import { addWatchlist, fetchMe, fetchWatchlist, logout, removeWatchlist } from '../lib/accountApi'

// 화면 전체가 같이 쓰는 상태
// - user      : 로그인한 사용자 (없으면 null). 서버 세션이 기준
// - accountStatus: loading | ready | error (로그인 상태·관심 종목 불러오기)
// - watchlist : 로그인한 계정의 관심 종목 코드 (서버 DB)
// - stocks    : 지원 종목 목록 (서버 DB)
// - quotes    : 종목별 최신 시세 (WebSocket push 또는 polling)
// - feedMode  : 시세 전달 방식 (push | poll)
// - connection: 연결 상태 배지용
const useStockStore = create(
  persist(
    (set, get) => ({
      user: null,
      accountStatus: 'loading',
      watchlist: [],
      stocks: [],
      stocksError: null,
      quotes: {},
      barUpdate: null, // 서버가 push 한 최신 봉 { code, bars }
      feedMode: 'push', // push = WebSocket 실시간 / poll = 2초마다 조회 (사용자가 선택, 브라우저에 저장)
      connection: {
        state: 'connecting', // connecting | live | server-polling | polling | manual-polling | disconnected | not-configured
        lastUpdated: null,
        marketOpen: null,
      },

      // 화면을 열 때·로그인 후: 서버 세션을 확인하고 그 계정의 관심 종목을 받아 옵니다
      loadAccount: async () => {
        set({ accountStatus: 'loading' })
        try {
          const user = await fetchMe()
          const watchlist = user ? await fetchWatchlist() : []
          set({ user, watchlist, accountStatus: 'ready' })
        } catch {
          set({ user: null, watchlist: [], accountStatus: 'error' })
        }
      },

      signOut: async () => {
        await logout()
        set({ user: null, watchlist: [], accountStatus: 'ready' })
      },

      changeWatchlist: async (change, code) => {
        set({ watchlist: await change(code) })
      },

      addToWatchlist: (code) => get().changeWatchlist(addWatchlist, code),

      removeFromWatchlist: (code) => get().changeWatchlist(removeWatchlist, code),

      setFeedMode: (feedMode) => set({ feedMode }),

      setBarUpdate: (code, bars) => set({ barUpdate: { code, bars } }),

      setStocks: (stocks, stocksError = null) => set({ stocks, stocksError }),

      setQuote: (code, quote) => {
        set((state) => ({
          quotes: { ...state.quotes, [code]: { ...state.quotes[code], ...quote } },
          connection: { ...state.connection, lastUpdated: Date.now() },
        }))
      },

      // 서버 연결이 끊기면 마지막 시세를 지워서 각 화면이 빈 상태 안내("—", "연결 끊김")를 보여 주게 합니다
      setConnection: (patch) => {
        set((state) => ({
          connection: { ...state.connection, ...patch },
          ...(patch.state === 'disconnected' ? { quotes: {} } : {}),
        }))
      },
    }),
    {
      name: 'stock-dashboard',
      partialize: (state) => ({ feedMode: state.feedMode }),
      // 예전에 브라우저에 저장된 관심 종목은 읽지 않습니다
      merge: (saved, current) => ({ ...current, feedMode: saved?.feedMode ?? current.feedMode }),
    }
  )
)

// 어떤 요청에서든 세션 만료를 알게 되면 로그아웃 상태로 돌립니다
if (typeof window !== 'undefined') {
  window.addEventListener('session-expired', () => {
    useStockStore.setState({ user: null, watchlist: [], accountStatus: 'ready' })
  })
}

export default useStockStore