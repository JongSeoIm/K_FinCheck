import { create } from 'zustand'
import { persist } from 'zustand/middleware'
import { stockApi } from '@/api/stockApi'

const MAX_TICKS = 600

const useStockStore = create(
  persist(
    (set, get) => ({
      // 브라우저에 저장되는 값
      feedMode: 'push', // 'push' | 'poll'

      // 관심 종목: 2차부터 계정별로 서버(DB)에 저장한다
      watchlist: [], // [{ symbol, name, market, regdate }]
      watchlistStatus: 'idle', // 'idle'(로그인 안 함) | 'loading' | 'ready' | 'error'
      watchlistError: '',

      // 화면에서만 쓰는 값
      quotes: {}, // { symbol: quote }
      ticks: {}, // { symbol: [{ time, price }] } 실시간 탭 차트용

      loadWatchlist: async () => {
        set({ watchlistStatus: 'loading', watchlistError: '' })
        try {
          const watchlist = await stockApi.getWatchlist()
          set({ watchlist, watchlistStatus: 'ready' })
        } catch (error) {
          set({ watchlist: [], watchlistStatus: 'error', watchlistError: error.message })
        }
      },

      // 로그아웃하면 목록을 비운다
      clearWatchlist: () => set({ watchlist: [], watchlistStatus: 'idle', watchlistError: '' }),

      // 화면에 먼저 반영하고, 서버 응답(전체 목록)으로 맞춘다. 실패하면 되돌린다
      addToWatchlist: async (stock) => {
        const previous = get().watchlist
        if (previous.some((item) => item.symbol === stock.symbol)) return
        set({ watchlist: [...previous, { symbol: stock.symbol, name: stock.name, market: stock.market }], watchlistError: '' })
        try {
          set({ watchlist: await stockApi.addWatchlist(stock.symbol) })
        } catch (error) {
          set({ watchlist: previous, watchlistError: `관심 종목을 저장하지 못했습니다. ${error.message}` })
        }
      },

      removeFromWatchlist: async (symbol) => {
        const previous = get().watchlist
        set({ watchlist: previous.filter((item) => item.symbol !== symbol), watchlistError: '' })
        try {
          set({ watchlist: await stockApi.removeWatchlist(symbol) })
        } catch (error) {
          set({ watchlist: previous, watchlistError: `관심 종목을 삭제하지 못했습니다. ${error.message}` })
        }
      },

      setFeedMode: (feedMode) => set({ feedMode }),

      // 늦게 도착한 과거 시세가 최신 시세를 덮어쓰지 않도록 서버 수신 시각(receivedAt)을 비교한다
      setQuote: (quote) => {
        if (!quote?.symbol) return
        const receivedMs = Date.parse(quote.receivedAt)
        const current = get().quotes[quote.symbol]
        if (current && Date.parse(current.receivedAt) >= receivedMs) return

        set((state) => {
          const list = state.ticks[quote.symbol] || []
          const last = list[list.length - 1]
          const nextTicks = last && last.time === receivedMs
            ? list
            : [...list, { time: receivedMs, price: Number(quote.price) }].slice(-MAX_TICKS)
          return {
            quotes: { ...state.quotes, [quote.symbol]: quote },
            ticks: { ...state.ticks, [quote.symbol]: nextTicks },
          }
        })
      },
    }),
    {
      name: 'stock-chat-v1',
      // version 1: 관심 종목을 브라우저 저장에서 서버 저장으로 옮겼다. 1차에 저장된 watchlist 는 버린다
      version: 1,
      migrate: (persisted) => ({ feedMode: persisted?.feedMode ?? 'push' }),
      partialize: (state) => ({ feedMode: state.feedMode }),
    }
  )
)

export default useStockStore
