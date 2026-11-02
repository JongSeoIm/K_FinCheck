import Dashboard from './components/Dashboard'
import { marketStocks } from './lib/market'

export default async function Home({ searchParams }) {
  const params = await searchParams
  const requestedSymbol = params?.symbol?.toUpperCase()
  const initialStock = marketStocks.find(
    (stock) => stock.symbol === requestedSymbol
  ) || marketStocks[0]

  return <Dashboard initialStock={initialStock} />
}
