import { getStockQuote } from '@/app/lib/finnhub'

export async function GET(_request, { params }) {
  try {
    const { symbol } = await params
    const quote = await getStockQuote(symbol.toUpperCase())
    return Response.json(quote)
  } catch (error) {
    return Response.json({ error: error.message }, { status: 500 })
  }
}
