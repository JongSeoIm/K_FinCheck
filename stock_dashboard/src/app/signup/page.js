import AuthForm from '../components/AuthForm'
import { returnPath } from '../lib/accountApi'
export const metadata = { title: '회원가입 | K-FinCheck' }
export default async function SignupPage({ searchParams }) {
  const params = await searchParams
  return <AuthForm mode='signup' nextPath={returnPath(params?.next)} />
}
