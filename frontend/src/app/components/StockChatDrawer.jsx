'use client'

import { useEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { useRouter } from 'next/navigation'
import { stockApi } from '@/api/stockApi'
import useHydrated from '@/app/hooks/useHydrated'
import useAuthStore from '@/app/store/useAuthStore'
import ChatAnswerVisuals from './ChatAnswerVisuals'

// AI 대화 패널 (로그인한 사용자만 이용)
// 질문 → 우리 Spring 서버(POST /api/chat) → FastAPI(질문 분류·Tool 조회) → OpenAI 답변
// 서버가 대화를 대화방별로 DB에 저장하고, AI 에게는 그 대화방의 최근 메시지 10개를 문맥으로 보낸다.
// 새 대화를 눌러도 바로 대화방을 만들지 않는다. 첫 질문을 보낼 때 POST 로 만들어 빈 대화방이 쌓이지 않게 한다.

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

// 기사 링크는 카드에 있으므로 뉴스 카드가 있는 답변의 출처 목록에서는 뺀다
function visibleSources(message) {
  const hasNews = message.visuals?.some((item) => item.type === 'news')
  return (message.sources || []).filter((source) => !hasNews || source.provider !== 'NAVER API HUB')
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

// 오늘이면 시각만, 아니면 날짜만
function formatRoomTime(value) {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  const seoul = (options) => new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul', ...options }).format(date)
  const today = new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul', dateStyle: 'short' }).format(new Date())
  return seoul({ dateStyle: 'short' }) === today
    ? seoul({ hour: '2-digit', minute: '2-digit', hour12: false })
    : seoul({ month: 'long', day: 'numeric' })
}

// 아직 대화방을 만들지 않은 새 대화의 화면 키
const draftKey = (symbol) => `new-${symbol}`

function fromServer(message) {
  return { role: message.role, text: message.content, status: message.status, sources: message.sources || [], visuals: message.visuals || [] }
}

export default function StockChatDrawer({ stock }) {
  const hydrated = useHydrated()
  const router = useRouter()
  const user = useAuthStore((state) => state.user)
  const authChecked = useAuthStore((state) => state.checked)
  const [open, setOpen] = useState(false)
  const [closing, setClosing] = useState(false)
  const [context, setContext] = useState(stock)	// 패널을 연 시점의 종목 (열려 있는 동안 고정)
  const [conversations, setConversations] = useState({})	// 대화방별 메시지 (키: 대화방 번호, 새 대화는 draftKey)
  const [rooms, setRooms] = useState([])	// 패널을 연 종목의 대화방 목록
  const [roomKey, setRoomKey] = useState(null)	// 보고 있는 대화방
  const [showRooms, setShowRooms] = useState(false)	// 대화 목록 보기
  const [confirmDelete, setConfirmDelete] = useState(null)	// 삭제 확인 중인 대화방 번호
  const [loading, setLoading] = useState(false)
  const [loadError, setLoadError] = useState('')
  const [draft, setDraft] = useState('')
  const [pending, setPending] = useState(false)
  const dialogRef = useRef(null)
  const inputRef = useRef(null)
  const endRef = useRef(null)
  const closeTimer = useRef(null)
  const loadRequest = useRef(0)	// 마지막으로 요청한 불러오기 (늦게 온 이전 응답은 버린다)
  const pendingRoom = useRef(null)	// 답변을 기다리는 대화방의 화면 키
  const roomKeyRef = useRef(null)
  roomKeyRef.current = roomKey
  const loadSymbol = useRef(context.symbol)
  loadSymbol.current = context.symbol
  const messages = conversations[roomKey] || []
  const activeRoom = rooms.find((room) => room.conversationId === roomKey)

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
  }, [messages.length, pending, open, showRooms])

  function addMessage(key, message) {
    setConversations((current) => ({ ...current, [key]: [...(current[key] || []), message] }))
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
    setShowRooms(false)
    setConfirmDelete(null)

    // 같은 종목에서 답변을 기다리는 중이면 화면을 그대로 두고 다시 불러오지 않는다
    if (pending && context.symbol === stock.symbol) return
    await loadRooms(stock.symbol)
  }

  // 대화방 목록을 불러와 가장 최근 대화를 연다 (새로고침·재로그인 후에도 이어서)
  async function loadRooms(symbol) {
    const request = ++loadRequest.current
    setRooms([])
    setRoomKey(null)
    setLoading(true)
    setLoadError('')
    try {
      const list = await stockApi.getConversations(symbol)
      if (request !== loadRequest.current) return
      setRooms(list)
      if (list.length === 0) {
        setRoomKey(draftKey(symbol))
        setLoading(false)
        return
      }
      await loadMessages(list[0].conversationId, request)
    } catch (error) {
      if (request !== loadRequest.current) return
      if (error.status === 401) await useAuthStore.getState().fetchMe()
      else setLoadError(error.message)
      setLoading(false)
    }
  }

  async function loadMessages(conversationId, request) {
    setRoomKey(conversationId)
    const saved = await stockApi.getConversationMessages(conversationId)
    if (request !== loadRequest.current) return
    setConversations((current) => ({ ...current, [conversationId]: saved.map(fromServer) }))
    setLoading(false)
  }

  // 목록에서 대화방을 고른다
  async function selectRoom(conversationId) {
    if (pending) return
    setShowRooms(false)
    setConfirmDelete(null)
    const request = ++loadRequest.current
    setLoading(true)
    setLoadError('')
    try {
      await loadMessages(conversationId, request)
    } catch (error) {
      if (request !== loadRequest.current) return
      if (error.status === 401) await useAuthStore.getState().fetchMe()
      else setLoadError(error.message)
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
    if (!question || pending || loading || !roomKey) return
    setShowRooms(false)
    const { symbol } = context
    let key = roomKey
    let conversationId = key === draftKey(symbol) ? null : key
    pendingRoom.current = key
    addMessage(key, { role: 'user', text: question })
    setDraft('')
    setPending(true)
    try {
      if (!conversationId) {
        // 첫 질문 → 대화방을 만들고 화면 키를 대화방 번호로 옮긴다
        const room = await stockApi.createConversation(symbol)
        const previousKey = key
        conversationId = room.conversationId
        key = conversationId
        pendingRoom.current = key
        setConversations(({ [previousKey]: drafted = [], ...rest }) => ({ ...rest, [key]: drafted }))
        setRooms((current) => [room, ...current])
        if (roomKeyRef.current === previousKey) setRoomKey(key)
      }
      const result = await stockApi.askChat(symbol, question, conversationId)
      const reply = { role: 'assistant', text: result.answer, status: result.status, sources: result.sources || [], visuals: result.visuals || [], question }
      addMessage(key, reply)
      stockApi.getConversations(symbol)
        .then((list) => { if (loadSymbol.current === symbol) setRooms(list) })
        .catch(() => {})	// 목록 갱신 실패는 대화에 영향이 없다
    } catch (error) {
      const text = error.status === 401 ? '로그인이 만료되었습니다. 다시 로그인한 뒤 질문해 주세요.' : error.message
      addMessage(key, { role: 'assistant', text, status: 'error', sources: [], question: error.status === 401 ? undefined : question })
      if (error.status === 401) useAuthStore.getState().fetchMe()
    } finally {
      pendingRoom.current = null
      setPending(false)
      inputRef.current?.focus({ preventScroll: true })
    }
  }

  // 다시 시도: 저장되지 않은 실패 답변(error)은 화면에서 지우고 같은 질문을 다시 보낸다
  function retry(message) {
    if (pending) return
    if (message.status === 'error') {
      setConversations((current) => ({ ...current, [roomKey]: (current[roomKey] || []).slice(0, -2) }))
    }
    send(message.question)
  }

  // 새 대화: 지금 대화는 목록에 남기고 빈 화면을 연다
  function startNewConversation() {
    if (pending) return
    const key = draftKey(context.symbol)
    loadRequest.current += 1
    setLoading(false)
    setLoadError('')
    setShowRooms(false)
    setConfirmDelete(null)
    setConversations((current) => ({ ...current, [key]: [] }))
    setRoomKey(key)
    inputRef.current?.focus({ preventScroll: true })
  }

  // 대화방 삭제. 보고 있던 방이면 남은 방 중 가장 최근 방을 열고, 없으면 새 대화를 연다
  async function deleteRoom(conversationId) {
    if (pending) return
    setLoadError('')
    try {
      await stockApi.deleteConversation(conversationId)
    } catch (error) {
      if (error.status === 401) {
        await useAuthStore.getState().fetchMe()
        return
      }
      // 다른 창에서 이미 지운 대화방이면 목록에서만 빼면 된다
      if (error.status !== 404) {
        setConfirmDelete(null)
        setLoadError(error.message)
        return
      }
    }
    const remaining = rooms.filter((room) => room.conversationId !== conversationId)
    setRooms(remaining)
    setConfirmDelete(null)
    setConversations(({ [conversationId]: _removed, ...rest }) => rest)
    if (roomKey !== conversationId) return
    if (remaining.length > 0) {
      await selectRoom(remaining[0].conversationId)
      setShowRooms(true)
    } else {
      startNewConversation()
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
                  disabled={pending || !roomKey || roomKey === draftKey(context.symbol)}
                >
                  새 대화
                </button>
                <button className='stock-chat-close' type='button' aria-label='대화 패널 닫기' onClick={close}>×</button>
              </div>
            </header>

            <div className='stock-chat-preview' id='stock-chat-note'>
              <span className='stock-chat-preview-tag'>AI</span>
              <span>대화는 계정에 저장돼요 · 대화방마다 최근 메시지 {MEMORY_LIMIT}개를 이어서 기억해요</span>
            </div>

            <div className='stock-chat-rooms-bar'>
              <button
                type='button'
                className='stock-chat-rooms-toggle'
                aria-expanded={showRooms}
                aria-controls='stock-chat-rooms'
                onClick={() => { setShowRooms((value) => !value); setConfirmDelete(null) }}
              >
                대화 목록 {rooms.length}<span aria-hidden='true'>{showRooms ? '⌃' : '⌄'}</span>
              </button>
              <span className='stock-chat-room-title' title={activeRoom?.title}>{activeRoom ? activeRoom.title : '새 대화'}</span>
            </div>

            <div className='stock-chat-messages' aria-label='대화 내용' aria-live='polite' aria-relevant='additions text'>
              {showRooms ? (
                <div id='stock-chat-rooms' className='stock-chat-rooms'>
                  {rooms.length === 0 ? (
                    <p className='stock-chat-rooms-empty'>저장된 대화가 없습니다. 질문하면 대화가 저장돼요.</p>
                  ) : (
                    <ul>
                      {rooms.map((room) => (
                        <li key={room.conversationId} className={room.conversationId === roomKey ? 'is-active' : ''}>
                          {confirmDelete === room.conversationId ? (
                            <div className='stock-chat-room-confirm'>
                              <span>이 대화를 삭제할까요?</span>
                              <button type='button' className='is-danger' disabled={pending} onClick={() => deleteRoom(room.conversationId)}>삭제</button>
                              <button type='button' onClick={() => setConfirmDelete(null)}>취소</button>
                            </div>
                          ) : (
                            <>
                              <button type='button' className='stock-chat-room-open' disabled={pending} aria-current={room.conversationId === roomKey || undefined} onClick={() => selectRoom(room.conversationId)}>
                                <span>{room.title}</span>
                                <time dateTime={room.updatedAt}>{formatRoomTime(room.updatedAt)}</time>
                              </button>
                              <button type='button' className='stock-chat-room-delete' disabled={pending} aria-label={`${room.title} 대화 삭제`} onClick={() => setConfirmDelete(room.conversationId)}>×</button>
                            </>
                          )}
                        </li>
                      ))}
                    </ul>
                  )}
                </div>
              ) : loading ? (
                <div className='skeleton-list'><span /><span /></div>
              ) : loadError ? (
                <div className='news-error' role='alert'>
                  <strong>저장된 대화를 불러오지 못했습니다.</strong>
                  <p>{loadError}</p>
                  <button type='button' onClick={() => (roomKey && roomKey !== draftKey(context.symbol) ? selectRoom(roomKey) : loadRooms(context.symbol))}>다시 시도</button>
                </div>
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
                      key={`${roomKey}-${index}`}
                      className={`stock-chat-message ${message.role}${message.status === 'error' || message.status === 'unavailable' ? ' is-error' : ''}`}
                    >
                      {message.role === 'assistant' && (
                        <span className='stock-chat-answer-label'>{STATUS_LABEL[message.status] || 'AI 답변'}</span>
                      )}
                      <p>{message.text}</p>
                      {message.role === 'assistant' && <ChatAnswerVisuals visuals={message.visuals} />}
                      {message.role === 'assistant' && visibleSources(message).length > 0 && (
                        <ul className='stock-chat-sources' aria-label='출처'>
                          {visibleSources(message).map((source, i) => (
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
                      {message.question && index === messages.length - 1 && (message.status === 'error' || message.status === 'unavailable') && (
                        <button type='button' className='stock-chat-retry' disabled={pending} onClick={() => retry(message)}>다시 시도</button>
                      )}
                    </article>
                  ))}
                  {pending && pendingRoom.current === roomKey && (
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
                  <button type='submit' disabled={!draft.trim() || pending || loading || !roomKey} aria-label='질문 전송'>↑</button>
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
