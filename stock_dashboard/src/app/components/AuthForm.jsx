'use client'

import { useState } from 'react'
import { accountApi, returnPath } from '../lib/accountApi'

export default function AuthForm({ mode, nextPath, registered = false }) {
  const signup = mode === 'signup'
  const destination = returnPath(nextPath)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  async function submit(event) {
    event.preventDefault()
    if (busy) return
    const form = new FormData(event.currentTarget)
    const loginId = String(form.get('loginId')).trim()
    const password = String(form.get('password'))
    if (signup && password !== form.get('confirmPassword')) { setError('비밀번호가 일치하지 않습니다.'); return }
    if (new TextEncoder().encode(password).length > 72) { setError('비밀번호는 UTF-8 기준 72바이트 이내로 입력해 주세요.'); return }
    setError(''); setBusy(true)
    try {
      await accountApi(`/api/auth/${signup ? 'signup' : 'login'}`, {
        method: 'POST', body: JSON.stringify({ loginId, password, ...(signup ? { nickname: String(form.get('nickname')).trim() } : {}) }),
      })
      window.location.assign(signup ? `/login?registered=1&next=${encodeURIComponent(destination)}` : destination)
    } catch (err) { setError(err.message); setBusy(false) }
  }
  return <main className='auth-page'>
    <header className='auth-page-header'><a className='auth-wordmark' href={destination}>K-FinCheck</a><a href={destination}>주식 화면으로</a></header>
    <section className='auth-card' aria-labelledby='auth-title'>
      <h1 id='auth-title'>{signup ? '회원가입' : '로그인'}</h1>
      {!signup && registered && <p className='auth-success' role='status'>가입이 완료되었습니다. 로그인해 주세요.</p>}
      <form onSubmit={submit}>
        <div className='auth-field'><label htmlFor='loginId'>아이디</label>
          <input id='loginId' name='loginId' autoComplete='username' required minLength={4} maxLength={30} pattern='[A-Za-z0-9_]+' placeholder='아이디 입력' aria-describedby={signup ? 'id-help' : undefined} />
          {signup && <small id='id-help'>영문·숫자·밑줄 4~30자</small>}
        </div>
        {signup && <div className='auth-field'><label htmlFor='nickname'>닉네임</label><input id='nickname' name='nickname' autoComplete='nickname' required minLength={2} maxLength={30} placeholder='닉네임 입력' /></div>}
        <div className='auth-field'><label htmlFor='password'>비밀번호</label>
          <input id='password' name='password' type='password' autoComplete={signup ? 'new-password' : 'current-password'} required minLength={8} maxLength={64} placeholder='비밀번호 입력' aria-describedby={signup ? 'password-help' : undefined} />
          {signup && <small id='password-help'>8~64자 · 한글 포함 시 최대 72바이트</small>}
        </div>
        {signup && <div className='auth-field'><label htmlFor='confirmPassword'>비밀번호 확인</label><input id='confirmPassword' name='confirmPassword' type='password' autoComplete='new-password' required minLength={8} maxLength={64} placeholder='비밀번호 다시 입력' /></div>}
        {error && <p className='auth-error' role='alert'>{error}</p>}
        <button className='auth-submit' disabled={busy} type='submit'>{busy ? '처리 중…' : signup ? '회원가입' : '로그인'}</button>
      </form>
      <div className='auth-switch'><span>{signup ? '이미 계정이 있나요?' : '아직 계정이 없나요?'}</span><a href={`/${signup ? 'login' : 'signup'}?next=${encodeURIComponent(destination)}`}>{signup ? '로그인' : '회원가입'}</a></div>
    </section>
  </main>
}
