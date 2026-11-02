import { getCompanyNews } from '@/app/lib/finnhub'

function formatDate(date) {
  return date.toISOString().slice(0, 10)
}

export async function GET(request) {
  try {
    const { searchParams } = new URL(request.url)
    const symbol = searchParams.get('symbol')?.trim().toUpperCase()
    if (!symbol) return Response.json({ error: '종목 코드가 필요합니다.' }, { status: 400 })

    const to = new Date()
    const from = new Date()
    from.setUTCDate(from.getUTCDate() - 30)
    return Response.json(await getCompanyNews(symbol, formatDate(from), formatDate(to)))
  } catch (error) {
    return Response.json({ error: error.message }, { status: 500 })
  }
}
