import { searchStocks } from '@/app/lib/finnhub'
import { marketStocks } from '@/app/lib/market'

function searchFallback(query) {
  const normalizedQuery = query.toUpperCase()

  return marketStocks.filter((stock) => (
    stock.symbol.includes(normalizedQuery)
    || stock.name.toUpperCase().includes(normalizedQuery)
  ))
}

export async function GET(request) {
  const { searchParams } = new URL(request.url)
  const query = searchParams.get('q')?.trim()

  if (!query) {
    return Response.json([])
  }

  try {
    return Response.json(await searchStocks(query))
  } catch {
    return Response.json(searchFallback(query))
  }
}
