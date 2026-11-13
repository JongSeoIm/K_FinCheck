'use client'

import { useState } from 'react'
import { useRouter } from 'next/navigation'
import { FaStar, FaRegStar, FaChevronRight } from 'react-icons/fa'
import { marketStocks } from '../lib/market'
import useAuthStore from '../store/useAuthStore'
import useStockStore from '../store/useStockStore'
import useStockListQuotes from '../hooks/useStockListQuotes'

const PAGE_SIZE = 10

function StockRow({ stock, quote, error, loading, saved, disabled, onToggle, onSelect }) {
  const price = Number.isFinite(quote?.price) ? quote.price : null
  const rate = Number.isFinite(quote?.changeRate) ? quote.changeRate : null
  const title = error || quote?.exchangeTime || quote?.fetchedAt || undefined
  return <tr>
    <td className='market-star-cell'>
      <button className={`market-star${saved ? ' saved' : ''}`} type='button'
        aria-label={`${stock.name} 관심 종목 ${saved ? '해제' : '추가'}`} aria-pressed={saved}
        disabled={disabled} onClick={() => onToggle(stock.symbol)}>
        {saved ? <FaStar aria-hidden='true' /> : <FaRegStar aria-hidden='true' />}
      </button>
    </td>
    <th scope='row'><button className='market-stock-name' type='button' onClick={() => onSelect(stock)}>{stock.name}</button></th>
    <td className='market-code'>{stock.symbol}</td>
    <td className='market-number' title={title} aria-busy={loading}>
      {price == null ? '—' : `${price.toLocaleString('ko-KR')}원`}
      {error && <span className='sr-only'>시세 조회 실패</span>}
    </td>
    <td className={`market-number${rate > 0 ? ' positive' : rate < 0 ? ' negative' : ''}`}>
      {rate == null ? '—' : `${rate > 0 ? '+' : ''}${rate.toFixed(2)}%`}
    </td>
    <td className='market-open-cell'><button type='button' className='market-open' onClick={() => onSelect(stock)}
      aria-label={`${stock.name} 상세 보기`}><FaChevronRight aria-hidden='true' /></button></td>
  </tr>
}

export default function DomesticStocks({ trades, onSelectStock }) {
  const router = useRouter()
  const [market, setMarket] = useState('전체')
  const [page, setPage] = useState(1)
  const user = useAuthStore(state => state.user)
  const ready = useAuthStore(state => state.ready)
  const watchlist = useStockStore(state => state.watchlist)
  const saving = useStockStore(state => state.saving)
  const error = useStockStore(state => state.watchlistError)
  const filtered = marketStocks.filter(stock => market === '전체' || stock.market === market)
  const pageCount = Math.ceil(filtered.length / PAGE_SIZE)
  const visible = filtered.slice((page - 1) * PAGE_SIZE, page * PAGE_SIZE)
  const quotes = useStockListQuotes(visible.map(stock => stock.symbol), trades)

  function toggle(symbol) {
    if (!user) { router.push('/login?next=%2F'); return }
    const store = useStockStore.getState()
    if (watchlist.includes(symbol)) store.removeFromWatchlist(symbol)
    else store.addToWatchlist(symbol)
  }

  return <section className='domestic-market' aria-labelledby='domestic-market-title'>
    <div className='market-title'><h1 id='domestic-market-title'>국내 주식</h1><span>{filtered.length}개 종목</span></div>
    <nav className='market-tabs' aria-label='시장 구분'>
      {['전체', 'KOSPI', 'KOSDAQ'].map(value => <button key={value} type='button'
        className={market === value ? 'active' : ''} aria-pressed={market === value}
        onClick={() => { setMarket(value); setPage(1) }}>{value}</button>)}
    </nav>
    <div className='market-table-panel'>
      <div className='market-table-scroll' role='region' aria-label='국내 주식 목록' tabIndex={0}>
        <table className='market-table'>
          <caption className='sr-only'>지원 국내 종목의 현재가와 등락률</caption>
          <thead><tr><th scope='col'>관심</th><th scope='col'>종목명</th><th scope='col'>종목코드</th><th scope='col'>현재가</th><th scope='col'>등락률</th><th scope='col'><span className='sr-only'>상세 보기</span></th></tr></thead>
          <tbody>{visible.map(stock => <StockRow key={stock.symbol} stock={stock}
            quote={trades[stock.symbol] || quotes.data[stock.symbol]} error={trades[stock.symbol] ? null : quotes.errors[stock.symbol]}
            loading={quotes.loading && !trades[stock.symbol] && !quotes.data[stock.symbol]}
            saved={Boolean(user && watchlist.includes(stock.symbol))} disabled={!ready || saving}
            onToggle={toggle} onSelect={onSelectStock} />)}</tbody>
        </table>
      </div>
      {!filtered.length && <p className='market-empty' role='status'>아직 지원하는 {market} 종목이 없습니다.</p>}
      {pageCount > 1 && <nav className='market-pagination' aria-label='종목 목록 페이지'>
        {Array.from({ length: pageCount }, (_, i) => i + 1).map(value => <button key={value} type='button'
          aria-current={page === value ? 'page' : undefined} onClick={() => setPage(value)}>{value}</button>)}
      </nav>}
      <div className='market-list-footer'><span>종목을 선택하면 차트·실적·산업 정책을 확인할 수 있습니다.</span>
        {visible.some(stock => quotes.errors[stock.symbol] && !trades[stock.symbol])
          ? <button type='button' onClick={quotes.retry} disabled={quotes.loading}>시세 다시 조회</button>
          : <span>KIS 시세 · 조회되지 않은 값은 — 표시</span>}
      </div>
    </div>
    {error && <p className='financial-state' role='alert'>{error}</p>}
  </section>
}
