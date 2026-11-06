'use client'

import { lazy, Suspense, useState } from 'react'
import Link from 'next/link'
import { useRouter, useSearchParams } from 'next/navigation'
import EarningsPanel from './EarningsPanel'
import DomesticStocks from './DomesticStocks'
import CompanyCalendar from './CompanyCalendar'
import RecommendationChart from './RecommendationChart'
import StockChart from './StockChart'
import StockChatDrawer from './StockChatDrawer'
import StockInfo from './StockInfo'
import StockSearch from './StockSearch'
import WatchlistPanel from './WatchlistPanel'
import AuthNav from './AuthNav'
import useRealtimeQuotes from '../hooks/useRealtimeQuotes'
import useRecentStock from '../hooks/useRecentStock'
import { marketStocks } from '../lib/market'

const LazyNewsFeed = lazy(() => import('./NewsFeed'))

export default function Dashboard({ initialStock }) {
  const router = useRouter()
  const params = useSearchParams()
  const isCalendar = params.get('view') === 'calendar'
  const isList = !isCalendar && !params.has('symbol')
  const isDetail = !isCalendar && !isList
  const selectedStock = marketStocks.find(stock => stock.symbol === params.get('symbol')) || initialStock || marketStocks[0]
  const recentSymbol = useRecentStock(isDetail ? selectedStock.symbol : null)
  const recentStock = marketStocks.find(stock => stock.symbol === recentSymbol)
  const [streamMode, setStreamMode] = useState('push')
  const [intervalSeconds, setIntervalSeconds] = useState(60)
  const stream = useRealtimeQuotes(intervalSeconds, streamMode)

  const handleSelectStock = (stock) => {
    const normalizedStock = {
      ...stock,
      symbol: String(stock.symbol),
      name: stock.name || String(stock.symbol),
    }

    router.push(`/?symbol=${encodeURIComponent(normalizedStock.symbol)}`)
  }

  return (
    <main className='app-shell'>
      <header className='topbar'>
        {isDetail && <h1 className='sr-only'>종목 상세 정보</h1>}
        <div className='topbar-navigation'>
          <Link className={`market-nav-link${isList ? ' active' : ''}`} href='/' aria-current={isList ? 'page' : undefined}>국내 주식</Link>
          {recentStock
            ? <Link className={`market-nav-link recent-stock-link${isDetail ? ' active' : ''}`}
              href={`/?symbol=${recentStock.symbol}`} title={recentStock.name} aria-current={isDetail ? 'page' : undefined}>최근 본 종목</Link>
            : <span className='market-nav-link recent-stock-link unavailable' title='종목을 선택하면 다시 열 수 있습니다.'>최근 본 종목</span>}
          <Link className={`market-nav-link calendar-nav-link${isCalendar ? ' active' : ''}`}
            href='/?view=calendar' aria-current={isCalendar ? 'page' : undefined}>기업 일정</Link>
        </div>
        <div className='topbar-actions'>
          <StockSearch onSelectStock={handleSelectStock} />
          <AuthNav symbol={selectedStock.symbol} returnTo={isCalendar ? '/?view=calendar' : isList ? '/' : undefined} />
        </div>
      </header>

      {isCalendar || isList ? <div className='dashboard-layout market-layout'>
        {isCalendar ? <CompanyCalendar /> : <DomesticStocks trades={stream.trades} onSelectStock={handleSelectStock} />}
        <aside className='insight-column' aria-label='관심 종목'>
          <WatchlistPanel onSelectStock={handleSelectStock} trades={stream.trades} />
        </aside>
      </div> : (
      <div className='dashboard-layout'>
        <section className='primary-column' aria-label='선택 종목 정보'>
          <StockInfo key={selectedStock.symbol} stock={selectedStock} liveQuote={stream.trades[selectedStock.symbol]} />

          <section className='panel chart-panel'>
            <div className='panel-heading'>
              <h2>주가 차트</h2>
              <div className='stream-mode' aria-label='시세 전달 방식'>
                <button type='button' className={streamMode === 'push' ? 'active' : ''} onClick={() => setStreamMode('push')}>Push</button>
                <span className='tab-separator' aria-hidden='true'>·</span>
                <button type='button' className={streamMode === 'poll' ? 'active' : ''} onClick={() => setStreamMode('poll')}>Polling</button>
              </div>
            </div>
            <StockChart
              symbol={selectedStock.symbol}
              liveQuote={stream.trades[selectedStock.symbol]}
              liveCandles={stream.candles?.[selectedStock.symbol]}
              streamInterval={stream.intervalSeconds}
              streamStatus={stream.status}
              supported={stream.supportedSymbols.includes(selectedStock.symbol)}
              onIntervalChange={setIntervalSeconds}
            />
          </section>

          <section className='panel news-panel'>
            <div className='panel-heading'>
              <h2>{selectedStock.name || selectedStock.symbol} 관련 뉴스</h2>
            </div>
            <Suspense fallback={<NewsSkeleton />}>
              <LazyNewsFeed key={selectedStock.symbol} symbol={selectedStock.symbol} />
            </Suspense>
          </section>
        </section>

        <aside className='insight-column' aria-label='추가 종목 정보'>
          <StockChatDrawer stock={selectedStock} />
          <WatchlistPanel
            activeSymbol={selectedStock.symbol}
            onSelectStock={handleSelectStock}
            trades={stream.trades}
          />

          <section className='panel compact-panel'>
            <div className='panel-heading'><h2>증권사 투자의견</h2></div>
            <RecommendationChart key={selectedStock.symbol} symbol={selectedStock.symbol} liveQuote={stream.trades[selectedStock.symbol]} />
          </section>

          <section className='panel compact-panel'>
            <div className='panel-heading'><h2>최근 실적</h2></div>
            <EarningsPanel symbol={selectedStock.symbol} />
          </section>
        </aside>
      </div>
      )}
    </main>
  )
}

function NewsSkeleton() {
  return (
    <div className='skeleton-list' aria-label='뉴스를 불러오는 중'>
      {[1, 2, 3].map((item) => <span key={item} className='skeleton-row' />)}
    </div>
  )
}
