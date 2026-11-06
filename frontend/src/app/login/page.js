import AuthForm from '../components/AuthForm'

export const metadata = { title: '로그인 · 국내 주식 실시간 시세' }

export default async function LoginPage({ searchParams }) {
  const params = await searchParams
  return <AuthForm mode='login' justSignedUp={params?.signup === '1'} />
}
