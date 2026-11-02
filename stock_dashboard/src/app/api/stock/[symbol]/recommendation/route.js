import { getRecommendationTrends } from '@/app/lib/finnhub'

export async function GET(_request, { params }) {
  try {
    const { symbol } = await params
    return Response.json(await getRecommendationTrends(symbol.toUpperCase()))
  } catch (error) {
    return Response.json({ error: error.message }, { status: 500 })
  }
}
