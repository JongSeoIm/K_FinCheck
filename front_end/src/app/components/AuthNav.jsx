'use client'

import Link from 'next/link'
import { useState } from 'react'
import useStockStore from '@/app/store/useStockStore'

// 상단 계정 메뉴. symbol: 로그인 후 돌아올 종목
export default function AuthNav({ symbol }) {
  const user = useStockStore((state) => state.user)
  const accountStatus = useStockStore((state) => state.accountStatus)
  const signOut = useStockStore((state) => state.signOut)
  const [pending, setPending] = useState(false)
  const [failed, setFailed] = useState(false)

  const handleLogout = async () => {
    setPending(true)
    setFailed(false)
    try {
      await signOut()
    } catch {
      setFailed(true)
    } finally {
      setPending(false)
    }
  }

  if (accountStatus === 'loading') return <div className='auth-nav' aria-hidden='true' />

  if (user) {
    return (
      <div className='auth-nav'>
        <span>{user.nickname}님</span>
        <button type='button' onClick={handleLogout} disabled={pending}>로그아웃</button>
        {failed && <small role='alert'>로그아웃하지 못했습니다.</small>}
      </div>
    )
  }

  return (
    <nav className='auth-nav' aria-label='계정'>
      <Link href={`/login?symbol=${symbol}`}>로그인</Link>
      <Link href={`/signup?symbol=${symbol}`}>회원가입</Link>
    </nav>
  )
}
