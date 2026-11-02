import { create } from 'zustand'
import { persist } from 'zustand/middleware'

const useStockStore = create(
  persist(
    (set, get) => ({
      watchlist: [],
      prices: {},

      addToWatchlist: (symbol) => {
        const normalizedSymbol = symbol.toUpperCase()
        const { watchlist } = get()
        if (watchlist.includes(normalizedSymbol)) return
        set({ watchlist: [...watchlist, normalizedSymbol] })
      },

      removeFromWatchlist: (symbol) => {
        set((state) => ({
          watchlist: state.watchlist.filter((item) => item !== symbol),
        }))
      },

      setPrice: (symbol, price) => {
        set((state) => ({
          prices: { ...state.prices, [symbol]: price },
        }))
      },
    }),
    {
      name: 'stock-dashboard',
      partialize: (state) => ({ watchlist: state.watchlist }),
    }
  )
)

export default useStockStore
