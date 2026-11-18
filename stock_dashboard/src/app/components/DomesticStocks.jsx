'use client'

import { useState } from 'react'
import { FaChevronRight } from 'react-icons/fa'
import { changeRateClass, marketStocks } from '../lib/market'
import useStockStore from '../store/useStockStore'
import useStockListQuotes from '../hooks/useStockListQuotes'
import useStockCandles from '../hooks/useStockCandles'
import InvestorRanking from './InvestorRanking'
import WatchStar from './WatchStar'

const PAGE_SIZE = 10
const RANKING_TAB = '투자자별 순위'

function PriceSparkline({ symbol }) {
  const { data, loading } = useStockCandles(symbol, 'D', 45)
  const closes = data.filter(candle => Number.isFinite(candle.close) && candle.close > 0).slice(-10).map(candle => candle.close)
  if (closes.length < 2) return <span className='market-sparkline-empty' aria-label={loading ? '차트 조회 중' : '차트 자료 없음'}>—</span>

  const low = Math.min(...closes)
  const high = Math.max(...closes)
  const range = high - low
  const points = closes.map((close, index) => `${2 + index * 80 / (closes.length - 1)},${range ? 22 - (close - low) * 20 / range : 12}`)
  const change = closes.at(-1) - closes[0]
  return <svg className='market-sparkline' viewBox='0 0 84 24' role='img'
    aria-label={`최근 ${closes.length}거래일 종가 ${change > 0 ? '상승' : change < 0 ? '하락' : '보합'}`}>
    <polyline points={points.join(' ')} fill='none' stroke={change > 0 ? '#c43b3b' : change < 0 ? '#1769aa' : '#777'}
      strokeWidth='1.5' strokeLinecap='round' strokeLinejoin='round' vectorEffect='non-scaling-stroke' />
  </svg>
}

function StockRow({ stock, quote, error, loading, onSelect }) {
  const price = Number.isFinite(quote?.price) ? quote.price : null
  const rate = Number.isFinite(quote?.changeRate) ? quote.changeRate : null
  const title = error || quote?.exchangeTime || quote?.fetchedAt || undefined
  return <tr>
    <td className='market-star-cell'>
      <WatchStar symbol={stock.symbol} name={stock.name} />
    </td>
    <th scope='row'><button className='market-stock-name' type='button' onClick={() => onSelect(stock)}>
      {stock.name}<small className='market-compact-code'>{stock.symbol}</small>
    </button></th>
    <td className='market-code'>{stock.symbol}</td>
    <td className='market-trend'><PriceSparkline symbol={stock.symbol} /></td>
    <td className='market-number' title={title} aria-busy={loading}>
      {price == null ? '—' : `${price.toLocaleString('ko-KR')}원`}
      {error && <span className='sr-only'>시세 조회 실패</span>}
    </td>
    <td className={`market-number ${changeRateClass(rate)}`}>
      {rate == null ? '—' : `${rate > 0 ? '+' : ''}${rate.toFixed(2)}%`}
    </td>
    <td className='market-open-cell'><button type='button' className='market-open' onClick={() => onSelect(stock)}
      aria-label={`${stock.name} 상세 보기`}><FaChevronRight aria-hidden='true' /></button></td>
  </tr>
}

export default function DomesticStocks({ trades, onSelectStock }) {
  const [market, setMarket] = useState('전체')
  const [page, setPage] = useState(1)
  const [sort, setSort] = useState(null)
  const error = useStockStore(state => state.watchlistError)
  const showsRanking = market === RANKING_TAB
  const filtered = marketStocks.filter(stock => market === '전체' || stock.market === market)
  const quotes = useStockListQuotes(filtered.map(stock => stock.symbol), trades)
  const quoteFor = symbol => trades[symbol] || quotes.data[symbol]
  const pageCount = Math.ceil(filtered.length / PAGE_SIZE)
  const currentPage = Math.min(page, Math.max(1, pageCount))
  const ordered = sort ? filtered.toSorted((a, b) => {
    const first = quoteFor(a.symbol)?.[sort.key]
    const second = quoteFor(b.symbol)?.[sort.key]
    if (!Number.isFinite(first)) return Number.isFinite(second) ? 1 : 0
    if (!Number.isFinite(second)) return -1
    return sort.direction === 'descending' ? second - first : first - second
  }) : filtered
  const visible = ordered.slice((currentPage - 1) * PAGE_SIZE, currentPage * PAGE_SIZE)

  function sortColumn(key) {
    setSort(previous => ({ key, direction: previous?.key === key && previous.direction === 'descending' ? 'ascending' : 'descending' }))
    setPage(1)
  }

  return <section className='domestic-market' aria-labelledby='domestic-market-title'>
    <div className='market-title'><h1 id='domestic-market-title'>국내 주식</h1><span>{showsRanking ? marketStocks.length : filtered.length}개 종목</span></div>
    <nav className='market-tabs' aria-label='국내 주식 보기'>
      {['전체', 'KOSPI', RANKING_TAB].map(value => <button key={value} type='button'
        className={market === value ? 'active' : ''} aria-pressed={market === value}
        onClick={() => { setMarket(value); setPage(1) }}>{value}</button>)}
    </nav>
    {showsRanking ? <InvestorRanking onSelectStock={onSelectStock} /> : <div className='market-table-panel'>
      <div className='market-table-scroll' role='region' aria-label='국내 주식 목록' tabIndex={0}>
        <table className='market-table market-stock-table'>
          <caption className='sr-only'>지원 국내 종목의 현재가와 등락률</caption>
          <thead><tr><th scope='col'>관심</th><th scope='col'>종목명</th><th scope='col'>종목코드</th>
            <th scope='col' className='market-trend'>최근 10일</th>
            {[['price', '현재가'], ['changeRate', '등락률']].map(([key, label]) => {
              const direction = sort?.key === key ? sort.direction : undefined
              return <th key={key} scope='col' aria-sort={direction}>
                <button className='market-sort' type='button' onClick={() => sortColumn(key)}>{label}</button>
              </th>
            })}
            <th scope='col'><span className='sr-only'>상세 보기</span></th></tr></thead>
          <tbody>{visible.map(stock => <StockRow key={stock.symbol} stock={stock}
            quote={quoteFor(stock.symbol)} error={trades[stock.symbol] ? null : quotes.errors[stock.symbol]}
            loading={quotes.loading && !trades[stock.symbol] && !quotes.data[stock.symbol]}
            onSelect={onSelectStock} />)}</tbody>
        </table>
      </div>
      {!filtered.length && <p className='market-empty' role='status'>
        {`아직 지원하는 ${market} 종목이 없습니다.`}
      </p>}
      {pageCount > 1 && <nav className='market-pagination' aria-label='종목 목록 페이지'>
        {Array.from({ length: pageCount }, (_, i) => i + 1).map(value => <button key={value} type='button'
          aria-current={currentPage === value ? 'page' : undefined} onClick={() => setPage(value)}>{value}</button>)}
      </nav>}
      {visible.length > 0 && <div className='market-list-footer'><span>KIS 시세</span>
        {visible.some(stock => quotes.errors[stock.symbol] && !trades[stock.symbol]) &&
          <button type='button' onClick={quotes.retry} disabled={quotes.loading}>시세 다시 조회</button>}
      </div>}
    </div>}
    {error && <p className='financial-state' role='alert'>{error}</p>}
  </section>
}
