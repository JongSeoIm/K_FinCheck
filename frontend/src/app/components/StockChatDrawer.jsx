'use client'

import { useEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { useRouter } from 'next/navigation'
import { stockApi } from '@/api/stockApi'
import useHydrated from '@/app/hooks/useHydrated'
import useAuthStore from '@/app/store/useAuthStore'

// AI 대화 패널 (로그인한 사용자만 이용)
// 질문 → 우리 Spring 서버(POST /api/chat/messages) → FastAPI(질문 분류·Tool 조회) → OpenAI 답변
// 서버가 회원·종목별로 최근 메시지 10개를 기억한다. 새 대화 버튼·로그아웃 시 문맥을 지운다.

const MEMORY_LIMIT = 10
const MAX_QUESTION = 500

const STATUS_LABEL = {
  answered: 'AI 답변',
  need_more_info: '조건 확인',
  out_of_scope: '안내',
  unavailable: '자료 확인 불가',
  error: '답변 실패',
}

function suggestionsFor(name) {
  return [`${name} 현재가 알려줘`, `${name} 최근 실적 알려줘`, `${name} 관련 뉴스 알려줘`, '부채비율이 뭐야?']
}

// "2026-11-04T10:31:05+09:00" → "11월 4일 10:31", 날짜가 아니면 그대로 ("2026 사업연도")
function formatBasis(value) {
  if (!value) return ''
  if (!/^\d{4}-\d{2}-\d{2}T/.test(value)) return value
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat('ko-KR', {
    month: 'long', day: 'numeric', hour: '2-digit', minute: '2-digit', hour12: false, timeZone: 'Asia/Seoul',
  }).format(date)
}

function fromServer(message) {
  return { role: message.role, text: message.content, status: message.status, sources: message.sources || [] }
}

export default function StockChatDrawer({ stock }) {
  const hydrated = useHydrated()
  const router = useRouter()
  const user = useAuthStore((state) => state.user)
  const authChecked = useAuthStore((state) => state.checked)
  const [open, setOpen] = useState(false)
  const [closing, setClosing] = useState(false)
  const [context, setContext] = useState(stock)	// 패널을 연 시점의 종목 (열려 있는 동안 고정)
  const [conversations, setConversations] = useState({})	// 종목코드별 대화 (서버에서 불러와 화면에 표시)
  const [loading, setLoading] = useState(false)
  const [draft, setDraft] = useState('')
  const [pending, setPending] = useState(false)
  const dialogRef = useRef(null)
  const inputRef = useRef(null)
  const endRef = useRef(null)
  const closeTimer = useRef(null)
  const messages = conversations[context.symbol] || []

  // 로그아웃·계정 변경 시에는 Dashboard 가 key 를 바꿔 이 컴포넌트를 새로 만든다 (화면 대화도 초기화)
  useEffect(() => () => clearTimeout(closeTimer.current), [])

  // 열릴 때 모달로 띄우고 뒤쪽 화면 스크롤을 막는다
  useEffect(() => {
    if (!hydrated || !open) return undefined
    const dialog = dialogRef.current
    if (!dialog.open) dialog.showModal()
    const previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    inputRef.current?.focus({ preventScroll: true })
    return () => { document.body.style.overflow = previousOverflow }
  }, [hydrated, open])

  useEffect(() => {
    if (open) endRef.current?.scrollIntoView({ block: 'end', behavior: 'smooth' })
  }, [messages.length, pending, open])

  function addMessage(symbol, message) {
    setConversations((current) => ({ ...current, [symbol]: [...(current[symbol] || []), message] }))
  }

  async function show() {
    if (!user) {
      router.push('/login')
      return
    }
    clearTimeout(closeTimer.current)
    setContext(stock)
    setDraft('')
    setClosing(false)
    setOpen(true)

    // 서버가 기억 중인 대화를 불러온다 (다른 탭·새로고침 후에도 이어서)
    setLoading(true)
    try {
      const saved = await stockApi.getConversation(stock.symbol)
      setConversations((current) => ({ ...current, [stock.symbol]: saved.map(fromServer) }))
    } catch (error) {
      if (error.status === 401) await useAuthStore.getState().fetchMe()
    } finally {
      setLoading(false)
    }
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

  async function send(value = draft) {
    const question = value.trim()
    if (!question || pending) return
    const { symbol } = context
    addMessage(symbol, { role: 'user', text: question })
    setDraft('')
    setPending(true)
    try {
      const result = await stockApi.askChat(symbol, question)
      addMessage(symbol, { role: 'assistant', text: result.answer, status: result.status, sources: result.sources || [] })
    } catch (error) {
      const text = error.status === 401 ? '로그인이 만료되었습니다. 다시 로그인한 뒤 질문해 주세요.' : error.message
      addMessage(symbol, { role: 'assistant', text, status: 'error', sources: [] })
      if (error.status === 401) useAuthStore.getState().fetchMe()
    } finally {
      setPending(false)
      inputRef.current?.focus({ preventScroll: true })
    }
  }

  // 새 대화: 서버 문맥(최근 10개)과 화면 대화를 모두 지운다
  async function startNewConversation() {
    if (pending) return
    const { symbol } = context
    try {
      await stockApi.clearConversation(symbol)
      setConversations((current) => ({ ...current, [symbol]: [] }))
      inputRef.current?.focus({ preventScroll: true })
    } catch (error) {
      addMessage(symbol, { role: 'assistant', text: `새 대화를 시작하지 못했습니다. ${error.message}`, status: 'error', sources: [] })
    }
  }

  // 패널 바깥(왼쪽 어두운 영역)을 누르면 닫는다
  function handleBackdropClick(event) {
    if (event.target !== dialogRef.current) return
    const rect = dialogRef.current.getBoundingClientRect()
    if (event.clientX < rect.left || event.clientX > rect.right) close()
  }

  return (
    <>
      <button
        className={`stock-ai-launcher${user ? '' : ' is-locked'}`}
        type='button'
        onClick={show}
        disabled={!authChecked}
        aria-haspopup={user ? 'dialog' : undefined}
      >
        <span>{user ? 'AI에게 종목 질문하기' : '로그인하고 AI에게 질문하기'}</span>
        <span aria-hidden='true'>{user ? '↗' : '🔒'}</span>
      </button>

      {hydrated && user && createPortal(
        <dialog
          ref={dialogRef}
          className={`stock-chat-drawer${closing ? ' is-closing' : ''}`}
          aria-labelledby='stock-chat-title'
          aria-describedby='stock-chat-note'
          onCancel={(event) => { event.preventDefault(); close() }}
          onClick={handleBackdropClick}
        >
          <div className='stock-chat-layout'>
            <header className='stock-chat-header'>
              <div>
                <span className='stock-chat-eyebrow'>종목 AI 대화</span>
                <h2 id='stock-chat-title'>{context.name}<span>{context.symbol}{context.market ? ` · ${context.market}` : ''}</span></h2>
              </div>
              <div className='stock-chat-header-actions'>
                <button
                  className='stock-chat-new'
                  type='button'
                  onClick={startNewConversation}
                  disabled={pending || messages.length === 0}
                >
                  새 대화
                </button>
                <button className='stock-chat-close' type='button' aria-label='대화 패널 닫기' onClick={close}>×</button>
              </div>
            </header>

            <div className='stock-chat-preview' id='stock-chat-note'>
              <span className='stock-chat-preview-tag'>AI</span>
              <span>최근 메시지 {MEMORY_LIMIT}개까지 이어서 기억해요 · 새 대화를 누르면 지워져요</span>
            </div>

            <div className='stock-chat-messages' aria-label='대화 내용' aria-live='polite' aria-relevant='additions text'>
              {loading ? (
                <div className='skeleton-list'><span /><span /></div>
              ) : messages.length === 0 ? (
                <div className='stock-chat-empty'>
                  <div className='stock-chat-empty-copy'>
                    <h3>종목을 보면서,<br />궁금한 건 바로 질문하세요.</h3>
                    <p>현재가·실적·관련 뉴스는 실제 조회한 자료로,<br />금융 용어는 쉬운 말로 설명해 드려요.</p>
                  </div>
                  <div className='stock-chat-suggestions'>
                    {suggestionsFor(context.name).map((question) => (
                      <button key={question} type='button' onClick={() => send(question)}>
                        {question}<span aria-hidden='true'>↗</span>
                      </button>
                    ))}
                  </div>
                </div>
              ) : (
                <div className='stock-chat-thread'>
                  {messages.map((message, index) => (
                    <article
                      key={`${context.symbol}-${index}`}
                      className={`stock-chat-message ${message.role}${message.status === 'error' || message.status === 'unavailable' ? ' is-error' : ''}`}
                    >
                      {message.role === 'assistant' && (
                        <span className='stock-chat-answer-label'>{STATUS_LABEL[message.status] || 'AI 답변'}</span>
                      )}
                      <p>{message.text}</p>
                      {message.role === 'assistant' && message.sources?.length > 0 && (
                        <ul className='stock-chat-sources' aria-label='출처'>
                          {message.sources.map((source, i) => (
                            <li key={`${source.provider}-${i}`}>
                              <span className='stock-chat-source-provider'>{source.provider}</span>
                              <span className='stock-chat-source-label'>{source.label}</span>
                              {source.basisAt && <span className='stock-chat-source-time'>{formatBasis(source.basisAt)}</span>}
                              {source.url && (
                                <a href={source.url} target='_blank' rel='noopener noreferrer'>원문</a>
                              )}
                            </li>
                          ))}
                        </ul>
                      )}
                    </article>
                  ))}
                  {pending && (
                    <div className='stock-chat-typing' role='status'>
                      <span /><span /><span /><span className='sr-only'>AI 답변 작성 중</span>
                    </div>
                  )}
                </div>
              )}
              <div ref={endRef} />
            </div>

            <footer className='stock-chat-footer'>
              <form className='stock-chat-composer' onSubmit={(event) => { event.preventDefault(); send() }}>
                <label className='sr-only' htmlFor='stock-chat-input'>종목에 대해 질문 입력</label>
                <textarea
                  id='stock-chat-input'
                  ref={inputRef}
                  value={draft}
                  onChange={(event) => setDraft(event.target.value)}
                  maxLength={MAX_QUESTION}
                  rows={2}
                  placeholder={`${context.name}에 대해 질문해 보세요`}
                  onKeyDown={(event) => {
                    // 한글 조합 중 Enter 는 글자 확정이므로 전송하지 않는다
                    if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) {
                      event.preventDefault()
                      send()
                    }
                  }}
                />
                <div className='stock-chat-composer-actions'>
                  <span>Shift + Enter 줄바꿈 · {draft.length}/{MAX_QUESTION}</span>
                  <button type='submit' disabled={!draft.trim() || pending} aria-label='질문 전송'>↑</button>
                </div>
              </form>
              <p>답변은 조회한 자료를 바탕으로 AI가 작성하며, 투자 권유가 아닙니다.</p>
            </footer>
          </div>
        </dialog>,
        document.body,
      )}
    </>
  )
}
