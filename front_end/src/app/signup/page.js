import AuthForm from '../components/AuthForm'

export const metadata = { title: '회원가입 · K-FinCheck' }

export default async function SignupPage({ searchParams }) {
  const params = await searchParams
  const symbol = /^\d{6}$/.test(params?.symbol || '') ? params.symbol : null

  return <AuthForm mode='signup' symbol={symbol} />
}
