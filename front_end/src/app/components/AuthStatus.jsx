'use client'

import { useEffect } from 'react'
import useStockStore from '@/app/store/useStockStore'
import { goToLogin } from '@/app/lib/api'

// 화면 상단 왼쪽의 로그인 / 로그아웃 표시
export default function AuthStatus() {
  const user = useStockStore((state) => state.user)
  const authReady = useStockStore((state) => state.authReady)
  const loadSession = useStockStore((state) => state.loadSession)
  const logout = useStockStore((state) => state.logout)

  useEffect(() => {
    loadSession()
  }, [loadSession])

  const linkStyle = {
    border: 0,
    background: 'none',
    padding: 0,
    cursor: 'pointer',
    fontSize: 13,
    textDecoration: 'underline',
    textUnderlineOffset: 3,
  }

  return (
    <span style={{ marginRight: 16, display: 'flex', alignItems: 'center', gap: 10, fontSize: 13 }}>
      {authReady && (user ? (
        <>
          <span>{user.nickname}님</span>
          <button type='button' style={linkStyle} onClick={logout}>로그아웃</button>
        </>
      ) : (
        <>
          <button type='button' style={linkStyle} onClick={goToLogin}>로그인</button>
          <a href='/signup' style={{ ...linkStyle, color: 'inherit' }}>회원가입</a>
        </>
      ))}
    </span>
  )
}
