'use client'

import { lazy, Suspense, useState } from 'react'
import EarningsPanel from './EarningsPanel'
import MarketTicker from './MarketTicker'
import RecommendationChart from './RecommendationChart'
import StockChart from './StockChart'
import StockChatDrawer from './StockChatDrawer'
import StockInfo from './StockInfo'
import StockSearch from './StockSearch'
import WatchlistPanel from './WatchlistPanel'
import AuthNav from './AuthNav'
import useRealtimeQuotes from '../hooks/useRealtimeQuotes'
import { marketStocks } from '../lib/market'

const LazyNewsFeed = lazy(() => import('./NewsFeed'))

export default function Dashboard({ initialStock }) {
  const [selectedStock, setSelectedStock] = useState(initialStock || marketStocks[0])
  const [streamMode, setStreamMode] = useState('push')
  const [intervalSeconds, setIntervalSeconds] = useState(1)
  const stream = useRealtimeQuotes(intervalSeconds, streamMode)

  const handleSelectStock = (stock) => {
    const normalizedStock = {
      ...stock,
      symbol: String(stock.symbol),
      name: stock.name || String(stock.symbol),
    }

    setSelectedStock(normalizedStock)
    window.history.replaceState({}, '', `/?symbol=${encodeURIComponent(normalizedStock.symbol)}`)
    window.scrollTo({ top: 0, behavior: 'smooth' })
  }

  return (
    <main className='app-shell'>
      <header className='topbar'>
        <h1 className='sr-only'>주식 대시보드</h1>
        <AuthNav symbol={selectedStock.symbol} />
        <StockSearch onSelectStock={handleSelectStock} />
      </header>

      <MarketTicker
        activeSymbol={selectedStock.symbol}
        onSelectStock={handleSelectStock}
        trades={stream.trades}
      />

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
