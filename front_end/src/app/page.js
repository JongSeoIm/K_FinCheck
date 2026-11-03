import Dashboard from './components/Dashboard'

export default async function Home({ searchParams }) {
  const params = await searchParams
  const requested = params?.symbol
  // 6자리 코드만 받고, 지원 종목인지는 Dashboard 가 서버 목록으로 확인합니다 (기본: 삼성전자)
  const initialCode = /^\d{6}$/.test(requested || '') ? requested : '005930'

  return <Dashboard initialCode={initialCode} />
}
