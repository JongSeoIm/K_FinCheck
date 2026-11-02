import 'server-only'
import { marketStocks } from './market'
// 시세(getStockQuote)만 KIS 실제 값, 나머지는 아직 UI 예시 데이터
function stock(symbol) { return marketStocks.find(item => item.symbol === symbol) || { symbol, name: symbol, price: 100, change: 0 } }
export async function searchStocks(query) { return marketStocks.filter(item => `${item.symbol} ${item.name}`.toLowerCase().includes(query.toLowerCase())).map(item => ({ ...item, type: '예시 종목' })) }

const KIS_BASE = process.env.KIS_BASE_URL || 'https://openapi.koreainvestment.com:9443'

function kisNumber(value) {
    if (value == null || String(value).trim() === '') return null
    const n = Number(value)
    return Number.isNaN(n) ? null : n
}

// 토큰은 발급 횟수 제한이 있어서 한 번 받으면 재사용한다 (개발 중 재로드에도 유지)
function kisToken() {
    const cache = globalThis.__kisToken
    if (cache && Date.now() < cache.expiresAt) return cache.promise
    const promise = fetch(`${KIS_BASE}/oauth2/tokenP`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
            grant_type: 'client_credentials',
            appkey: process.env.KIS_APP_KEY,
            appsecret: process.env.KIS_APP_SECRET,
        }),
        cache: 'no-store',
    }).then(async (res) => {
        const body = await res.json().catch(() => ({}))
        if (!res.ok || !body.access_token) {
            throw new Error(`KIS 토큰 발급 실패: ${body.error_description || body.msg1 || res.status}`)
        }
        globalThis.__kisToken.expiresAt = Date.now() + Math.max(body.expires_in - 600, 60) * 1000
        return body.access_token
    })
    globalThis.__kisToken = { promise, expiresAt: Date.now() + 60 * 1000 }
    promise.catch(() => { globalThis.__kisToken = null })
    return promise
}

export async function getStockQuote(symbol) {
    const token = await kisToken()
    const url = new URL(`${KIS_BASE}/uapi/domestic-stock/v1/quotations/inquire-price`)
    url.searchParams.set('FID_COND_MRKT_DIV_CODE', 'J')
    url.searchParams.set('FID_INPUT_ISCD', symbol)
    const res = await fetch(url, {
        headers: {
            authorization: `Bearer ${token}`,
            appkey: process.env.KIS_APP_KEY,
            appsecret: process.env.KIS_APP_SECRET,
            tr_id: 'FHKST01010100',
            custtype: 'P',
        },
        cache: 'no-store',
    })
    const body = await res.json().catch(() => ({}))
    if (!res.ok || body.rt_cd !== '0') {
        throw new Error(`KIS 시세 조회 실패: ${body.msg1 || res.status}`)
    }

    const o = body.output
    const price = kisNumber(o.stck_prpr)
    // 부호 코드: 4 하한, 5 하락이면 음수
    const dir = ['4', '5'].includes(String(o.prdy_vrss_sign)) ? -1 : 1
    const absChange = kisNumber(o.prdy_vrss)
    const absRate = kisNumber(o.prdy_ctrt)
    const priceChange = absChange == null ? null : dir * Math.abs(absChange)
    const listedShares = kisNumber(o.lstn_stcn)
    return {
        symbol,
        name: stock(symbol).name,
        industry: o.bstp_kor_isnm || null,
        exchange: o.rprs_mrkt_kor_name || null,
        listedShares,
        marketCapitalization: price != null && listedShares != null ? price * listedShares : null,
        price,
        priceChange,
        change: absRate == null ? null : dir * Math.abs(absRate),
        open: kisNumber(o.stck_oprc),
        high: kisNumber(o.stck_hgpr),
        low: kisNumber(o.stck_lwpr),
        previousClose: price != null && priceChange != null ? price - priceChange : null,
    }
}

export async function getCompanyProfile(symbol) { const q = await getStockQuote(symbol); return { symbol, name: q.name, currency: 'KRW', exchange: 'KRX', industry: q.industry, marketCapitalization: q.marketCapitalization } }
export async function getRecommendationTrends() { return [{ period: '2026-04-01', strongBuy: 10, buy: 17, hold: 11, sell: 4, strongSell: 1 }, { period: '2026-05-01', strongBuy: 11, buy: 18, hold: 10, sell: 3, strongSell: 1 }, { period: '2026-06-01', strongBuy: 12, buy: 18, hold: 9, sell: 3, strongSell: 1 }] }
export async function getEarnings() { return [{ period: '2026 Q2', actual: 1.42, estimate: 1.31, surprisePercent: 8.4 }, { period: '2026 Q1', actual: 1.28, estimate: 1.24, surprisePercent: 3.2 }, { period: '2025 Q4', actual: 1.19, estimate: 1.22, surprisePercent: -2.5 }, { period: '2025 Q3', actual: 1.11, estimate: 1.05, surprisePercent: 5.7 }] }
export async function getCompanyNews(symbol) { return [{ id: 'preview-1', headline: `${symbol} 실적과 주요 지표 살펴보기`, source: 'UI 예시', datetime: 1793322000, summary: '실제 뉴스가 아닌 화면 배치를 확인하기 위한 예시입니다.', url: '#' }, { id: 'preview-2', headline: '기업 재무정보를 이해하는 방법', source: 'UI 예시', datetime: 1793318400, summary: '오른쪽 버튼을 눌러 AI 대화 패널을 열어보세요.', url: '#' }] }
export async function getStockCandles(symbol, resolution) { const s = stock(symbol); const count = resolution === 'W' ? 52 : 42; const offsets = [-.8, -.4, -.6, .1, .3, .2, .8, .6, 1.1, .9, .7, 1.4]; return Array.from({ length: count }, (_, i) => ({ time: resolution === '5' ? `${String(9 + Math.floor(i / 12)).padStart(2, '0')}:${String((i % 12) * 5).padStart(2, '0')}` : `${i + 1}일`, price: Number((s.price - 2.6 + i * .07 + offsets[i % offsets.length]).toFixed(2)), preview: true })) }