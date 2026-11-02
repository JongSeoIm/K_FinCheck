'use client'

import { useEffect, useRef } from 'react'
import { CandlestickSeries, ColorType, createChart } from 'lightweight-charts'

const won = new Intl.NumberFormat('ko-KR')

// 일봉 캔들 차트 (Lightweight Charts). 상승 빨강 / 하락 파랑
export default function CandleChart({ points }) {
  const containerRef = useRef(null)
  const chartRef = useRef(null)
  const seriesRef = useRef(null)
  const fittedRef = useRef(false)

  // 차트 생성은 처음 한 번만
  useEffect(() => {
    const chart = createChart(containerRef.current, {
      autoSize: true,
      layout: {
        background: { type: ColorType.Solid, color: '#ffffff' },
        textColor: '#666',
        fontSize: 11,
        attributionLogo: true,
      },
      grid: {
        vertLines: { color: '#f2f2f2' },
        horzLines: { color: '#ececec' },
      },
      rightPriceScale: { borderColor: '#d6d6d6' },
      timeScale: { borderColor: '#d6d6d6' },
      localization: {
        locale: 'ko-KR',
        priceFormatter: (price) => won.format(Math.round(price)),
      },
    })
    seriesRef.current = chart.addSeries(CandlestickSeries, {
      upColor: '#d1343c',
      downColor: '#2f6fd6',
      borderVisible: false,
      wickUpColor: '#d1343c',
      wickDownColor: '#2f6fd6',
      priceFormat: { type: 'price', precision: 0, minMove: 1 },
    })
    chartRef.current = chart

    return () => {
      chart.remove()
      chartRef.current = null
      seriesRef.current = null
      fittedRef.current = false
    }
  }, [])

  // 데이터가 바뀌면 시리즈만 갱신 (처음 한 번만 전체 기간에 맞춤)
  useEffect(() => {
    if (!seriesRef.current) return
    seriesRef.current.setData(points.map((p) => ({
      time: p.time,
      open: Number(p.open),
      high: Number(p.high),
      low: Number(p.low),
      close: Number(p.close),
    })))
    if (!fittedRef.current) {
      chartRef.current.timeScale().fitContent()
      fittedRef.current = true
    }
  }, [points])

  return <div ref={containerRef} className='candle-chart' aria-label='일봉 캔들 차트' />
}
