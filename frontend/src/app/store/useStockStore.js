import { create } from 'zustand'
import { persist } from 'zustand/middleware'

const MAX_TICKS = 600

const useStockStore = create(
  persist(
    (set, get) => ({
      // 브라우저에 저장되는 값
      watchlist: [], // [{ symbol, name }]
      feedMode: 'push', // 'push' | 'poll'

      // 화면에서만 쓰는 값
      quotes: {}, // { symbol: quote }
      ticks: {}, // { symbol: [{ time, price }] } 실시간 탭 차트용

      addToWatchlist: (stock) => {
        const { watchlist } = get()
        if (watchlist.some((item) => item.symbol === stock.symbol)) return
        set({ watchlist: [...watchlist, { symbol: stock.symbol, name: stock.name }] })
      },

      removeFromWatchlist: (symbol) => {
        set((state) => ({
          watchlist: state.watchlist.filter((item) => item.symbol !== symbol),
        }))
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
      partialize: (state) => ({ watchlist: state.watchlist, feedMode: state.feedMode }),
    }
  )
)

export default useStockStore
