'use client'

import { useEffect, useState } from 'react'
import AuthNav from './AuthNav'
import ConnectionBadge from './ConnectionBadge'
import EarningsPanel from './EarningsPanel'
import InvestOpinionPanel from './InvestOpinionPanel'
import MarketTicker from './MarketTicker'
import NewsFeed from './NewsFeed'
import StockChart from './StockChart'
import StockChatDrawer from './StockChatDrawer'
import StockInfo from './StockInfo'
import StockSearch from './StockSearch'
import WatchlistPanel from './WatchlistPanel'
import useMarketFeed from '../hooks/useMarketFeed'
import useStockStore from '../store/useStockStore'

const DEFAULT_CODE = '005930'

export default function Dashboard({ initialCode }) {
  useMarketFeed()

  const loadAccount = useStockStore((state) => state.loadAccount)
  useEffect(() => {
    loadAccount()
  }, [loadAccount])

  const stocks = useStockStore((state) => state.stocks)
  const stocksError = useStockStore((state) => state.stocksError)
  const [requestedCode, setSelectedCode] = useState(initialCode || DEFAULT_CODE)

  // 주소의 종목이 지원 종목이 아니면 삼성전자(없으면 첫 번째 지원 종목)로
  const selectedCode = stocks.length === 0 || stocks.some((stock) => stock.code === requestedCode)
    ? requestedCode
    : stocks.some((stock) => stock.code === DEFAULT_CODE) ? DEFAULT_CODE : stocks[0].code

  const selectedStock = stocks.find((stock) => stock.code === selectedCode) || { code: selectedCode, name: '' }

  const handleSelectStock = (code) => {
    setSelectedCode(code)
    window.history.replaceState({}, '', `/?symbol=${encodeURIComponent(code)}`)
    window.scrollTo({ top: 0, behavior: 'smooth' })
  }

  return (
    <main className='app-shell'>
      <header className='topbar'>
        <h1 className='sr-only'>국내 주식 대시보드</h1>
        <div className='topbar-actions'>
          <AuthNav symbol={selectedCode} />
          <ConnectionBadge />
        </div>
        <StockSearch onSelectStock={handleSelectStock} />
      </header>

      {stocksError && stocks.length === 0 && (
        <p className='global-error' role='alert'>
          종목 목록을 불러오지 못했습니다. 백엔드(8080)와 DB 가 켜져 있는지 확인해 주세요. 자동으로 다시 시도합니다.
          <small>{stocksError}</small>
        </p>
      )}

      <MarketTicker activeCode={selectedCode} onSelectStock={handleSelectStock} />

      <div className='dashboard-layout'>
        <section className='primary-column' aria-label='선택 종목 정보'>
          <StockInfo key={selectedCode} stock={selectedStock} />

          <section className='panel chart-panel'>
            <div className='panel-heading'>
              <h2>주가 차트</h2>
            </div>
            <StockChart key={selectedCode} code={selectedCode} />
          </section>

          <section className='panel news-panel'>
            <div className='panel-heading'>
              <h2>{selectedStock.name || selectedCode} 관련 뉴스</h2>
            </div>
            <NewsFeed key={selectedCode} code={selectedCode} />
          </section>
        </section>

        <aside className='insight-column' aria-label='추가 종목 정보'>
          <StockChatDrawer stock={{ symbol: selectedCode, name: selectedStock.name }} />
          <WatchlistPanel activeCode={selectedCode} onSelectStock={handleSelectStock} />

          <section className='panel compact-panel'>
            <div className='panel-heading'><h2>증권사 투자의견</h2></div>
            <InvestOpinionPanel key={selectedCode} code={selectedCode} />
          </section>

          <section className='panel compact-panel'>
            <div className='panel-heading'><h2>최근 실적</h2></div>
            <EarningsPanel key={selectedCode} code={selectedCode} />
          </section>
        </aside>
      </div>
    </main>
  )
}
