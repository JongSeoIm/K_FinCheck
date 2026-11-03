import { create } from 'zustand'
import { persist } from 'zustand/middleware'

// 화면 전체가 같이 쓰는 상태
// - watchlist : 관심 종목 코드 (브라우저 localStorage 에 저장)
// - stocks    : 지원 종목 목록 (서버 DB)
// - quotes    : 종목별 최신 시세 (WebSocket push 또는 polling)
// - feedMode  : 시세 전달 방식 (push | poll)
// - connection: 연결 상태 배지용
const useStockStore = create(
  persist(
    (set, get) => ({
      watchlist: [],
      stocks: [],
      stocksError: null,
      quotes: {},
      feedMode: 'push', // push = WebSocket 실시간 / poll = 2초마다 조회 (사용자가 선택, 브라우저에 저장)
      connection: {
        state: 'connecting', // connecting | live | server-polling | polling | manual-polling | disconnected | not-configured
        lastUpdated: null,
        marketOpen: null,
      },

      addToWatchlist: (code) => {
        const { watchlist } = get()
        if (watchlist.includes(code)) return
        set({ watchlist: [...watchlist, code] })
      },

      removeFromWatchlist: (code) => {
        set((state) => ({ watchlist: state.watchlist.filter((item) => item !== code) }))
      },

      setFeedMode: (feedMode) => set({ feedMode }),

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
      partialize: (state) => ({ watchlist: state.watchlist, feedMode: state.feedMode }),
    }
  )
)

export default useStockStore