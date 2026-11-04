'use client'

import { useEffect, useRef, useState } from 'react'
import { CandlestickSeries, createChart } from 'lightweight-charts'

const won = (value) => '₩' + Math.round(Number(value)).toLocaleString('ko-KR')
const KST_OFFSET = 9 * 3600 // 차트 라이브러리는 UTC로 그리므로, 한국 시각으로 보이도록 9시간을 더합니다.

// 캔들 차트 (Lightweight Charts). 마우스 휠로 확대·축소, 드래그로 이동합니다.
// bars: [{ t(초), date, time, open, high, low, close }]
// intraday: 초·분봉이면 true (시간축에 시각 표시)
// 국내 증시 관례에 맞춰 상승은 빨강, 하락은 파랑으로 표시합니다.
export default function CandleChart({ bars, intraday = false, showSeconds = false, fitSignal = 0 }) {
    const containerRef = useRef(null)
    const chartRef = useRef(null)
    const seriesRef = useRef(null)
    const fittedRef = useRef(false)
    const labelsRef = useRef(new Map())
    const [hover, setHover] = useState(null)

    // 차트 만들기 (한 번만. 주기가 바뀌면 부모가 key 로 새로 만듭니다)
    useEffect(() => {
        const chart = createChart(containerRef.current, {
            autoSize: true,
            layout: { background: { color: 'transparent' }, textColor: '#666', fontSize: 11 },
            grid: { vertLines: { color: '#eeeeee' }, horzLines: { color: '#eeeeee' } },
            rightPriceScale: { borderColor: '#cccccc' },
            timeScale: { borderColor: '#cccccc', timeVisible: intraday, secondsVisible: showSeconds },
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

        // 마우스를 올린 봉의 시가·고가·저가·종가 표시
        chart.subscribeCrosshairMove((param) => {
            const point = param.seriesData.get(series)
            if (!param.time || !point) {
                setHover(null)
                return
            }
            setHover({ label: labelsRef.current.get(param.time) || String(param.time), ...point })
        })

        chartRef.current = chart
        seriesRef.current = series
        fittedRef.current = false

        return () => {
            chart.remove()
            chartRef.current = null
            seriesRef.current = null
        }
    }, [intraday, showSeconds])

    // 데이터 넣기 (갱신될 때마다 넣되, 화면 확대/이동은 유지)
    useEffect(() => {
        if (!seriesRef.current) return
        const labels = new Map()
        const seen = new Set()
        const data = []
        bars.forEach((bar) => {
            const time = intraday ? bar.t + KST_OFFSET : bar.date
            if (seen.has(time)) return
            seen.add(time)
            labels.set(time, bar.time ? `${bar.date} ${bar.time}` : bar.date)
            data.push({ time, open: bar.open, high: bar.high, low: bar.low, close: bar.close })
        })
        labelsRef.current = labels
        seriesRef.current.setData(data)
        if (!fittedRef.current && data.length > 0) {
            chartRef.current.timeScale().fitContent()
            fittedRef.current = true
        }
    }, [bars, intraday])

    // '전체 보기' 버튼
    useEffect(() => {
        if (fitSignal > 0 && chartRef.current) chartRef.current.timeScale().fitContent()
    }, [fitSignal])

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
                    {hover.label} · 시 {won(hover.open)} · 고 {won(hover.high)} · 저 {won(hover.low)} · 종 {won(hover.close)}
                </div>
            )}
        </div>
    )
}
