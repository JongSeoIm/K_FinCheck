# AI 중심 구현

- 전체 화면 UI 배치와 CSS 작성
- Finnhub API Route의 반복되는 기본 코드
- Recharts 차트의 기본 구성과 속성 설정
- 로딩 화면과 데이터가 없을 때 보여 주는 단순 반환 코드

<br>

# 직접 작성

```jsx
const [keyword, setKeyword] = useState('')
const debouncedKeyword = useDebounce(keyword, 350)

<input
  value={keyword}
  onChange={(event) => setKeyword(event.target.value)}
/>
```

`useState`로 input의 값을 상태로 관리했다. `onChange`가 실행되면 입력한 값이 `keyword`에 저장된다. 그리고 직접 연결한 `useDebounce` Custom Hook을 사용해서 입력할 때마다 API를 요청하지 않고, 350ms 동안 입력이 멈춘 뒤에 검색하도록 했다.

두 번째는 `useStockStore.js`의 관심 종목 관리 부분이다.

```js
addToWatchlist: (symbol) => {
  const normalizedSymbol = symbol.toUpperCase()
  const { watchlist } = get()
  if (watchlist.includes(normalizedSymbol)) return
  set({ watchlist: [...watchlist, normalizedSymbol] })
},

removeFromWatchlist: (symbol) => {
  set((state) => ({
    watchlist: state.watchlist.filter((item) => item !== symbol),
  }))
},
```

Zustand의 `get`으로 기존 관심 종목을 읽고 `set`으로 상태를 변경했다. 같은 종목은 중복 추가되지 않게 했고, 추가할 때는 spread 문법으로 새 배열을 만들었다. 삭제할 때도 기존 배열을 직접 수정하지 않고 `filter`로 새 배열을 만들었다. `persist`에는 관심 종목만 저장해서 새로고침한 뒤에도 목록이 유지되도록 했다.