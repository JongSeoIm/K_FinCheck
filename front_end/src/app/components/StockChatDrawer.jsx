'use client'

import Link from 'next/link'
import { useEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import useHydrated from '../hooks/useHydrated'
import { askChat, createConversation, deleteConversation, fetchConversationMessages, fetchConversations } from '../lib/accountApi'
import { dateTime } from '../lib/format'
import useStockStore from '../store/useStockStore'

const suggestions = ['부채비율이 뭐야?', '최근 실적 알려줘', '현재가 알려줘', '관련 뉴스 알려줘']

function basisText(source) {
  if (source.provider === 'OpenDART') return `사업연도 ${source.basisAt}`
  return dateTime(source.basisAt)
}

function Sources({ sources }) {
  if (!sources?.length) return null
  return (
    <div className='stock-chat-sources'>
      <span>출처</span>
      <ul>
        {sources.slice(0, 5).map((source, index) => (
          <li key={`${source.label}-${index}`}>
            {source.provider} · {source.url
              ? <a href={source.url} target='_blank' rel='noreferrer'>{source.label}</a>
              : source.label}
            {basisText(source) && ` · ${basisText(source)}`}
          </li>
        ))}
      </ul>
    </div>
  )
}

export default function StockChatDrawer({ stock }) {
  const mounted = useHydrated()
  const user = useStockStore((state) => state.user)
  const ready = useStockStore((state) => state.accountStatus !== 'loading')
  const [open, setOpen] = useState(false)
  const [closing, setClosing] = useState(false)
  const [context, setContext] = useState(stock)
  const [conversations, setConversations] = useState({}) // 종목별 화면의 메시지
  const [rooms, setRooms] = useState({}) // 종목별 저장된 대화 목록
  const [activeIds, setActiveIds] = useState({}) // 종목별 열려 있는 대화
  const [historyError, setHistoryError] = useState(null)
  const [reloadKey, setReloadKey] = useState(0)
  const [draft, setDraft] = useState('')
  const [pending, setPending] = useState(false)
  const [failure, setFailure] = useState(null) // { question, message }
  const dialogRef = useRef(null)
  const inputRef = useRef(null)
  const endRef = useRef(null)
  const closeTimer = useRef(null)
  const requestId = useRef(0)
  const historyRequest = useRef(0)
  const messages = conversations[context.symbol] || []
  const symbolRooms = rooms[context.symbol]
  const activeId = activeIds[context.symbol] || null
  // 종목 목록을 받기 전에 패널을 열어도 이름을 채웁니다
  const stockName = (context.symbol === stock.symbol ? stock.name : context.name) || context.symbol

  useEffect(() => () => clearTimeout(closeTimer.current), [])

  // 로그아웃하거나 세션이 만료되면 화면의 대화도 지웁니다
  useEffect(() => useStockStore.subscribe((state, previous) => {
    if (!previous.user || state.user) return
    requestId.current += 1
    historyRequest.current += 1
    setConversations({})
    setRooms({})
    setActiveIds({})
    setHistoryError(null)
    setFailure(null)
    setPending(false)
  }), [])

  // 패널을 열면 이 종목의 저장된 대화 목록과 가장 최근 대화를 불러옵니다
  useEffect(() => {
    if (!open || !user || symbolRooms) return
    const { symbol } = context
    const id = ++historyRequest.current
    fetchConversations(symbol).then(async list => {
      const latest = list[0]?.conversationId
      const saved = latest ? await fetchConversationMessages(latest) : []
      if (id !== historyRequest.current) return
      setRooms(current => ({ ...current, [symbol]: list }))
      setActiveIds(current => ({ ...current, [symbol]: latest || null }))
      setConversations(current => ({ ...current, [symbol]: saved }))
      setHistoryError(null)
    }).catch(error => {
      if (id === historyRequest.current) setHistoryError(error.message)
    })
  }, [open, user, context, symbolRooms, reloadKey])

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
  }, [messages.length, pending, failure, open])

  function show() {
    clearTimeout(closeTimer.current)
    setContext(stock)
    setDraft('')
    setFailure(null)
    setHistoryError(null)
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

  // 첫 질문을 보낼 때 대화방을 만듭니다
  async function conversationId(symbol) {
    if (activeId) return activeId
    const room = await createConversation(symbol)
    setRooms(current => ({ ...current, [symbol]: [room, ...(current[symbol] || [])] }))
    setActiveIds(current => ({ ...current, [symbol]: room.conversationId }))
    return room.conversationId
  }

  async function refreshRooms(symbol) {
    const list = await fetchConversations(symbol)
    setRooms(current => ({ ...current, [symbol]: list }))
  }

  function append(symbol, message) {
    setConversations(current => ({ ...current, [symbol]: [...(current[symbol] || []), message] }))
  }

  async function ask(question, addQuestion = true) {
    const { symbol } = context
    if (addQuestion) append(symbol, { role: 'user', text: question })
    setDraft('')
    setFailure(null)
    setPending(true)
    const id = ++requestId.current
    try {
      const reply = await askChat(await conversationId(symbol), question, { symbol, name: stockName })
      if (id !== requestId.current) return
      if (reply.status === 'failed') {
        setFailure({ question, message: reply.answer })
      } else {
        append(symbol, { role: 'assistant', text: reply.answer, sources: reply.sources })
        refreshRooms(symbol).catch(() => {}) // 제목·순서만 늦게 바뀝니다
      }
    } catch (error) {
      if (id === requestId.current) setFailure({ question, message: error.message })
    } finally {
      if (id === requestId.current) {
        setPending(false)
        inputRef.current?.focus({ preventScroll: true })
      }
    }
  }

  function send(value = draft) {
    const question = value.trim()
    if (question && !pending) ask(question)
  }

  function resetScreen(symbol, id, list) {
    requestId.current += 1
    historyRequest.current += 1
    setActiveIds(current => ({ ...current, [symbol]: id }))
    setConversations(current => ({ ...current, [symbol]: list }))
    setFailure(null)
    setHistoryError(null)
    setPending(false)
  }

  // 새 대화: 이전 대화는 목록에 남고, 첫 질문을 보낼 때 새 대화방이 만들어집니다
  function startNew() {
    resetScreen(context.symbol, null, [])
    inputRef.current?.focus({ preventScroll: true })
  }

  async function openRoom(id) {
    const { symbol } = context
    try {
      const saved = await fetchConversationMessages(id)
      resetScreen(symbol, id, saved)
    } catch (error) {
      setHistoryError(error.message)
    }
  }

  async function removeRoom() {
    const { symbol } = context
    try {
      await deleteConversation(activeId)
      const rest = (symbolRooms || []).filter(room => room.conversationId !== activeId)
      setRooms(current => ({ ...current, [symbol]: rest }))
      const next = rest[0]?.conversationId
      resetScreen(symbol, next || null, next ? await fetchConversationMessages(next) : [])
    } catch (error) {
      setHistoryError(error.message)
    }
  }

  function retryHistory() {
    setHistoryError(null)
    setRooms(current => ({ ...current, [context.symbol]: undefined }))
    setReloadKey(key => key + 1)
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
              <div><span className='stock-chat-eyebrow'>종목 AI 대화</span><h2 id='stock-chat-title'>{context.symbol}<span>{stockName}</span></h2></div>
              <button className='stock-chat-close' type='button' aria-label='대화 패널 닫기' onClick={close}>×</button>
            </header>
            <div className='stock-chat-preview' id='stock-chat-note'>
              <span>최근 메시지 10개까지 이어서 답변합니다</span>
              <button className='stock-chat-new' type='button' onClick={startNew} disabled={!user || (messages.length === 0 && !pending)}>새 대화</button>
            </div>
            {user && symbolRooms?.length > 0 && <div className='stock-chat-history'>
              <label className='sr-only' htmlFor='stock-chat-room'>저장된 대화</label>
              <select id='stock-chat-room' value={activeId || ''} disabled={pending} onChange={event => openRoom(event.target.value)}>
                {!activeId && <option value=''>새 대화</option>}
                {symbolRooms.map(room => <option key={room.conversationId} value={room.conversationId}>{room.title} · {dateTime(room.updatedAt)}</option>)}
              </select>
              <button className='stock-chat-new' type='button' onClick={removeRoom} disabled={!activeId || pending}>삭제</button>
            </div>}
            <div className='stock-chat-messages' aria-label='대화 내용' aria-live='polite' aria-relevant='additions text'>
              {historyError ? <div className='stock-chat-error' role='alert'><span>{historyError}</span><button type='button' onClick={retryHistory}>다시 시도</button></div>
                : user && !symbolRooms ? <div className='stock-chat-typing' role='status'><span /><span /><span /><span className='sr-only'>대화를 불러오고 있습니다</span></div>
                : ready && !user ? <div className='stock-chat-empty stock-chat-login'>
                <div className='stock-chat-empty-copy'><h3>로그인하면<br />AI에게 질문할 수 있어요.</h3><p>지금 보고 있는 종목은 로그인 후 그대로 이어집니다.</p></div>
                <Link href={`/login?symbol=${context.symbol}`}>로그인</Link>
              </div> : messages.length === 0 && !pending ? <div className='stock-chat-empty'>
                <div className='stock-chat-empty-copy'><h3>종목을 보면서,<br />궁금한 건 바로 질문하세요.</h3><p>금융 용어, 현재가, 실적, 관련 기사를 물어볼 수 있습니다.</p></div>
                <div className='stock-chat-suggestions'>{suggestions.map(question => <button key={question} type='button' onClick={() => send(question)}>{question}<span aria-hidden='true'>↗</span></button>)}</div>
              </div> : <div className='stock-chat-thread'>{messages.map((message, index) => <article key={`${context.symbol}-${index}`} className={`stock-chat-message ${message.role}`}>
                {message.role === 'assistant' && <span className='stock-chat-answer-label'>AI 답변</span>}
                <p>{message.text}</p>
                {message.role === 'assistant' && <Sources sources={message.sources} />}
              </article>)}
                {pending && <div className='stock-chat-typing' role='status'><span /><span /><span /><span className='sr-only'>답변을 준비하고 있습니다</span></div>}
                {failure && <div className='stock-chat-error' role='alert'><span>{failure.message}</span><button type='button' onClick={() => ask(failure.question, false)}>다시 시도</button></div>}
              </div>}
              <div ref={endRef} />
            </div>
            <footer className='stock-chat-footer'>
              <form className='stock-chat-composer' onSubmit={event => { event.preventDefault(); send() }}>
                <label className='sr-only' htmlFor='stock-chat-input'>종목에 대해 질문 입력</label>
                <textarea id='stock-chat-input' ref={inputRef} value={draft} onChange={event => setDraft(event.target.value)} maxLength={1500} rows={2} disabled={!user}
                  placeholder={user ? `${stockName}에 대해 질문해 보세요` : '로그인 후 질문할 수 있습니다'}
                  onKeyDown={event => { if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) { event.preventDefault(); send() } }} />
                <div className='stock-chat-composer-actions'><span>Shift + Enter 줄바꿈</span><button type='submit' disabled={!user || !draft.trim() || pending} aria-label='질문 전송'>↑</button></div>
              </form>
              <p>대화는 내 계정에 저장되며 AI 답변은 투자 권유가 아닙니다.</p>
            </footer>
          </div>
        </dialog>, document.body)}
    </>
  )
}
