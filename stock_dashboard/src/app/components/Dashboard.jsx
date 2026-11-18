'use client'

import { lazy, Suspense, useState } from 'react'
import Link from 'next/link'
import { useRouter, useSearchParams } from 'next/navigation'
import EarningsPanel from './EarningsPanel'
import InvestorTradesPanel from './InvestorTradesPanel'
import DividendPanel from './DividendPanel'
import DomesticStocks from './DomesticStocks'
import CompanyCalendar from './CompanyCalendar'
import RecommendationChart from './RecommendationChart'
import StockChart from './StockChart'
import StockChatDrawer from './StockChatDrawer'
import StockInfo from './StockInfo'
import StockNewsFeed from './StockNewsFeed'
import StockSearch from './StockSearch'
import WatchlistPanel from './WatchlistPanel'
import AuthNav from './AuthNav'
import useRealtimeQuotes from '../hooks/useRealtimeQuotes'
import useRecentStock from '../hooks/useRecentStock'
import { marketStocks } from '../lib/market'

const LazyPolicyFeed = lazy(() => import('./PolicyFeed'))
const detailTabs = [
  { id: 'chart', label: '시세·차트' },
  { id: 'info', label: '종목정보' },
  { id: 'policy', label: '정책·뉴스' },
]
const policyTabs = [
  { id: 'trends', label: '산업·정책 동향' },
  { id: 'news', label: '최신 기사' },
]

