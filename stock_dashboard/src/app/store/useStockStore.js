import { create } from 'zustand'
import { accountApi } from '../lib/accountApi'
import useAuthStore from './useAuthStore'

// 관심 종목은 서버의 계정별 DB 목록을 사용한다. 기존 브라우저 저장 데이터는 읽거나 삭제하지 않는다.
const useStockStore = create((set, get) => ({
  watchlist: [], prices: {}, watchlistError: null, saving: false,
  clearWatchlist: () => set({ watchlist: [], watchlistError: null }),
  loadWatchlist: async () => {
    const userId = useAuthStore.getState().user?.userId
    if (!userId) { get().clearWatchlist(); return }
    try {
      const watchlist = await accountApi('/api/watchlist')
      if (useAuthStore.getState().user?.userId === userId) set({ watchlist, watchlistError: null })
    } catch (error) {
      if (useAuthStore.getState().user?.userId === userId) set({ watchlist: [], watchlistError: error.message })
    }
  },
  changeWatchlist: async (symbol, method) => {
    const userId = useAuthStore.getState().user?.userId
    if (!userId || get().saving) return
    set({ saving: true, watchlistError: null })
    try {
      const watchlist = await accountApi(`/api/watchlist/${encodeURIComponent(symbol)}`, { method })
      if (useAuthStore.getState().user?.userId === userId) set({ watchlist })
    } catch (error) {
      if (useAuthStore.getState().user?.userId === userId) set({ watchlistError: error.message })
    } finally { set({ saving: false }) }
  },
  addToWatchlist: symbol => get().changeWatchlist(symbol, 'POST'),
  removeFromWatchlist: symbol => get().changeWatchlist(symbol, 'DELETE'),
  setPrice: (symbol, price) => set(state => ({ prices: { ...state.prices, [symbol]: price } })),
}))
export default useStockStore
