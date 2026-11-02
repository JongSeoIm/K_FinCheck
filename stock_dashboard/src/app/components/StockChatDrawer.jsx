'use client'

import { useEffect, useRef, useState, useSyncExternalStore } from 'react'
import { createPortal } from 'react-dom'

const subscribe = () => () => {}
const clientSnapshot = () => true
const serverSnapshot = () => false

export default function StockChatDrawer({ stock }) {
  const mounted = useSyncExternalStore(subscribe, clientSnapshot, serverSnapshot)
  const [open, setOpen] = useState(false)
  const [closing, setClosing] = useState(false)
  const [context, setContext] = useState(stock)
  const [conversations, setConversations] = useState({})
  const [draft, setDraft] = useState('')
  const dialogRef = useRef(null)
  const inputRef = useRef(null)
  const endRef = useRef(null)
  const closeTimer = useRef(null)
  const messages = conversations[context.symbol] || []

  useEffect(() => () => clearTimeout(closeTimer.current), [])

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
  }, [messages.length, open])

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

  function send() {
    const question = draft.trim()
    if (!question) return
    const symbol = context.symbol
    setConversations(current => ({ ...current, [symbol]: [
      ...(current[symbol] || []),
      { role: 'user', text: question },
      { role: 'assistant', text: 'AI 연결 전입니다.' },
    ] }))
    setDraft('')
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
          onClick={event => {
            if (event.target !== dialogRef.current) return
            const rect = dialogRef.current.getBoundingClientRect()
            if (event.clientX < rect.left || event.clientX > rect.right) close()
          }}>
          <div className='stock-chat-layout'>
            <header className='stock-chat-header'>
              <div>
                <h2 id='stock-chat-title'>{context.name}<span>{context.symbol}</span></h2>
                <span className='stock-chat-connection' id='stock-chat-note'>AI 미연결</span>
              </div>
              <button className='stock-chat-close' type='button' aria-label='대화 패널 닫기' onClick={close}>×</button>
            </header>
            <div className='stock-chat-messages' aria-label='대화 내용' aria-live='polite' aria-relevant='additions text'>
              <div className='stock-chat-thread'>
                {messages.map((message, index) => (
                  <article key={`${context.symbol}-${index}`} className={`stock-chat-message ${message.role}`}>
                    <p>{message.text}</p>
                  </article>
                ))}
              </div>
              <div ref={endRef} />
            </div>
            <footer className='stock-chat-footer'>
              <form className='stock-chat-composer' onSubmit={event => { event.preventDefault(); send() }}>
                <label className='sr-only' htmlFor='stock-chat-input'>종목에 대해 질문 입력</label>
                <textarea id='stock-chat-input' ref={inputRef} value={draft}
                  onChange={event => setDraft(event.target.value)} maxLength={1500} rows={2}
                  placeholder='메시지 입력'
                  onKeyDown={event => {
                    if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) {
                      event.preventDefault()
                      send()
                    }
                  }} />
                <div className='stock-chat-composer-actions'>
                  <span>Shift + Enter 줄바꿈</span>
                  <button type='submit' disabled={!draft.trim()} aria-label='질문 전송'>↑</button>
                </div>
              </form>
            </footer>
          </div>
        </dialog>, document.body)}
    </>
  )
}