export default function Dashboard({ initialStock }) {
  const router = useRouter()
  const params = useSearchParams()
  const isCalendar = params.get('view') === 'calendar'
  const isList = !isCalendar && !params.has('symbol')
  const isDetail = !isCalendar && !isList
  const selectedStock = marketStocks.find(stock => stock.symbol === params.get('symbol')) || initialStock || marketStocks[0]
  const recentSymbols = useRecentStock(isDetail ? selectedStock.symbol : null)
  const recentStock = recentSymbols.map(symbol => marketStocks.find(stock => stock.symbol === symbol)).find(Boolean)
  const [intervalSeconds, setIntervalSeconds] = useState(60)
  const [detailTab, setDetailTab] = useState('chart')
  const [policyTab, setPolicyTab] = useState('trends')
  const stream = useRealtimeQuotes(intervalSeconds)
  const chatStock = recentStock || selectedStock

  const handleSelectStock = (stock) => {
    const normalizedStock = {
      ...stock,
      symbol: String(stock.symbol),
      name: stock.name || String(stock.symbol),
    }

    router.push(`/?symbol=${encodeURIComponent(normalizedStock.symbol)}`)
  }

  const handleTabKeyDown = (event, index, tabs, selectTab) => {
    if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return
    event.preventDefault()
    let nextIndex = index
    if (event.key === 'ArrowLeft') nextIndex = (index - 1 + tabs.length) % tabs.length
    if (event.key === 'ArrowRight') nextIndex = (index + 1) % tabs.length
    if (event.key === 'Home') nextIndex = 0
    if (event.key === 'End') nextIndex = tabs.length - 1
    selectTab(tabs[nextIndex].id)
    event.currentTarget.parentElement.querySelectorAll('[role="tab"]')[nextIndex]?.focus()
  }

  const view = isCalendar ? 'calendar' : isList ? 'list' : 'detail'

  return (
    <div className='app-frame'>
      <a className='skip-link' href='#workspace-main'>본문으로 건너뛰기</a>
      <header className='topbar'>
        {isDetail && <h1 className='sr-only'>종목 상세 정보</h1>}
        <Link className='brand-wordmark' href='/' translate='no'>K-FinCheck</Link>
        <nav className='topbar-nav' aria-label='주요 메뉴'>
          <Link className={`market-nav-link${isList ? ' active' : ''}`} href='/' aria-current={isList ? 'page' : undefined}>국내 주식</Link>
          {recentStock
            ? <Link className={`market-nav-link recent-stock-link${isDetail ? ' active' : ''}`}
              href={`/?symbol=${recentStock.symbol}`} title={recentStock.name} aria-current={isDetail ? 'page' : undefined}>
              최근 본 종목<span className='nav-context'>{recentStock.name}</span></Link>
            : <span className='market-nav-link recent-stock-link unavailable' title='종목을 선택하면 다시 열 수 있습니다.'>최근 본 종목</span>}
          <Link className={`market-nav-link calendar-nav-link${isCalendar ? ' active' : ''}`}
            href='/?view=calendar' aria-current={isCalendar ? 'page' : undefined}>기업 일정</Link>
        </nav>
        <StockSearch onSelectStock={handleSelectStock} />
        <AuthNav symbol={selectedStock.symbol} returnTo={isCalendar ? '/?view=calendar' : isList ? '/' : undefined} />
      </header>

      <div className='app-body'>
        <main className='workspace-main' id='workspace-main' tabIndex={-1}>
          {/* 화면 종류가 바뀔 때만 새로 그려 진입 전환을 보여 준다. 같은 화면 안의 종목 변경은 그대로 유지한다. */}
          <div className={`view view-${view}`} key={view}>
        {isList && <DomesticStocks trades={stream.trades} onSelectStock={handleSelectStock} />}
        {isCalendar && <CompanyCalendar />}
        {isDetail && <section className='primary-column' aria-label='선택 종목 정보'>
          <StockInfo key={selectedStock.symbol} stock={selectedStock} liveQuote={stream.trades[selectedStock.symbol]} />

          <nav className='detail-tabs' role='tablist' aria-label='종목 상세 메뉴'>
            {detailTabs.map((tab, index) => <button
              id={`detail-tab-${tab.id}`}
              key={tab.id}
              type='button'
              role='tab'
              aria-selected={detailTab === tab.id}
              aria-controls={`detail-panel-${tab.id}`}
              tabIndex={detailTab === tab.id ? 0 : -1}
              onClick={() => setDetailTab(tab.id)}
              onKeyDown={event => handleTabKeyDown(event, index, detailTabs, setDetailTab)}
            >{tab.label}</button>)}
          </nav>

          {detailTab === 'chart' && <section id='detail-panel-chart' className='detail-info-grid detail-tab-panel'
            role='tabpanel' aria-labelledby='detail-tab-chart'>
            <section className='panel chart-panel'>
              <div className='panel-heading'><h2>주가 차트</h2></div>
              <StockChart
                symbol={selectedStock.symbol}
                liveQuote={stream.trades[selectedStock.symbol]}
                liveCandles={stream.candles?.[selectedStock.symbol]}
                streamInterval={stream.intervalSeconds}
                onIntervalChange={setIntervalSeconds}
              />
            </section>
            <section className='panel'>
              <div className='panel-heading'><h2>투자자별 매매 동향</h2></div>
              <InvestorTradesPanel key={selectedStock.symbol} symbol={selectedStock.symbol} />
            </section>
          </section>}

          {detailTab === 'info' && <section id='detail-panel-info' className='detail-info-grid detail-tab-panel'
            role='tabpanel' aria-labelledby='detail-tab-info'>
              <section className='panel compact-panel'>
                <div className='panel-heading'><h2>증권사 목표주가</h2></div>
                <RecommendationChart key={selectedStock.symbol} symbol={selectedStock.symbol} liveQuote={stream.trades[selectedStock.symbol]} />
              </section>
              <section className='panel compact-panel'>
                <div className='panel-heading'><h2>기업 실적</h2></div>
                <EarningsPanel symbol={selectedStock.symbol} />
              </section>
              <section className='panel compact-panel'>
                <div className='panel-heading'><h2>배당 내역</h2></div>
                <DividendPanel key={selectedStock.symbol} symbol={selectedStock.symbol} />
              </section>
          </section>}

          {detailTab === 'policy' && <section id='detail-panel-policy' className='detail-info-grid detail-tab-panel'
            role='tabpanel' aria-labelledby='detail-tab-policy'>
              <div className='detail-subtabs' role='tablist' aria-label='정책·뉴스 자료 구분'>
                {policyTabs.map((tab, index) => <button
                  key={tab.id}
                  id={`policy-tab-${tab.id}`}
                  type='button'
                  role='tab'
                  aria-selected={policyTab === tab.id}
                  aria-controls={`policy-panel-${tab.id}`}
                  tabIndex={policyTab === tab.id ? 0 : -1}
                  onClick={() => setPolicyTab(tab.id)}
                  onKeyDown={event => handleTabKeyDown(event, index, policyTabs, setPolicyTab)}
                >{tab.label}</button>)}
              </div>
              <section id='policy-panel-trends' className='panel' role='tabpanel' aria-labelledby='policy-tab-trends' hidden={policyTab !== 'trends'}>
                {policyTab === 'trends' && <Suspense fallback={<PolicySkeleton />}>
                  <LazyPolicyFeed key={selectedStock.symbol} symbol={selectedStock.symbol} />
                </Suspense>}
              </section>
              <section id='policy-panel-news' className='panel' role='tabpanel' aria-labelledby='policy-tab-news' hidden={policyTab !== 'news'}>
                {policyTab === 'news' && <StockNewsFeed key={selectedStock.symbol} symbol={selectedStock.symbol} />}
              </section>
          </section>}
        </section>}
          </div>
        </main>
        {/* 오른쪽 열: AI 질문 버튼 + 관심 종목. 둘 다 한 곳에서만 그려 화면을 옮겨도 대화 상태와 관심 종목 조회가 유지된다. */}
        <div className='side-column'>
          <StockChatDrawer stock={chatStock} />
          <aside className='side-panel' aria-label='관심 종목'>
            <WatchlistPanel activeSymbol={isDetail ? selectedStock.symbol : undefined}
              onSelectStock={handleSelectStock} trades={stream.trades} />
          </aside>
        </div>
      </div>
    </div>
  )
}

function PolicySkeleton() {
  return (
    <div className='skeleton-list' aria-label='산업·정책 자료를 불러오는 중'>
      {[1, 2, 3].map((item) => <span key={item} className='skeleton-row' />)}
    </div>
  )
}
