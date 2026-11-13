'use client'

import { useEffect, useRef, useState } from 'react'
import { marketStocks } from '../lib/market'

export default function StockSearch({ onSelectStock }) {
  const [keyword, setKeyword] = useState('')
  const [results, setResults] = useState([])
  const [message, setMessage] = useState('')
  const [activeIndex, setActiveIndex] = useState(-1)
  const searchAreaRef = useRef(null)
  const composing = useRef(false)

  const handleKeywordChange = (value) => {
    setKeyword(value)
    setActiveIndex(-1)
    const trimmed = value.trim().toLowerCase()
    if (!trimmed) {
      setResults([])
      setMessage('')
      return
    }

    const filtered = marketStocks.filter((stock) =>
      stock.name.toLowerCase().includes(trimmed) ||
      stock.symbol.toLowerCase().includes(trimmed)
    )

    setResults(filtered)
    setMessage(filtered.length === 0 ? '일치하는 국내 종목이 없습니다.' : '')
  }

  useEffect(() => {
    const closeResults = (event) => {
      if (!searchAreaRef.current?.contains(event.target)) {
        setResults([])
        setMessage('')
        setActiveIndex(-1)
      }
    }
    document.addEventListener('pointerdown', closeResults)
    return () => document.removeEventListener('pointerdown', closeResults)
  }, [])

  const handleSelect = (stock) => {
    onSelectStock(stock)
    setKeyword('')
    setResults([])
    setMessage('')
    setActiveIndex(-1)
  }

  const handleSubmit = (event) => {
    event.preventDefault()
    if (composing.current) return
    const normalized = keyword.trim().toLowerCase()
    const exact = marketStocks.find(stock => stock.name.toLowerCase() === normalized || stock.symbol === normalized)
    const selected = activeIndex >= 0 ? results[activeIndex] : exact || (results.length === 1 ? results[0] : null)
    if (selected) handleSelect(selected)
    else if (normalized) setMessage(results.length ? '방향키로 종목을 선택한 뒤 Enter를 누르거나 결과를 클릭해 주세요.' : '일치하는 국내 종목이 없습니다.')
  }

  useEffect(() => {
    if (activeIndex < 0) return
    searchAreaRef.current?.querySelector('[aria-selected="true"]')?.scrollIntoView({ block: 'nearest' })
  }, [activeIndex])

  const handleKeyDown = event => {
    if (event.nativeEvent.isComposing || event.keyCode === 229) return
    if ((event.key === 'ArrowDown' || event.key === 'ArrowUp') && results.length) {
      event.preventDefault()
      setActiveIndex(current => event.key === 'ArrowDown'
        ? (current + 1) % results.length
        : current <= 0 ? results.length - 1 : current - 1)
    } else if (event.key === 'Escape') {
      setResults([])
      setMessage('')
      setActiveIndex(-1)
    }
  }

  return (
    <div className='global-search' ref={searchAreaRef}>
      <form className='search-form' onSubmit={handleSubmit}>
        <label className='sr-only' htmlFor='stock-search'>종목 검색</label>
        <input
          id='stock-search'
          type='search'
          role='combobox'
          aria-autocomplete='list'
          aria-expanded={results.length > 0}
          aria-controls={results.length ? 'stock-search-results' : undefined}
          aria-activedescendant={activeIndex >= 0 && results[activeIndex] ? `stock-option-${results[activeIndex].symbol}` : undefined}
          value={keyword}
          onChange={(event) => handleKeywordChange(event.target.value)}
          onFocus={() => handleKeywordChange(keyword)}
          onKeyDown={handleKeyDown}
          onCompositionStart={() => { composing.current = true }}
          onCompositionEnd={() => { composing.current = false }}
          placeholder='기업명 또는 종목코드 검색'
          autoComplete='off'
        />
      </form>

      {(results.length > 0 || message) && keyword && (
        <div className='search-popover'>
          {message && <p className='search-message' role='status'>{message}</p>}
          {results.length > 0 && (
            <ul className='search-results' id='stock-search-results' role='listbox' aria-label='종목 검색 결과'>
              {results.map((stock, index) => (
                <li key={`${stock.symbol}-${stock.name}`} role='presentation'>
                  <button type='button' role='option' id={`stock-option-${stock.symbol}`} aria-selected={activeIndex === index}
                    tabIndex={-1} onMouseDown={event => event.preventDefault()} onClick={() => handleSelect(stock)}>
                    <span className='result-symbol'>{stock.symbol}</span>
                    <span className='result-name'>{stock.name}</span>
                    <span className='result-type'>{stock.market || 'KOSPI'}</span>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </div>
  )
}
