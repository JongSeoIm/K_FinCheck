'use client'

import { useRouter } from 'next/navigation'
import { FaRegStar, FaStar } from 'react-icons/fa'
import useAuthStore from '../store/useAuthStore'
import useStockStore from '../store/useStockStore'

export default function WatchStar({ symbol, name, returnTo = '/' }) {
  const router = useRouter()
  const user = useAuthStore(state => state.user)
  const ready = useAuthStore(state => state.ready)
  const saved = useStockStore(state => Boolean(user && state.watchlist.includes(symbol)))
  const saving = useStockStore(state => state.saving)
  const label = `${name} 관심 종목`

  function toggle() {
    if (!user) { router.push(`/login?next=${encodeURIComponent(returnTo)}`); return }
    const store = useStockStore.getState()
    if (store.watchlist.includes(symbol)) store.removeFromWatchlist(symbol)
    else store.addToWatchlist(symbol)
  }

  return <button className='watch-star' type='button' aria-label={label} aria-pressed={saved}
    title={`${label} ${saved ? '해제' : '추가'}`} disabled={!ready || saving} onClick={toggle}>
    {saved ? <FaStar aria-hidden='true' /> : <FaRegStar aria-hidden='true' />}
  </button>
}
