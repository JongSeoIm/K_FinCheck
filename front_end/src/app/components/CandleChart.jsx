'use client'

import { useEffect, useRef, useState } from 'react'
import { CandlestickSeries, createChart } from 'lightweight-charts'

const won = (value) => '₩' + Math.round(Number(value)).toLocaleString('ko-KR')

// 캔들 차트 (Lightweight Charts)
// - 일봉: bars = [{ date, open, high, low, close }]
// - 1일(분 간격): bars = [{ ts, time, open, high, low, close }], intraday = true
// 국내 증시 관례에 맞춰 상승은 빨강, 하락은 파랑으로 표시합니다.
// 마우스를 올린 봉의 시각 표시 ("YYYY-MM-DD" 또는 "HH:MM")
const formatTime = (time) => (typeof time === 'number' ? new Date(time * 1000).toISOString().slice(11, 16) : String(time))

export default function CandleChart({ bars, intraday = false }) {
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
            // 데이터 범위 밖으로 끌고 가면 빈 화면만 보이던 문제: 양 끝에서 멈추게 합니다
            timeScale: { borderColor: '#cccccc', fixLeftEdge: true, fixRightEdge: true, timeVisible: intraday, secondsVisible: false },
            handleScale: { axisPressedMouseMove: { time: true, price: false } },
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
            setHover({ time: formatTime(param.time), ...point })
        })

        chartRef.current = chart
        seriesRef.current = series
        fittedRef.current = false

        return () => {
            chart.remove()
            chartRef.current = null
            seriesRef.current = null
        }
        // intraday 가 바뀌면 부모가 key 로 새로 만들기 때문에 처음 한 번만 실행합니다
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [])

    // 데이터 넣기 (현재가가 바뀔 때마다 갱신하되, 화면 확대/이동은 유지)
    useEffect(() => {
        if (!seriesRef.current) return
        seriesRef.current.setData(
            bars.map((bar) => ({
                time: bar.ts ?? bar.date,
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