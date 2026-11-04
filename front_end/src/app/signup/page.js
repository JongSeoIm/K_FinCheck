'use client'

import { useEffect, useState } from 'react'
import { apiFetch } from '@/app/lib/api'
import '../auth.css'

export default function SignupPage() {
  const [form, setForm] = useState({ loginId: '', password: '', passwordConfirm: '', nickname: '' })
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    // 변경 요청에 필요한 CSRF 토큰 쿠키를 미리 받아 둡니다.
    apiFetch('/api/auth/me')
  }, [])

  const update = (key) => (event) => setForm((prev) => ({ ...prev, [key]: event.target.value }))

  const handleSubmit = async (event) => {
    event.preventDefault()
    setError('')
    if (form.password !== form.passwordConfirm) {
      setError('비밀번호 확인이 일치하지 않습니다.')
      return
    }
    setBusy(true)
    const result = await apiFetch('/api/auth/signup', { method: 'POST', body: form })
    setBusy(false)
    if (!result.ok) {
      setError(result.data?.error || '가입하지 못했습니다.')
      return
    }
    // 가입에 성공하면 로그인 화면으로 이동합니다.
    window.location.assign('/login?signedUp=1')
  }

  return (
    <main className='auth-page'>
      <form className='auth-card' onSubmit={handleSubmit}>
        <h1>회원가입</h1>

        <label>
          아이디
          <input type='text' autoComplete='username' value={form.loginId} onChange={update('loginId')} required />
          <p className='auth-hint'>영문·숫자·밑줄 4~20자</p>
        </label>
        <label>
          비밀번호
          <input type='password' autoComplete='new-password' value={form.password} onChange={update('password')} required />
          <p className='auth-hint'>8~50자</p>
        </label>
        <label>
          비밀번호 확인
          <input type='password' autoComplete='new-password' value={form.passwordConfirm} onChange={update('passwordConfirm')} required />
        </label>
        <label>
          닉네임
          <input type='text' value={form.nickname} onChange={update('nickname')} required />
          <p className='auth-hint'>2~20자</p>
        </label>

        {error && <p className='auth-error' role='alert'>{error}</p>}
        <button type='submit' disabled={busy}>{busy ? '처리 중…' : '가입하기'}</button>

        <div className='auth-links'>
          <a href='/login'>로그인</a>
          <a href='/'>대시보드로</a>
        </div>
      </form>
    </main>
  )
}
