'use client'

import { useEffect, useRef, useState, useSyncExternalStore } from 'react'
import { createPortal } from 'react-dom'
import { accountApi } from '../lib/accountApi'
import useAuthStore from '../store/useAuthStore'
import ChatAnswerData, { formatChatTime } from './ChatAnswerData'

const subscribe = () => () => {}
const clientSnapshot = () => true
const serverSnapshot = () => false
const suggestions = ['현재가와 등락률 알려줘', '최근 연간 실적 알려줘', '관련 산업 정책 알려줘']
const followUpsByType = {
  quote: ['어제 종가와 거래량 알려줘', '최근 연간 실적 알려줘'],
  financial: ['최근 3년 연간 실적 비교해줘', '최근 공시 알려줘'],
  financial_comparison: ['최근 사업보고서에서 실적 변동 이유를 알려줘', '최근 공시 알려줘'],
  policy: ['회사에 직접 관련된 정책만 알려줘', '최근 공시 알려줘'],
  report: ['최근 3년 연간 실적 비교해줘', '최근 공시 알려줘'],
  disclosure: ['최근 사업보고서의 주요 위험 요인을 알려줘', '최근 연간 실적 알려줘'],
}

export default function StockChatDrawer({ stock }) {
  const mounted = useSyncExternalStore(subscribe, clientSnapshot, serverSnapshot)
  const [open, setOpen] = useState(false)
  const [closing, setClosing] = useState(false)
  const [context, setContext] = useState(stock)
  const [messages, setMessages] = useState([])
  const [rooms, setRooms] = useState([])
  const [activeRoom, setActiveRoom] = useState(null)
  const [historyOpen, setHistoryOpen] = useState(false)
  const [deleteId, setDeleteId] = useState(null)
  const [loading, setLoading] = useState(false)
  const [notice, setNotice] = useState('')
  const [draft, setDraft] = useState('')
  const [sending, setSending] = useState(false)
  const { user, ready } = useAuthStore()
  const dialogRef = useRef(null)
  const inputRef = useRef(null)
  const endRef = useRef(null)
  const closeTimer = useRef(null)
  const sendLock = useRef(false)
  const pendingRequest = useRef(null)
  const viewKey = useRef('')
  const conversationKey = user ? `${user.userId}:${context.symbol}` : `guest:${context.symbol}`
  const storageKey = `kfc-chat-room-${conversationKey}`
  const lastMessage = messages.at(-1)
  const lastQuestion = messages.at(-2)?.text?.trim()
  const showFollowUps = user && !sending && !loading && !historyOpen && !draft.trim()
    && lastMessage?.role === 'assistant' && !lastMessage.pending && !lastMessage.error
  const followUps = showFollowUps
    ? [...new Set([...(followUpsByType[lastMessage.presentation?.type] ?? []), ...suggestions])].filter(question =>
        question !== lastQuestion && `${context.name} ${question}` !== lastQuestion).slice(0, 3)
    : []

  useEffect(() => () => clearTimeout(closeTimer.current), [])

  useEffect(() => {
    if (!open || !user) return
    if (pendingRequest.current?.key === conversationKey) return
    let cancelled = false
    async function restore() {
      try {
        const list = await accountApi(`/api/chat/conversations?symbol=${context.symbol}`)
        const saved = localStorage.getItem(storageKey)
        const room = list.find(item => item.conversationId === saved) || list[0] || null
        const records = room ? await accountApi(`/api/chat/conversations/${room.conversationId}/messages`) : []
        if (cancelled) return
        setRooms(list); setActiveRoom(room); setMessages(records.map(record => ({
          role: record.role, text: record.text, sources: record.response?.sources || [], presentation: record.response?.presentation, basis: record.response?.basis,
        })))
        setNotice('')
      } catch (error) { if (!cancelled) setNotice(error.message) }
      finally { if (!cancelled) setLoading(false) }
    }
    restore()
    return () => { cancelled = true }
  }, [open, user, context.symbol, storageKey, conversationKey])

  async function selectRoom(room) {
    setLoading(true); setNotice('')
    try {
      const records = await accountApi(`/api/chat/conversations/${room.conversationId}/messages`)
      if (useAuthStore.getState().user?.userId !== user.userId) return
      setActiveRoom(room); localStorage.setItem(storageKey, room.conversationId)
      setMessages(records.map(record => ({ role: record.role, text: record.text,
        sources: record.response?.sources || [], presentation: record.response?.presentation, basis: record.response?.basis })))
      setHistoryOpen(false); setDeleteId(null)
    } catch (error) { setNotice(error.message) }
    finally { setLoading(false) }
  }

  async function newRoom() {
    setLoading(true); setNotice('')
    try {
      const room = await accountApi('/api/chat/conversations', { method: 'POST', body: JSON.stringify(context) })
      if (useAuthStore.getState().user?.userId !== user.userId) return
      setRooms(current => [room, ...current]); setActiveRoom(room); setMessages([])
      setDraft(''); setHistoryOpen(false); setDeleteId(null)
      localStorage.setItem(storageKey, room.conversationId)
    } catch (error) { setNotice(error.message) }
    finally { setLoading(false) }
  }

  async function deleteRoom(id) {
    setLoading(true); setNotice('')
    try {
      await accountApi(`/api/chat/conversations/${id}`, { method: 'DELETE' })
      if (useAuthStore.getState().user?.userId !== user.userId) return
      const remaining = rooms.filter(room => room.conversationId !== id)
      setRooms(remaining); setDeleteId(null)
      if (activeRoom?.conversationId === id) {
        localStorage.removeItem(storageKey); setActiveRoom(null); setMessages([])
        if (remaining[0]) await selectRoom(remaining[0])
      }
    } catch (error) { setNotice(error.message) }
    finally { setLoading(false) }
  }

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
    if (open) {
      const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches
      endRef.current?.scrollIntoView({ block: 'end', behavior: reduceMotion ? 'auto' : 'smooth' })
    }
  }, [messages, open])

  function show() {
    if (ready && !user) {
      window.location.href = `/login?next=${encodeURIComponent(`/?symbol=${stock.symbol}`)}`
      return
    }
    clearTimeout(closeTimer.current)
    const nextKey = `${user?.userId}:${stock.symbol}`
    viewKey.current = nextKey
    setContext(stock)
    const pending = pendingRequest.current
    if (pending?.key === nextKey) {
      setMessages(pending.messages); setRooms(pending.rooms); setActiveRoom(pending.room); setLoading(false)
    } else {
      setMessages([]); setRooms([]); setActiveRoom(null); setLoading(true)
    }
    setHistoryOpen(false); setDeleteId(null)
    setNotice(pending && pending.key !== nextKey ? '다른 종목의 답변을 처리 중입니다. 완료 후 질문해 주세요.' : '')
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

  async function send(value = draft) {
    const question = value.trim()
    if (!question || sendLock.current || sending || loading || !user) return
    sendLock.current = true
    const replyId = crypto.randomUUID()
    setSending(true)
    setNotice('')
    setHistoryOpen(false)
    setDraft('')
    const nextMessages = [...messages,
      { role: 'user', text: question },
      { id: replyId, role: 'assistant', pending: true, text: '답변을 확인하고 있습니다.' },
    ]
    const request = { key: conversationKey, messages: nextMessages, rooms, room: activeRoom }
    pendingRequest.current = request
    const currentView = () => viewKey.current === request.key && useAuthStore.getState().user?.userId === user.userId
    setMessages(nextMessages)
    try {
      let room = activeRoom
      if (!room) {
        room = await accountApi('/api/chat/conversations', { method: 'POST', body: JSON.stringify(context) })
        if (useAuthStore.getState().user?.userId !== user.userId) return
        request.room = room
        request.rooms = [room, ...rooms]
        if (currentView()) { setActiveRoom(room); setRooms(request.rooms) }
        localStorage.setItem(storageKey, room.conversationId)
      }
      const conversationId = room.conversationId
      const response = await accountApi('/api/chat', {
        method: 'POST',
        body: JSON.stringify({ conversationId, message: question, stock: context }),
      })
      if (useAuthStore.getState().user?.userId !== user.userId) return
      if (!currentView()) return
      setMessages(current => current.map(message => message.id === replyId
        ? { id: replyId, role: 'assistant', text: response.answer, sources: response.sources || [], presentation: response.presentation, basis: response.basis }
        : message))
      try {
        const list = await accountApi(`/api/chat/conversations?symbol=${context.symbol}`)
        if (currentView()) setRooms(list)
      } catch {
        if (currentView()) setNotice('답변을 받았지만 대화 목록을 갱신하지 못했습니다.')
      }
    } catch (error) {
      if (currentView()) {
        setMessages(current => current.map(message => message.id === replyId
          ? { id: replyId, role: 'assistant', error: true, text: error.message }
          : message))
      }
    } finally {
      sendLock.current = false
      if (pendingRequest.current === request) pendingRequest.current = null
      setSending(false)
      setNotice(value => value === '다른 종목의 답변을 처리 중입니다. 완료 후 질문해 주세요.' ? '' : value)
      if (currentView() && dialogRef.current?.open) inputRef.current?.focus({ preventScroll: true })
    }
  }

  return (
    <>
      <button className='stock-ai-launcher' type='button' onClick={show} aria-haspopup='dialog' disabled={!ready}>
        <span className='stock-ai-launcher-label'>AI 질문</span>
        <span className='stock-ai-launcher-stock'>{stock.name}</span>
      </button>
      {mounted && createPortal(
        <dialog ref={dialogRef} className={`stock-chat-drawer${closing ? ' is-closing' : ''}`}
          aria-labelledby='stock-chat-title'
          onCancel={event => { event.preventDefault(); close() }}
          onClick={event => {
            if (event.target !== dialogRef.current) return
            const rect = dialogRef.current.getBoundingClientRect()
            if (event.clientX < rect.left || event.clientX > rect.right) close()
          }}>
          <div className='stock-chat-layout'>
            <header className='stock-chat-header'>
              <div>
                <h2 id='stock-chat-title'>{context.name} AI 질문</h2>
                <p className='stock-chat-context'>종목코드 {context.symbol} 기준으로 답변합니다</p>
              </div>
              <button className='stock-chat-close' type='button' aria-label='대화 패널 닫기' onClick={close}>×</button>
            </header>
            {user && <nav className='stock-chat-actions' aria-label='대화 관리'>
              <button type='button' onClick={newRoom} disabled={sending || loading}>새 대화</button>
              <button type='button' onClick={() => setHistoryOpen(value => !value)} disabled={sending || loading} aria-expanded={historyOpen}>이전 대화</button>
            </nav>}
            {user && notice && <p className='stock-chat-notice' role='alert'>{notice}</p>}
            {!user && ready && <p className='stock-chat-notice' role='alert'>로그인이 필요합니다. <a href={`/login?next=${encodeURIComponent(`/?symbol=${context.symbol}`)}`}>다시 로그인</a>해 주세요.</p>}
            {user && loading && <p className='stock-chat-notice' role='status'>대화를 불러오고 있습니다.</p>}
            {historyOpen && user && <section className='stock-chat-history' aria-label='이전 대화 목록'>
              {!rooms.length && <p>저장된 대화가 없습니다.</p>}
              {rooms.map(room => <div className='stock-chat-history-row' key={room.conversationId}>
                <button type='button' className='stock-chat-room-title' onClick={() => selectRoom(room)} disabled={sending || loading} aria-current={room.conversationId === activeRoom?.conversationId ? 'true' : undefined}>{room.title}</button>
                {deleteId !== room.conversationId ? <button type='button' disabled={sending || loading} onClick={() => setDeleteId(room.conversationId)}>삭제</button> :
                  <div className='stock-chat-delete-confirm'><span>이 대화와 메시지를 삭제할까요?</span>
                    <button type='button' disabled={loading} onClick={() => deleteRoom(room.conversationId)}>삭제</button>
                    <button type='button' disabled={loading} onClick={() => setDeleteId(null)}>취소</button>
                  </div>}
              </div>)}
            </section>}
            <div className='stock-chat-messages' aria-label='대화 내용' aria-live='polite' aria-relevant='additions text'>
              {user && !messages.length && !loading && !sending && !notice && !historyOpen &&
                <div className='stock-chat-empty'>
                  <p>{context.name}에 대해 무엇이 궁금한가요?</p>
                  <div className='stock-chat-suggestions' aria-label='예시 질문'>
                    {suggestions.map(question => <button type='button' key={question}
                      onClick={() => send(`${context.name} ${question}`)} disabled={sending || loading}>
                      {question}
                    </button>)}
                  </div>
                </div>}
              <div className='stock-chat-thread'>
                {(user ? messages : []).map((message, index) => (
                  <article key={message.id || `${conversationKey}-${index}`}
                    className={`stock-chat-message ${message.role}${message.error ? ' error' : ''}${message.pending ? ' pending' : ''}`}
                    aria-label={message.role === 'user' ? '내 질문' : 'AI 답변'}>
                    {message.pending ? <div className='stock-chat-typing' role='status'>
                      <span className='sr-only'>답변 생성 중</span>
                      <span aria-hidden='true' /><span aria-hidden='true' /><span aria-hidden='true' />
                    </div> : <p>{message.text}</p>}
                    {message.basis && <small className='stock-chat-basis'>{message.basis}</small>}
                    <ChatAnswerData presentation={message.presentation} />
                    {message.sources?.length > 0 && <p className='stock-chat-sources-label' aria-hidden='true'>출처</p>}
                    {message.sources?.length > 0 && <ul className='stock-chat-sources answer-sources' aria-label='답변 출처'>
                      {message.sources.map((source, sourceIndex) => <li key={`${source.provider}-${sourceIndex}`}>
                        {source.url
                          ? <a href={source.url} target='_blank' rel='noreferrer'>{source.provider === 'KOREA_KR' ? '정책브리핑' : source.provider} · {source.label}</a>
                          : <span>{source.provider === 'KOREA_KR' ? '정책브리핑' : source.provider} · {source.label}</span>}
                        {source.basisAt && <small>{formatChatTime(source.basisAt)}</small>}
                      </li>)}
                    </ul>}
                  </article>
                ))}
              </div>
              <div ref={endRef} />
            </div>
            {followUps.length > 0 && <div className='stock-chat-suggestions stock-chat-follow-ups' role='group' aria-label='추천 후속 질문'>
              {followUps.map(question => <button type='button' key={question} onClick={() => send(`${context.name} ${question}`)}>{question}</button>)}
            </div>}
            <footer className='stock-chat-footer'>
              <form className='stock-chat-composer' onSubmit={event => { event.preventDefault(); send() }}>
                <label className='sr-only' htmlFor='stock-chat-input'>종목에 대해 질문 입력</label>
                <textarea id='stock-chat-input' ref={inputRef} value={draft} enterKeyHint='send'
                  readOnly={sending || loading}
                  onChange={event => setDraft(event.target.value)} maxLength={1500} rows={1}
                  placeholder={`${context.name}에 대해 질문하기`}
                  onKeyDown={event => {
                    if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) {
                      event.preventDefault()
                      send()
                    }
                  }} />
                <button className='stock-chat-send' type='submit' disabled={!user || sending || loading || !draft.trim()}>
                  {sending ? '답변 중…' : '보내기'}
                </button>
              </form>
            </footer>
          </div>
        </dialog>, document.body)}
    </>
  )
}
