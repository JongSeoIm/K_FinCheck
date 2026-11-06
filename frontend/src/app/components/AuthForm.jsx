'use client'

import Link from 'next/link'
import { useRouter } from 'next/navigation'
import { useState } from 'react'
import useAuthStore from '@/app/store/useAuthStore'
import useStockStore from '@/app/store/useStockStore'

// 로그인·회원가입 공통 화면 (mode: 'login' | 'signup')
// 서버와 같은 규칙으로 먼저 검사하고, 최종 검사는 서버가 한다
function validate(mode, form) {
  if (!form.loginId.trim()) return '아이디를 입력하세요.'
  if (mode === 'signup' && !/^[a-z0-9]{4,20}$/.test(form.loginId)) return '아이디는 영문 소문자·숫자 4~20자입니다.'
  if (!form.password) return '비밀번호를 입력하세요.'
  if (mode === 'signup') {
    if (form.password.length < 8 || form.password.length > 64) return '비밀번호는 8~64자입니다.'
    if (form.password !== form.passwordConfirm) return '비밀번호 확인이 일치하지 않습니다.'
    const nickname = form.nickname.trim()
    if (nickname.length < 2 || nickname.length > 20) return '닉네임은 2~20자입니다.'
  }
  return ''
}

export default function AuthForm({ mode, justSignedUp = false }) {
  const isSignup = mode === 'signup'
  const router = useRouter()
  const login = useAuthStore((state) => state.login)
  const signup = useAuthStore((state) => state.signup)
  const [form, setForm] = useState({ loginId: '', password: '', passwordConfirm: '', nickname: '' })
  const [error, setError] = useState('')
  const [submitting, setSubmitting] = useState(false)

  function update(event) {
    setForm((current) => ({ ...current, [event.target.name]: event.target.value }))
  }

  async function submit(event) {
    event.preventDefault()
    if (submitting) return
    const message = validate(mode, form)
    if (message) {
      setError(message)
      return
    }
    setError('')
    setSubmitting(true)
    try {
      if (isSignup) {
        // 가입 후 로그인 화면으로 이동한다
        await signup({ loginId: form.loginId, password: form.password, nickname: form.nickname.trim() })
        router.replace('/login?signup=1')
      } else {
        // 로그인 후 이전에 보던 종목으로 돌아간다
        await login({ loginId: form.loginId.trim(), password: form.password })
        const symbol = useStockStore.getState().lastSymbol
        router.replace(symbol ? `/?symbol=${symbol}` : '/')
      }
    } catch (err) {
      setError(err.message)
      setSubmitting(false)
    }
  }

  return (
    <main className='auth-page'>
      <form className='panel auth-card' onSubmit={submit} noValidate>
        <Link href='/' className='auth-home'>국내 주식 실시간 시세</Link>
        <h1>{isSignup ? '회원가입' : '로그인'}</h1>
        <p className='auth-desc'>로그인하면 관심 종목과 AI 종목 질문을 이용할 수 있어요.</p>

        <label className='auth-field'>
          <span>아이디</span>
          <input name='loginId' value={form.loginId} onChange={update} autoComplete='username'
            placeholder={isSignup ? '영문 소문자·숫자 4~20자' : ''} autoFocus />
        </label>
        <label className='auth-field'>
          <span>비밀번호</span>
          <input name='password' type='password' value={form.password} onChange={update}
            autoComplete={isSignup ? 'new-password' : 'current-password'} placeholder={isSignup ? '8자 이상' : ''} />
        </label>
        {isSignup && (
          <>
            <label className='auth-field'>
              <span>비밀번호 확인</span>
              <input name='passwordConfirm' type='password' value={form.passwordConfirm} onChange={update} autoComplete='new-password' />
            </label>
            <label className='auth-field'>
              <span>닉네임</span>
              <input name='nickname' value={form.nickname} onChange={update} placeholder='2~20자' />
            </label>
          </>
        )}

        {justSignedUp && !error && <p className='auth-notice' role='status'>가입이 완료되었습니다. 로그인해 주세요.</p>}
        {error && <p className='auth-error' role='alert'>{error}</p>}

        <button type='submit' className='auth-submit' disabled={submitting}>
          {submitting ? '처리 중…' : isSignup ? '가입하기' : '로그인'}
        </button>

        <p className='auth-switch'>
          {isSignup ? '이미 계정이 있나요? ' : '처음이신가요? '}
          <Link href={isSignup ? '/login' : '/signup'}>{isSignup ? '로그인' : '회원가입'}</Link>
        </p>
      </form>
    </main>
  )
}
