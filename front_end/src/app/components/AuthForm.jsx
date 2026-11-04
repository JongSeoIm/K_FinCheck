'use client'

import Link from 'next/link'
import { useRouter } from 'next/navigation'
import { useState } from 'react'
import { login, signup } from '@/app/lib/accountApi'

const EMPTY = { loginId: '', nickname: '', password: '', passwordConfirm: '' }

// 로그인·회원가입 화면. symbol: 돌아갈 종목 코드(6자리만 받음)
export default function AuthForm({ mode, symbol, joined }) {
  const router = useRouter()
  const isSignup = mode === 'signup'
  const [form, setForm] = useState(EMPTY)
  const [message, setMessage] = useState('')
  const [pending, setPending] = useState(false)

  const query = symbol ? `symbol=${symbol}` : ''
  const update = (field) => (event) => setForm((current) => ({ ...current, [field]: event.target.value }))

  const handleSubmit = async (event) => {
    event.preventDefault()
    if (isSignup && form.password !== form.passwordConfirm) {
      setMessage('비밀번호 확인이 일치하지 않습니다.')
      return
    }
    setPending(true)
    setMessage('')
    try {
      if (isSignup) {
        await signup({ loginId: form.loginId, nickname: form.nickname, password: form.password })
        router.push(`/login?joined=1${query ? `&${query}` : ''}`)
      } else {
        await login({ loginId: form.loginId, password: form.password })
        router.push(query ? `/?${query}` : '/')
      }
    } catch (error) {
      setMessage(error.message)
      setPending(false)
    }
  }

  return (
    <main className='auth-page'>
      <form className='panel auth-card' onSubmit={handleSubmit}>
        <h1>{isSignup ? '회원가입' : '로그인'}</h1>
        {joined && <p className='auth-notice' role='status'>가입이 완료되었습니다. 로그인해 주세요.</p>}

        <label>
          아이디
          <input
            value={form.loginId}
            onChange={update('loginId')}
            autoComplete='username'
            autoCapitalize='none'
            maxLength={20}
            required
          />
          {isSignup && <small>영문 소문자·숫자·밑줄 4~20자</small>}
        </label>

        {isSignup && (
          <label>
            닉네임
            <input value={form.nickname} onChange={update('nickname')} autoComplete='nickname' maxLength={20} required />
          </label>
        )}

        <label>
          비밀번호
          <input
            type='password'
            value={form.password}
            onChange={update('password')}
            autoComplete={isSignup ? 'new-password' : 'current-password'}
            required
          />
          {isSignup && <small>8자 이상</small>}
        </label>

        {isSignup && (
          <label>
            비밀번호 확인
            <input
              type='password'
              value={form.passwordConfirm}
              onChange={update('passwordConfirm')}
              autoComplete='new-password'
              required
            />
          </label>
        )}

        {message && <p className='auth-error' role='alert'>{message}</p>}

        <button type='submit' disabled={pending}>
          {pending ? '처리 중…' : isSignup ? '가입하기' : '로그인'}
        </button>

        <p className='auth-switch'>
          {isSignup ? '이미 계정이 있나요? ' : '계정이 없나요? '}
          <Link href={`/${isSignup ? 'login' : 'signup'}${query ? `?${query}` : ''}`}>
            {isSignup ? '로그인' : '회원가입'}
          </Link>
        </p>
        <p className='auth-switch'><Link href={query ? `/?${query}` : '/'}>대시보드로 돌아가기</Link></p>
      </form>
    </main>
  )
}
