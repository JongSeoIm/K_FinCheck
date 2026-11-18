'use client'

import { useState } from 'react'
import { FaArrowDown, FaArrowUp } from 'react-icons/fa'
import { changeRateClass, marketStocks } from '../lib/market'
import useStockStore from '../store/useStockStore'
import useStockListQuotes from '../hooks/useStockListQuotes'
import useStockCandles from '../hooks/useStockCandles'
import InvestorRanking from './InvestorRanking'
import StockAvatar from './StockAvatar'
import WatchStar from './WatchStar'

const PAGE_SIZE = 10
const RANKING_TAB = '투자자별 순위'
const SORTS = [['price', '현재가순'], ['changeRate', '등락률순']]

function PriceSparkline({ symbol }) {
  const { data, loading } = useStockCandles(symbol, 'D', 45)
  const closes = data.filter(candle => Number.isFinite(candle.close) && candle.close > 0).slice(-10).map(candle => candle.close)
  if (closes.length < 2) return <span className='market-sparkline-empty' aria-label={loading ? '차트 조회 중' : '차트 자료 없음'}>-</span>

  const low = Math.min(...closes)
  const high = Math.max(...closes)
  const range = high - low
  const points = closes.map((close, index) => `${2 + index * 80 / (closes.length - 1)},${range ? 22 - (close - low) * 20 / range : 12}`)
  const change = closes.at(-1) - closes[0]
  return <svg className='market-sparkline' viewBox='0 0 84 24' role='img'
    aria-label={`최근 ${closes.length}거래일 종가 ${change > 0 ? '상승' : change < 0 ? '하락' : '보합'}`}>
    <polyline points={points.join(' ')} fill='none' stroke={change > 0 ? 'var(--up)' : change < 0 ? 'var(--down)' : 'var(--flat)'}
      strokeWidth='1.5' strokeLinecap='round' strokeLinejoin='round' vectorEffect='non-scaling-stroke' />
  </svg>
}

// list-row: 순위 + 아바타 + 이름/코드 + 10일 흐름 + 오른쪽 현재가/등락률. 별(관심 종목)은 행 버튼 밖에 둔다.
function StockRow({ rank, stock, quote, error, loading, onSelect }) {
  const price = Number.isFinite(quote?.price) ? quote.price : null
  const rate = Number.isFinite(quote?.changeRate) ? quote.changeRate : null
  const title = error || quote?.exchangeTime || quote?.fetchedAt || undefined
  return <li className='stock-row'>
    <button className='stock-row-main' type='button' onClick={() => onSelect(stock)}>
      <span className='stock-row-rank'>{rank}</span>
      <StockAvatar symbol={stock.symbol} name={stock.name} />
      <span className='stock-row-title'><strong>{stock.name}</strong><small>{stock.symbol}</small></span>
      <span className='stock-row-trend'><PriceSparkline symbol={stock.symbol} /></span>
      <span className='stock-row-amount' title={title} aria-busy={loading}>
        <strong>{price == null ? '-' : `${price.toLocaleString('ko-KR')}원`}</strong>
        <small className={changeRateClass(rate)}>{rate == null ? '-' : `${rate > 0 ? '+' : ''}${rate.toFixed(2)}%`}</small>
        {error && <span className='sr-only'>시세 조회 실패</span>}
      </span>
    </button>
    <span className='stock-row-star'><WatchStar symbol={stock.symbol} name={stock.name} /></span>
  </li>
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
  const offset = (currentPage - 1) * PAGE_SIZE
  const visible = ordered.slice(offset, offset + PAGE_SIZE)
  // 목록에 이미 표시한 등락률만 세어 상승·보합·하락 분포를 보여 준다(새 조회 없음).
  const breadth = filtered.reduce((count, stock) => {
    const rate = quoteFor(stock.symbol)?.changeRate
    if (Number.isFinite(rate)) count[rate > 0 ? 'up' : rate < 0 ? 'down' : 'flat'] += 1
    return count
  }, { up: 0, flat: 0, down: 0 })
  const counted = breadth.up + breadth.flat + breadth.down

  function sortColumn(key) {
    setSort(previous => ({ key, direction: previous?.key === key && previous.direction === 'descending' ? 'ascending' : 'descending' }))
    setPage(1)
  }

  return <section className='domestic-market' aria-labelledby='domestic-market-title'>
    <div className='market-title'><h1 id='domestic-market-title'>국내 주식</h1><span>{showsRanking ? marketStocks.length : filtered.length}개 종목</span></div>

    {!showsRanking && counted > 0 && <section className='market-breadth' aria-label={`시세를 받은 ${counted}개 종목의 등락`}>
      <dl className='market-summary'>
        {[['상승', 'up', 'change-rate-up', '▲'], ['보합', 'flat', 'breadth-flat', ''], ['하락', 'down', 'change-rate-down', '▼']]
          .map(([label, key, tone, mark]) => <div key={key}>
            <dt>{label}</dt>
            <dd className={tone}>
              {mark && <span className='breadth-mark' aria-hidden='true'>{mark}</span>}
              {breadth[key]}<span className='breadth-unit'>종목</span>
              <span className='breadth-pct'>{Math.round(breadth[key] / counted * 1000) / 10}%</span>
            </dd>
          </div>)}
      </dl>
      {/* 상승·보합·하락 개수 비율을 한 줄 막대로 보여 준다(장식, 숫자는 위 목록이 읽힌다). */}
      <div className='breadth-bar' aria-hidden='true'>
        {['up', 'flat', 'down'].map(key => breadth[key] > 0 &&
          <span key={key} className={`breadth-bar-${key}`} style={{ flexGrow: breadth[key] }} />)}
      </div>
    </section>}

    <div className='market-controls'>
      <nav className='market-tabs' aria-label='국내 주식 보기'>
        {['전체', 'KOSPI', RANKING_TAB].map(value => <button key={value} type='button'
          className={market === value ? 'active' : ''} aria-pressed={market === value}
          onClick={() => { setMarket(value); setPage(1) }}>{value}</button>)}
      </nav>
      {!showsRanking && filtered.length > 0 && <div className='sort-chips' role='group' aria-label='목록 정렬'>
        {SORTS.map(([key, label]) => {
          const direction = sort?.key === key ? sort.direction : undefined
          return <button key={key} type='button' className='chip' aria-pressed={Boolean(direction)} onClick={() => sortColumn(key)}>
            {label}
            {direction === 'descending' && <FaArrowDown className='chip-icon' aria-hidden='true' />}
            {direction === 'ascending' && <FaArrowUp className='chip-icon' aria-hidden='true' />}
            {direction && <span className='sr-only'>{direction === 'descending' ? ' 높은 순' : ' 낮은 순'}</span>}
          </button>
        })}
      </div>}
    </div>

    {showsRanking ? <InvestorRanking onSelectStock={onSelectStock} /> : <div className='list-card'>
      {visible.length > 0 && <ul className='stock-list' aria-label='국내 주식 목록'>
        {visible.map((stock, index) => <StockRow key={stock.symbol} rank={offset + index + 1} stock={stock}
          quote={quoteFor(stock.symbol)} error={trades[stock.symbol] ? null : quotes.errors[stock.symbol]}
          loading={quotes.loading && !trades[stock.symbol] && !quotes.data[stock.symbol]}
          onSelect={onSelectStock} />)}
      </ul>}
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
