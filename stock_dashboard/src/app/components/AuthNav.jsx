'use client'

import { useEffect, useState } from 'react'
import useAuthStore from '../store/useAuthStore'
import useStockStore from '../store/useStockStore'
import { accountApi } from '../lib/accountApi'

export default function AuthNav({ symbol }) {
  const { user, ready, refresh, setUser } = useAuthStore()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  useEffect(() => {
    let active = true
    refresh().then(user => {
      if (!active) return
      if (user) useStockStore.getState().loadWatchlist()
      else useStockStore.getState().clearWatchlist()
    })
    return () => { active = false }
  }, [refresh])
  async function logout() {
    setBusy(true)
    try {
      await accountApi('/api/auth/logout', { method: 'POST' })
      setUser(null)
      useStockStore.getState().clearWatchlist()
      setError('')
    } catch (err) { setError(err.message) }
    finally { setBusy(false) }
  }
  return <nav className='account-nav' aria-label='계정'>
    {error && <span role='alert'>{error}</span>}
    {!ready ? <span>계정 확인 중…</span> : user ? <>
      <button type='button' disabled={busy} onClick={logout}>{busy ? '로그아웃 중…' : '로그아웃'}</button>
    </> : <a href={`/login?next=${encodeURIComponent(`/?symbol=${symbol}`)}`}>로그인</a>}
  </nav>
}
