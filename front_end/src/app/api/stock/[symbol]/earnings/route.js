import { getEarnings } from '@/app/lib/finnhub'

export async function GET(_request, { params }) {
  try {
    const { symbol } = await params
    return Response.json(await getEarnings(symbol.toUpperCase()))
  } catch (error) {
    return Response.json({ error: error.message }, { status: 500 })
  }
}
