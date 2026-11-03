'use client'

import { useEffect, useRef, useState } from 'react'
import useDebounce from '../hooks/useDebounce'

export default function StockSearch({ onSelectStock }) {
  const [keyword, setKeyword] = useState('')
  const [results, setResults] = useState([])
  const [message, setMessage] = useState('')
  const searchAreaRef = useRef(null)
  const debouncedKeyword = useDebounce(keyword, 350)

  useEffect(() => {
    if (!debouncedKeyword.trim()) return

    const controller = new AbortController()

    const fetchStocks = async () => {
      try {
        setMessage('검색 중...')
        const response = await fetch(
          `/api/search?q=${encodeURIComponent(debouncedKeyword)}`,
          { signal: controller.signal }
        )
        const data = await response.json()
        if (!response.ok) throw new Error(data.error || '검색 요청에 실패했습니다.')

        setResults(data)
        setMessage(data.length === 0 ? '일치하는 종목이 없습니다.' : '')
      } catch (error) {
        if (error.name !== 'AbortError') {
          setResults([])
          setMessage(error.message)
        }
      }
    }

    fetchStocks()
    return () => controller.abort()
  }, [debouncedKeyword])

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
          onChange={(event) => {
            setKeyword(event.target.value)
            if (!event.target.value.trim()) {
              setResults([])
              setMessage('')
            }
          }}
          placeholder='종목명 또는 코드 (예: 삼성전자, 005930)'
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
                    <span className='result-type'>{stock.type || 'US Equity'}</span>
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
