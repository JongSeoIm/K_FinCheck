import AuthForm from '../components/AuthForm'

export const metadata = { title: '로그인 · K-FinCheck' }

export default async function LoginPage({ searchParams }) {
  const params = await searchParams
  const symbol = /^\d{6}$/.test(params?.symbol || '') ? params.symbol : null

  return <AuthForm mode='login' symbol={symbol} joined={params?.joined === '1'} />
}
