'use client'

import { useEffect, useState } from 'react'
import { apiFetch, safeNextPath } from '@/app/lib/api'
import '../auth.css'

export default function LoginPage() {
  const [loginId, setLoginId] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const [signedUp, setSignedUp] = useState(false)

  useEffect(() => {
    // 주소창의 signedUp=1 은 가입 직후라는 뜻입니다. 동시에 CSRF 토큰 쿠키도 미리 받아 둡니다.
    setSignedUp(new URLSearchParams(window.location.search).get('signedUp') === '1')
    apiFetch('/api/auth/me')
  }, [])

  const handleSubmit = async (event) => {
    event.preventDefault()
    setError('')
    setBusy(true)
    const result = await apiFetch('/api/auth/login', { method: 'POST', body: { loginId, password } })
    setBusy(false)
    if (!result.ok) {
      setError(result.data?.error || '로그인하지 못했습니다.')
      return
    }
    // 로그인 전에 보던 종목 화면으로 돌아갑니다.
    const next = new URLSearchParams(window.location.search).get('next')
    window.location.assign(safeNextPath(next))
  }

  return (
    <main className='auth-page'>
      <form className='auth-card' onSubmit={handleSubmit}>
        <h1>로그인</h1>
        {signedUp && <p className='auth-notice'>가입이 완료되었습니다. 로그인해 주세요.</p>}

        <label>
          아이디
          <input
            type='text'
            autoComplete='username'
            value={loginId}
            onChange={(event) => setLoginId(event.target.value)}
            required
          />
        </label>
        <label>
          비밀번호
          <input
            type='password'
            autoComplete='current-password'
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            required
          />
        </label>

        {error && <p className='auth-error' role='alert'>{error}</p>}
        <button type='submit' disabled={busy}>{busy ? '확인 중…' : '로그인'}</button>

        <div className='auth-links'>
          <a href='/signup'>회원가입</a>
          <a href='/'>대시보드로</a>
        </div>
      </form>
    </main>
  )
}
