import Dashboard from './components/Dashboard'
import { marketStocks } from './lib/market'

export default async function Home({ searchParams }) {
  const params = await searchParams
  const requestedSymbol = params?.symbol?.toUpperCase()

  // 요청된 종목이 있으면 찾고, 없으면 삼성전자('005930')를 기본값으로 설정
  const initialStock = marketStocks.find(
    (stock) => stock.symbol === requestedSymbol
  ) || marketStocks.find((stock) => stock.symbol === '005930') || marketStocks[0]

  return <Dashboard initialStock={initialStock} />
}