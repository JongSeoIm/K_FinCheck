import AuthForm from '../components/AuthForm'

export const metadata = { title: '로그인 · 국내 주식 실시간 시세' }

export default function LoginPage() {
  return <AuthForm mode='login' />
}
