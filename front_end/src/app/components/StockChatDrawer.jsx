'use client'

import { useEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import useHydrated from '../hooks/useHydrated'

const suggestions = ['영업이익률은 어떻게 읽어?', '매출과 이익은 어떻게 달라?', '부채비율이 높다는 건 무슨 뜻이야?']

function exampleAnswer(question) {
  if (/부채/.test(question)) return '부채비율은 자본에 비해 부채가 얼마나 있는지 보여주는 지표예요.\n\n부채총계 ÷ 자본총계 × 100으로 계산하며, 업종과 과거 추이를 함께 살펴봐야 해요. 실제 연결 후에는 선택 종목의 공시 수치와 기준 연도를 함께 보여줄 예정이에요.'
  if (/매출/.test(question)) return '매출은 제품이나 서비스를 판매해 얻은 수익이고, 이익은 관련 비용을 반영하고 남은 금액이에요.\n\n매출이 늘어도 비용이 더 크게 증가하면 이익은 줄어들 수 있어요. 실제 연결 후에는 선택 종목의 연도별 재무자료를 참고해 설명할 예정이에요.'
  if (/이익률/.test(question)) return '영업이익률은 매출에서 영업이익이 차지하는 비중이에요.\n\n영업이익 ÷ 매출액 × 100으로 계산해요. 같은 기업의 연도별 변화와 비슷한 업종을 함께 비교하면 이해하기 쉬워요.\n\n이 답변은 대화 패널을 확인하기 위한 고정 예시예요.'
  return '이곳에 선택한 종목의 재무자료를 참고한 답변이 표시될 예정이에요.\n\n지금은 UI 미리보기로, 입력하신 내용을 외부 AI에 전송하지 않아요. 실제 연결 후에는 답변에 사용한 자료의 기준 연도와 공시 출처도 함께 표시할 수 있어요.'
}

export default function StockChatDrawer({ stock }) {
  const mounted = useHydrated()
  const [open, setOpen] = useState(false)
  const [closing, setClosing] = useState(false)
  const [context, setContext] = useState(stock)
  const [conversations, setConversations] = useState({})
  const [draft, setDraft] = useState('')
  const [pending, setPending] = useState(false)
  const dialogRef = useRef(null)
  const inputRef = useRef(null)
  const endRef = useRef(null)
  const responseTimer = useRef(null)
  const closeTimer = useRef(null)
  const messages = conversations[context.symbol] || []

  useEffect(() => {
    return () => {
      clearTimeout(responseTimer.current)
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

  function send(value = draft) {
    const question = value.trim()
    if (!question || pending) return
    const symbol = context.symbol
    setConversations(current => ({ ...current, [symbol]: [...(current[symbol] || []), { role: 'user', text: question }] }))
    setDraft('')
    setPending(true)
    responseTimer.current = setTimeout(() => {
      setConversations(current => ({ ...current, [symbol]: [...(current[symbol] || []), { role: 'assistant', text: exampleAnswer(question) }] }))
      setPending(false)
      inputRef.current?.focus({ preventScroll: true })
    }, 650)
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
            <div className='stock-chat-preview' id='stock-chat-note'><span className='stock-chat-preview-tag'>UI 미리보기</span><span>예시 대화 · 실제 AI 및 공시 연결 전</span></div>
            <div className='stock-chat-messages' aria-label='대화 내용' aria-live='polite' aria-relevant='additions text'>
              {messages.length === 0 ? <div className='stock-chat-empty'>
                <div className='stock-chat-empty-copy'><h3>종목을 보면서,<br />궁금한 건 바로 질문하세요.</h3><p>재무 지표의 의미부터 기업 실적까지.<br />어떤 대화 화면인지 예시로 확인해 보세요.</p></div>
                <div className='stock-chat-suggestions'>{suggestions.map(question => <button key={question} type='button' onClick={() => send(question)}>{question}<span aria-hidden='true'>↗</span></button>)}</div>
              </div> : <div className='stock-chat-thread'>{messages.map((message, index) => <article key={`${context.symbol}-${index}`} className={`stock-chat-message ${message.role}`}>
                {message.role === 'assistant' && <span className='stock-chat-answer-label'>예시 답변</span>}
                <p>{message.text}</p>
              </article>)}{pending && <div className='stock-chat-typing' role='status'><span /><span /><span /><span className='sr-only'>예시 답변 표시 중</span></div>}</div>}
              <div ref={endRef} />
            </div>
            <footer className='stock-chat-footer'>
              <form className='stock-chat-composer' onSubmit={event => { event.preventDefault(); send() }}>
                <label className='sr-only' htmlFor='stock-chat-input'>종목에 대해 질문 입력</label>
                <textarea id='stock-chat-input' ref={inputRef} value={draft} onChange={event => setDraft(event.target.value)} maxLength={1500} rows={2}
                  placeholder={`${context.symbol}에 대해 질문해 보세요`}
                  onKeyDown={event => { if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) { event.preventDefault(); send() } }} />
                <div className='stock-chat-composer-actions'><span>Shift + Enter 줄바꿈</span><button type='submit' disabled={!draft.trim() || pending} aria-label='질문 전송'>↑</button></div>
              </form>
              <p>대화는 이 화면에서만 유지되며 외부로 전송되지 않습니다.</p>
            </footer>
          </div>
        </dialog>, document.body)}
    </>
  )
}
