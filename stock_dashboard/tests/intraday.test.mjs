import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mergeAndAggregate } from '../src/app/lib/intraday.mjs'
const bar = (minute, open, close, volume = 10) => ({ date: `2026-11-04T00:${String(minute).padStart(2,'0')}:00Z`, open, close, high: Math.max(open, close) + 2, low: Math.min(open, close) - 2, volume })
test('closed REST minute wins over overlapping observed minute without double volume', () => {
 const result = mergeAndAggregate([bar(0, 100, 105, 50), bar(1, 105, 110, 60)], [bar(0, 103, 104, 5), bar(2, 110, 111, 20)], 180)
 assert.equal(result.length, 1)
 assert.deepEqual([result[0].open, result[0].close, result[0].high, result[0].low, result[0].volume], [100,111,113,98,130])
})
test('every supported interval keeps ordered OHLC and sums actual minute volumes', () => {
 const minutes = Array.from({length:60}, (_,i) => bar(i,100+i,101+i))
 for(const seconds of [60,180,300,600,900,1800,3600]) {
  const result=mergeAndAggregate(minutes, [], seconds)
  assert.equal(result.length, 3600/seconds)
  assert.equal(result[0].open,100)
  assert.equal(result.at(-1).close,160)
  assert.equal(result.reduce((sum,b) => sum+b.volume,0),600)
 }
})
test('missing minutes are not synthesized and previous day stays separate', () => {
 const older={...bar(0,80,81),date:'2026-11-03T00:00:00Z'}
 assert.equal(mergeAndAggregate([older,bar(0,100,101),bar(10,110,111)],[],60).length,3)
})
