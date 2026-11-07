'use client'

import { useEffect, useRef, useState } from 'react'
import { stockApi } from '@/api/stockApi'
import useDebounce from '@/app/hooks/useDebounce'

// 종목명 또는 종목코드로 지원 종목 검색 ("삼성전자", "005930")
export default function StockSearch({ onSelect }) {
  const [keyword, setKeyword] = useState('')
  const [results, setResults] = useState([])
  const [message, setMessage] = useState('')
  const [open, setOpen] = useState(false)
  const areaRef = useRef(null)
  const debouncedKeyword = useDebounce(keyword.trim(), 300)

  useEffect(() => {
    if (!debouncedKeyword) return undefined
    const controller = new AbortController()

    stockApi.search(debouncedKeyword, { signal: controller.signal })
      .then((list) => {
        setResults(list)
        setMessage(list.length === 0 ? '지원하는 종목 중 일치하는 종목이 없습니다.' : '')
        setOpen(true)
      })
      .catch((error) => {
        if (error.name === 'AbortError') return
        setResults([])
        setMessage(error.message)
        setOpen(true)
      })

    return () => controller.abort()
  }, [debouncedKeyword])

  // 검색 영역 밖을 누르면 결과 닫기
  useEffect(() => {
    const close = (event) => {
      if (!areaRef.current?.contains(event.target)) setOpen(false)
    }
    document.addEventListener('pointerdown', close)
    return () => document.removeEventListener('pointerdown', close)
  }, [])

  const select = (stock) => {
    onSelect(stock)
    setKeyword('')
    setResults([])
    setMessage('')
    setOpen(false)
  }

  const handleSubmit = (event) => {
    event.preventDefault()
    if (results[0]) select(results[0])
    else if (keyword.trim()) setMessage('검색 결과에서 종목을 선택해 주세요.')
  }

  return (
    <div className='search' ref={areaRef}>
      <form className='search-form' onSubmit={handleSubmit} role='search'>
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
              setOpen(false)
            }
          }}
          onFocus={() => keyword.trim() && setOpen(true)}
          onKeyDown={(event) => event.key === 'Escape' && setOpen(false)}
          placeholder='종목명 또는 종목코드 (예: 삼성전자, 005930)'
          autoComplete='off'
        />
        <button type='submit'>검색</button>
      </form>

      {open && keyword.trim() && (results.length > 0 || message) && (
        <div className='search-popover'>
          {message && <p className='search-message'>{message}</p>}
          {results.length > 0 && (
            <ul className='search-results'>
              {results.map((stock) => (
                <li key={stock.symbol}>
                  <button type='button' onClick={() => select(stock)}>
                    <span className='result-name'>{stock.name}</span>
                    <span className='result-symbol'>{stock.symbol}</span>
                    <span className='result-market'>{stock.market}</span>
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
