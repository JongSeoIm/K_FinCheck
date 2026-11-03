'use client'

import { useEffect, useRef, useState } from 'react'
import useDebounce from '../hooks/useDebounce'

async function searchStocks(keyword, signal) {
  const response = await fetch(`/api/kis/stocks/search?q=${encodeURIComponent(keyword)}`, { signal })
  const data = await response.json()
  if (!response.ok || !Array.isArray(data)) throw new Error(data.error || '검색 요청에 실패했습니다.')
  return data
}

// 지원 종목 검색 (서버 DB). "삼성전자" 또는 "005930" 입력 후 Enter 로 바로 선택
export default function StockSearch({ onSelectStock }) {
  const [keyword, setKeyword] = useState('')
  const [results, setResults] = useState([])
  const [message, setMessage] = useState('')
  const searchAreaRef = useRef(null)
  const debouncedKeyword = useDebounce(keyword, 250)

  useEffect(() => {
    if (!debouncedKeyword.trim()) return undefined
    const controller = new AbortController()

    searchStocks(debouncedKeyword.trim(), controller.signal)
      .then((data) => {
        setResults(data)
        setMessage(data.length === 0 ? '일치하는 지원 종목이 없습니다.' : '')
      })
      .catch((error) => {
        if (error.name !== 'AbortError') {
          setResults([])
          setMessage(error.message)
        }
      })

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
    onSelectStock(stock.code)
    setKeyword('')
    setResults([])
    setMessage('')
  }

  // Enter: 디바운스를 기다리지 않고 바로 검색해서 첫 번째 결과를 선택
  const handleSubmit = async (event) => {
    event.preventDefault()
    const value = keyword.trim()
    if (!value) return
    try {
      const data = await searchStocks(value)
      if (data[0]) handleSelect(data[0])
      else {
        setResults([])
        setMessage('일치하는 지원 종목이 없습니다.')
      }
    } catch (error) {
      setMessage(error.message)
    }
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
                <li key={stock.code}>
                  <button type='button' onClick={() => handleSelect(stock)}>
                    <span className='result-symbol'>{stock.code}</span>
                    <span className='result-name'>{stock.name}</span>
                    <span className='result-type'>{stock.market}</span>
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
