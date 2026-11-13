import AuthForm from '../components/AuthForm'
import { returnPath } from '../lib/accountApi'
export const metadata = { title: '로그인 | K-FinCheck' }
export default async function LoginPage({ searchParams }) {
  const params = await searchParams
  return <AuthForm mode='login' nextPath={returnPath(params?.next)} registered={params?.registered === '1'} />
}
