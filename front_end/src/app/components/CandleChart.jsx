'use client'

import { useEffect, useRef, useState } from 'react'
import { CandlestickSeries, createChart } from 'lightweight-charts'

const won = (value) => '₩' + Math.round(Number(value)).toLocaleString('ko-KR')

// 일봉 캔들 차트 (Lightweight Charts). bars: [{ date, open, high, low, close }]
// 국내 증시 관례에 맞춰 상승은 빨강, 하락은 파랑으로 표시합니다.
export default function CandleChart({ bars }) {
    const containerRef = useRef(null)
    const chartRef = useRef(null)
    const seriesRef = useRef(null)
    const fittedRef = useRef(false)
    const [hover, setHover] = useState(null)

    // 차트 만들기 (한 번만)
    useEffect(() => {
        const chart = createChart(containerRef.current, {
            autoSize: true,
            layout: { background: { color: 'transparent' }, textColor: '#666', fontSize: 11 },
            grid: { vertLines: { color: '#eeeeee' }, horzLines: { color: '#eeeeee' } },
            rightPriceScale: { borderColor: '#cccccc' },
            timeScale: { borderColor: '#cccccc' },
            localization: { priceFormatter: (price) => Math.round(price).toLocaleString('ko-KR') },
        })

        const series = chart.addSeries(CandlestickSeries, {
            upColor: '#d6453d',
            downColor: '#2f6fdb',
            borderUpColor: '#d6453d',
            borderDownColor: '#2f6fdb',
            wickUpColor: '#d6453d',
            wickDownColor: '#2f6fdb',
        })

        // 마우스를 올린 날의 시가·고가·저가·종가 표시
        chart.subscribeCrosshairMove((param) => {
            const point = param.seriesData.get(series)
            if (!param.time || !point) {
                setHover(null)
                return
            }
            setHover({ time: String(param.time), ...point })
        })

        chartRef.current = chart
        seriesRef.current = series
        fittedRef.current = false

        return () => {
            chart.remove()
            chartRef.current = null
            seriesRef.current = null
        }
    }, [])

    // 데이터 넣기 (현재가가 바뀔 때마다 갱신하되, 화면 확대/이동은 유지)
    useEffect(() => {
        if (!seriesRef.current) return
        seriesRef.current.setData(
            bars.map((bar) => ({
                time: bar.date,
                open: bar.open,
                high: bar.high,
                low: bar.low,
                close: bar.close,
            }))
        )
        if (!fittedRef.current && bars.length > 0) {
            chartRef.current.timeScale().fitContent()
            fittedRef.current = true
        }
    }, [bars])

    return (
        <div style={{ position: 'relative', width: '100%', height: '100%' }}>
            <div ref={containerRef} style={{ width: '100%', height: '100%' }} />
            {hover && (
                <div
                    style={{
                        position: 'absolute',
                        top: 6,
                        left: 8,
                        fontSize: '0.75rem',
                        background: 'rgba(255,255,255,0.9)',
                        padding: '2px 6px',
                        pointerEvents: 'none',
                    }}
                >
                    {hover.time} · 시 {won(hover.open)} · 고 {won(hover.high)} · 저 {won(hover.low)} · 종 {won(hover.close)}
                </div>
            )}
        </div>
    )
}