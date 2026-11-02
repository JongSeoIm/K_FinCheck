'use client'

import { useEffect, useRef, useState } from 'react'
import { marketStocks } from '../lib/market'

export default function StockSearch({ onSelectStock }) {
  const [keyword, setKeyword] = useState('')
  const [results, setResults] = useState([])
  const [message, setMessage] = useState('')
  const searchAreaRef = useRef(null)

  useEffect(() => {
    const trimmed = keyword.trim().toLowerCase()
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
  }, [keyword])

  useEffect(() => {
    const closeResults = (event) => {
      if (!searchAreaRef.current?.contains(event.target)) {
        setResults([])
        setMessage('')
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
  }

  const handleSubmit = (event) => {
    event.preventDefault()
    if (results[0]) handleSelect(results[0])
    else if (keyword.trim()) setMessage('검색 결과에서 종목을 선택해 주세요.')
  }

  return (
    <div className='global-search' ref={searchAreaRef}>
      <form className='search-form' onSubmit={handleSubmit}>
        <label className='sr-only' htmlFor='stock-search'>종목 검색</label>
        <input
          id='stock-search'
          type='search'
          value={keyword}
          onChange={(event) => setKeyword(event.target.value)}
          placeholder='국내 기업명 또는 종목코드 검색 (예: 삼성전자, 005930)'
          autoComplete='off'
        />
        <button type='submit'>검색</button>
      </form>

      {(results.length > 0 || message) && keyword && (
        <div className='search-popover'>
          {message && <p className='search-message'>{message}</p>}
          {results.length > 0 && (
            <ul className='search-results'>
              {results.map((stock) => (
                <li key={`${stock.symbol}-${stock.name}`}>
                  <button type='button' onClick={() => handleSelect(stock)}>
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
