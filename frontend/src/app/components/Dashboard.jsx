'use client'

import { useCallback, useEffect, useMemo, useState } from 'react'
import { stockApi } from '@/api/stockApi'
import useHydrated from '@/app/hooks/useHydrated'
import useMarketStatus from '@/app/hooks/useMarketStatus'
import useQuoteFeed from '@/app/hooks/useQuoteFeed'
import useAuthStore from '@/app/store/useAuthStore'
import useStockStore from '@/app/store/useStockStore'
import AuthMenu from './AuthMenu'
import EarningsPanel from './EarningsPanel'
import FeedStatusBar from './FeedStatusBar'
import MarketTicker from './MarketTicker'
import NewsFeed from './NewsFeed'
import OpinionPanel from './OpinionPanel'
import StockChart from './StockChart'
import StockChatDrawer from './StockChatDrawer'
import StockInfo from './StockInfo'
import StockSearch from './StockSearch'
import WatchlistPanel from './WatchlistPanel'

const DEFAULT_SYMBOL = '005930' // 처음 화면에 보여줄 종목 (삼성전자)

export default function Dashboard({ initialSymbol }) {
  const hydrated = useHydrated()
  const [stocks, setStocks] = useState([])
  const [selected, setSelected] = useState(null)
  const [loadError, setLoadError] = useState('')
  const [reloadKey, setReloadKey] = useState(0)

  const feedMode = useStockStore((state) => state.feedMode)
  const watchlist = useStockStore((state) => state.watchlist)
  const loadWatchlist = useStockStore((state) => state.loadWatchlist)
  const clearWatchlist = useStockStore((state) => state.clearWatchlist)
  const userId = useAuthStore((state) => state.user?.userId)
  const { status: marketStatus, error: statusError } = useMarketStatus()

  // 로그인한 계정의 관심 종목을 서버에서 불러오고, 로그아웃하면 비운다
  useEffect(() => {
    if (userId) loadWatchlist()
    else clearWatchlist()
  }, [userId, loadWatchlist, clearWatchlist])

  // 지원 종목 목록 (티커·기본 선택 종목)
  useEffect(() => {
    const controller = new AbortController()
    stockApi.search('', { signal: controller.signal })
      .then((list) => {
        setStocks(list)
        setLoadError('')
        setSelected((current) => current
          || list.find((s) => s.symbol === initialSymbol)
          || list.find((s) => s.symbol === DEFAULT_SYMBOL)
          || list[0]
          || null)
      })
      .catch((error) => {
        if (error.name !== 'AbortError') setLoadError(error.message)
      })
    return () => controller.abort()
  }, [initialSymbol, reloadKey])

  // 실시간으로 받을 종목: 지원 종목 + 관심 종목 + 선택 종목
  const streamSymbols = useMemo(() => [
    ...stocks.map((s) => s.symbol),
    ...(hydrated ? watchlist.map((s) => s.symbol) : []),
    ...(selected ? [selected.symbol] : []),
  ], [stocks, watchlist, selected, hydrated])

  const feed = useQuoteFeed(streamSymbols, hydrated ? feedMode : null)

  const handleSelect = useCallback((stock) => {
    setSelected({ symbol: stock.symbol, name: stock.name, market: stock.market })
    window.history.replaceState({}, '', `/?symbol=${stock.symbol}`)
  }, [])

  return (
    <main className='app-shell'>
      <header className='topbar'>
        <span className='prototype-label'>국내 주식 실시간 시세 · 한국투자증권 데이터</span>
        <h1 className='sr-only'>국내 주식 대시보드</h1>
        <StockSearch onSelect={handleSelect} />
        <AuthMenu />
      </header>

      <FeedStatusBar feed={feed} marketStatus={marketStatus} statusError={statusError} />

      {loadError ? (
        <section className='panel error-panel' role='alert'>
          <strong>종목 정보를 불러오지 못했습니다.</strong>
          <p>{loadError}</p>
          <button type='button' onClick={() => setReloadKey((k) => k + 1)}>다시 시도</button>
        </section>
      ) : (
        <>
          <MarketTicker stocks={stocks} activeSymbol={selected?.symbol} onSelect={handleSelect} />

          <div className='dashboard-layout'>
            <section className='primary-column' aria-label='선택 종목'>
              {selected ? (
                <>
                  <StockInfo stock={selected} />
                  <StockChart
                    key={selected.symbol}
                    symbol={selected.symbol}
                    marketOpen={Boolean(marketStatus?.marketOpen)}
                  />
                  <section className='panel news-panel' aria-label='관련 뉴스'>
                    <div className='panel-heading'>
                      <h2>{selected.name} 관련 뉴스</h2>
                    </div>
                    <NewsFeed key={selected.symbol} symbol={selected.symbol} />
                  </section>
                </>
              ) : (
                <section className='panel empty-state'>
                  <strong>종목을 불러오는 중입니다.</strong>
                </section>
              )}
            </section>

            <aside className='insight-column' aria-label='AI 대화·관심 종목·투자의견·실적'>
              {selected && <StockChatDrawer key={userId ?? 'guest'} stock={selected} />}
              <WatchlistPanel activeSymbol={selected?.symbol} onSelect={handleSelect} />
              {selected && (
                <>
                  <section className='panel' aria-label='전문가 투자의견'>
                    <div className='panel-heading'><h2>전문가 투자의견</h2></div>
                    <OpinionPanel key={selected.symbol} symbol={selected.symbol} />
                  </section>
                  <section className='panel' aria-label='최근 실적'>
                    <div className='panel-heading'><h2>최근 실적</h2></div>
                    <EarningsPanel key={selected.symbol} symbol={selected.symbol} />
                  </section>
                </>
              )}
            </aside>
          </div>
        </>
      )}
    </main>
  )
}
