'use client'

import { useEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import useStockStore from '@/app/store/useStockStore'
import { apiFetch, goToLogin } from '@/app/lib/api'
import './chat-extra.css'

function safeUrl(value) {
  return typeof value === 'string' && /^https?:\/\//i.test(value) ? value : null
}

export default function StockChatDrawer({ stock }) {
  const [mounted, setMounted] = useState(false)
  const [open, setOpen] = useState(false)
  const [closing, setClosing] = useState(false)
  const [context, setContext] = useState(stock)
  const [messages, setMessages] = useState([])
  const user = useStockStore((state) => state.user)
  const authReady = useStockStore((state) => state.authReady)
  const [draft, setDraft] = useState('')
  const [pending, setPending] = useState(false)
  const dialogRef = useRef(null)
  const inputRef = useRef(null)
  const endRef = useRef(null)
  const closeTimer = useRef(null)
  const suggestions = [`${context.name || context.symbol} 현재가 알려줘`, `${context.name || context.symbol} 최근 실적 알려줘`, '부채비율이 높다는 건 무슨 뜻이야?']

  useEffect(() => {
    setMounted(true)
    return () => {
      clearTimeout(closeTimer.current)
    }
  }, [])

  useEffect(() => {
    if (!mounted || !open) return
    const dialog = dialogRef.current
    if (!dialog.open) dialog.showModal()
    const previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    inputRef.current?.focus({ preventScroll: true })
    return () => { document.body.style.overflow = previousOverflow }
  }, [mounted, open])

  useEffect(() => {
    // 창을 열 때 서버에 남아 있는 최근 대화(최대 10개)를 불러옵니다.
    if (!open || !user) return
    let cancelled = false
    apiFetch('/api/chat/history').then((result) => {
      if (cancelled || !result.ok || !Array.isArray(result.data)) return
      setMessages(result.data.map((item) => ({ role: item.role, text: item.content, sources: item.sources || [] })))
    })
    return () => { cancelled = true }
  }, [open, user])

  useEffect(() => {
    if (open) endRef.current?.scrollIntoView({ block: 'end', behavior: 'smooth' })
  }, [messages.length, pending, open])

  function show() {
    clearTimeout(closeTimer.current)
    setContext(stock)
    setDraft('')
    setClosing(false)
    setOpen(true)
  }

  function close() {
    if (closing) return
    setClosing(true)
    const duration = window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 0 : 180
    closeTimer.current = setTimeout(() => {
      dialogRef.current?.close()
      setOpen(false)
      setClosing(false)
    }, duration)
  }

  async function resetChat() {
    if (pending) return
    await apiFetch('/api/chat/reset', { method: 'POST' })
    setMessages([])
  }

  async function send(value = draft) {
    const question = value.trim()
    if (!question || pending || !user) return
    setMessages((current) => [...current, { role: 'user', text: question }])
    setDraft('')
    setPending(true)
    const result = await apiFetch('/api/chat', {
      method: 'POST',
      body: { message: question, stockCode: context.symbol },
    })
    if (result.status === 401) {
      useStockStore.setState({ user: null, watchlist: [] })
      setPending(false)
      return
    }
    const reply = result.ok
      ? { role: 'assistant', text: result.data.answer, sources: result.data.sources || [] }
      : { role: 'assistant', text: result.data?.error || '답변을 받지 못했습니다.', error: true }
    setMessages((current) => [...current, reply])
    setPending(false)
    inputRef.current?.focus({ preventScroll: true })
  }

  return (
    <>
      <button className='stock-ai-launcher' type='button' onClick={show} aria-haspopup='dialog'>
        <span>AI에게 종목 질문하기</span><span aria-hidden='true'>↗</span>
      </button>
      {mounted && createPortal(
        <dialog ref={dialogRef} className={`stock-chat-drawer${closing ? ' is-closing' : ''}`}
          aria-labelledby='stock-chat-title' aria-describedby='stock-chat-note'
          onCancel={event => { event.preventDefault(); close() }}
          onClick={event => { if (event.target === dialogRef.current) { const rect = dialogRef.current.getBoundingClientRect(); if (event.clientX < rect.left || event.clientX > rect.right) close() } }}>
          <div className='stock-chat-layout'>
            <header className='stock-chat-header'>
              <div><span className='stock-chat-eyebrow'>종목 AI 대화</span><h2 id='stock-chat-title'>{context.symbol}<span>{context.name}</span></h2></div>
              <button className='stock-chat-close' type='button' aria-label='대화 패널 닫기' onClick={close}>×</button>
            </header>
            <div className='stock-chat-preview' id='stock-chat-note'>
              <span style={{ marginRight: 'auto' }}>답변 아래에 자료 출처와 기준 시점을 표시합니다.</span>
              {user && <button className='stock-chat-reset' type='button' onClick={resetChat} disabled={pending || messages.length === 0}>새 대화</button>}
            </div>
            <div className='stock-chat-messages' aria-label='대화 내용' aria-live='polite' aria-relevant='additions text'>
              {!authReady ? null : !user ? <div className='stock-chat-login'>
                <h3>로그인 후 이용할 수 있어요.</h3>
                <p>AI 대화는 내 계정에서만 사용할 수 있고, 대화 내용은 다른 사용자와 구분돼요.</p>
                <button type='button' onClick={goToLogin}>로그인하러 가기</button>
              </div> : messages.length === 0 ? <div className='stock-chat-empty'>
                <div className='stock-chat-empty-copy'><h3>종목을 보면서,<br />궁금한 건 바로 질문하세요.</h3><p>금융 용어부터 현재가, 최근 실적, 관련 기사까지.<br />조회한 자료만 근거로 답해요.</p></div>
                <div className='stock-chat-suggestions'>{suggestions.map(question => <button key={question} type='button' onClick={() => send(question)}>{question}<span aria-hidden='true'>↗</span></button>)}</div>
              </div> : <div className='stock-chat-thread'>{messages.map((message, index) => <article key={index} className={`stock-chat-message ${message.role}${message.error ? ' error' : ''}`}>
                {message.role === 'assistant' && !message.error && <span className='stock-chat-answer-label'>AI 답변</span>}
                <p>{message.text}</p>
                {message.sources?.length > 0 && <ul className='stock-chat-sources' aria-label='답변에 사용한 자료'>
                  {message.sources.map((source, sourceIndex) => {
                    const url = safeUrl(source.url)
                    return <li key={sourceIndex}>
                      <strong>{source.provider}</strong> · {url ? <a href={url} target='_blank' rel='noopener noreferrer'>{source.label}</a> : source.label}
                      {source.basisAt ? ` · ${source.basisAt}` : ''}
                    </li>
                  })}
                </ul>}
              </article>)}{pending && <div className='stock-chat-typing' role='status'><span /><span /><span /><span className='sr-only'>답변 작성 중</span></div>}</div>}
              <div ref={endRef} />
            </div>
            <footer className='stock-chat-footer'>
              <form className='stock-chat-composer' onSubmit={event => { event.preventDefault(); send() }}>
                <label className='sr-only' htmlFor='stock-chat-input'>종목에 대해 질문 입력</label>
                <textarea id='stock-chat-input' ref={inputRef} value={draft} disabled={!user} onChange={event => setDraft(event.target.value)} maxLength={1000} rows={2}
                  placeholder={`${context.symbol}에 대해 질문해 보세요`}
                  onKeyDown={event => { if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) { event.preventDefault(); send() } }} />
                <div className='stock-chat-composer-actions'><span>Shift + Enter 줄바꿈</span><button type='submit' disabled={!user || !draft.trim() || pending} aria-label='질문 전송'>↑</button></div>
              </form>
              <p>질문은 답변 생성을 위해 AI 서비스(OpenAI)로 전송돼요. 최근 대화 10개까지 기억하고, 로그아웃하면 지워져요. 투자 판단은 직접 하셔야 해요.</p>
            </footer>
          </div>
        </dialog>, document.body)}
    </>
  )
}
