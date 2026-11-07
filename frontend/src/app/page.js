import Dashboard from './components/Dashboard'

// 주소의 ?symbol=005930 으로 처음 보여줄 종목을 고른다
export default async function Home({ searchParams }) {
  const params = await searchParams
  const symbol = /^\d{6}$/.test(params?.symbol || '') ? params.symbol : null
  return <Dashboard initialSymbol={symbol} />
}
