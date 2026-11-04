import AuthForm from '../components/AuthForm'

export const metadata = { title: '회원가입 · 국내 주식 실시간 시세' }

export default function SignupPage() {
  return <AuthForm mode='signup' />
}
