'use client'

import Link from 'next/link'
import { useEffect } from 'react'
import useAuthStore from '@/app/store/useAuthStore'

// 상단 오른쪽 로그인 상태 (로그인 / 회원가입 또는 닉네임 · 로그아웃)
export default function AuthMenu() {
  const user = useAuthStore((state) => state.user)
  const checked = useAuthStore((state) => state.checked)
  const fetchMe = useAuthStore((state) => state.fetchMe)
  const logout = useAuthStore((state) => state.logout)

  useEffect(() => {
    if (!checked) fetchMe()
  }, [checked, fetchMe])

  if (!checked) return <div className='auth-menu' aria-hidden='true' />

  if (!user) {
    return (
      <nav className='auth-menu' aria-label='계정'>
        <Link href='/login' className='auth-link'>로그인</Link>
        <Link href='/signup' className='auth-link auth-link-strong'>회원가입</Link>
      </nav>
    )
  }

  return (
    <nav className='auth-menu' aria-label='계정'>
      <span className='auth-user'><strong>{user.nickname}</strong>님</span>
      <button type='button' className='auth-link' onClick={logout}>로그아웃</button>
    </nav>
  )
}
