
## 발생 오류

```text
useStockStore.js.default is not a function
```

### Before

```js
export const useStockStore = create((set) => ({
  watchlist: [],
}))
```

```jsx
import useStockStore from '../store/useStockStore'
```

### 문제점

스토어는 named export로 내보냈지만 컴포넌트에서는 default import로 가져왔다. 이 경우 import 결과가 기대한 Zustand Hook 함수가 아니므로 컴포넌트 렌더링 중 호출할 수 없다.

### After

```js
const useStockStore = create(
  persist(
    (set, get) => ({
      watchlist: [],
      prices: {},
      // actions
    }),
    { name: 'stock-dashboard' }
  )
)

export default useStockStore
```

### 수정 이유

export와 import 방식을 default로 통일했다. 동시에 Zustand `persist`를 적용해 관심 종목 상태가 새로고침 후에도 유지되도록 했다.

<br>

## 발생 오류

### Before

```jsx
'use clinet'

<input
  value={keyword}
  onChange={(evnet) => setKeyword(event.target.value)}
/>
```

### 문제점

- `'use clinet'` 오탈자로 Client Component 선언이 적용되지 않는다.
- 매개변수 이름은 `evnet`인데 함수 내부에서는 전역 `event`를 사용하고 있다.
- `useState`와 입력 이벤트를 사용하는 컴포넌트이므로 올바른 `'use client'` 지시문이 필요하다.

### After

```jsx
'use client'

<input
  value={keyword}
  onChange={(event) => setKeyword(event.target.value)}
/>
```

### 수정 이유

React의 controlled input은 `value` 상태와 현재 입력 이벤트의 `event.target.value`가 연결되어야 한다. 매개변수를 일치시키고 Client Component 지시문을 수정했다.

<br>

## 발생 오류

```text
Conflicting route and page at /api/stock/[symbol]
```

### Before

```text
src/app/api/stock/[symbol]/route.js
src/app/api/stock/[symbol]/page.js
```

### 문제점

같은 URL segment에 JSON 응답용 `route.js`와 화면용 `page.js`를 함께 두어 Next.js가 동일 주소의 역할을 결정할 수 없었다.

### After

```text
src/app/api/stock/[symbol]/route.js  # JSON API
src/app/stocks/[symbol]/page.js      # 화면 진입 주소
```

### 수정 이유

API Route와 화면 Route의 책임 및 URL을 분리했다. 현재 상세 주소는 한 화면 대시보드 사용성을 위해 `/?symbol=...`로 redirect한다.

<br>

## 발생 오류

### Before

```jsx
useEffect(() => {
  const socket = new WebSocket(url)
  socket.onopen = () => {
    socket.send(JSON.stringify({ type: 'subscribe', symbol }))
  }
}, [symbol])
```

### 문제점

종목이 바뀌거나 컴포넌트가 제거돼도 이전 구독과 연결이 남는다. React 개발 모드에서 effect가 다시 실행되면 중복 소켓, 중복 메시지, 메모리 누수가 발생할 수 있다.

### After

```jsx
useEffect(() => {
  let active = true
  const socket = new WebSocket(url)

  socket.onopen = () => {
    if (!active) return
    socket.send(JSON.stringify({ type: 'subscribe', symbol }))
  }

  return () => {
    active = false
    if (socket.readyState === WebSocket.OPEN) {
      socket.send(JSON.stringify({ type: 'unsubscribe', symbol }))
    }
    socket.close()
  }
}, [symbol])
```

### 수정 이유

`useEffect` cleanup에서 외부 시스템의 구독과 연결을 함께 종료해 React 컴포넌트 생명주기와 WebSocket 생명주기를 일치시켰다. 최종 코드에서는 `Dashboard`에서 연결을 한 번만 생성하고 자식 컴포넌트에 데이터를 전달한다.

<br>

## 발생 오류

### Before

```jsx
setChartData([...chartData, newPoint])
```

### 문제점

WebSocket 이벤트가 연속으로 들어오면 이벤트 핸들러가 과거 렌더링의 `chartData`를 참조하는 stale closure 문제가 생길 수 있다. 또한 배열 길이를 제한하지 않으면 메모리와 차트 렌더링 비용이 계속 증가한다.

### After

```jsx
setHistory((current) => {
  const next = { ...current }
  next[symbol] = [...(current[symbol] || []), newPoint].slice(-60)
  return next
})
```

### 수정 이유

함수형 setter는 React가 보유한 최신 상태를 기준으로 다음 상태를 계산한다. `.slice(-60)`을 적용해 실시간 데이터의 크기를 제한했다.

<br>

## 발생 오류

### Before

```jsx
<button onClick={setCount(count + 1)}>증가</button>
```

### 문제점

이 코드는 클릭 이벤트에 함수를 전달하지 않고 렌더링 중 `setCount`를 즉시 실행한다. 상태 변경으로 다시 렌더링되고 다시 setter가 호출돼 반복 렌더링 오류가 발생할 수 있다.

### After

```jsx
<button onClick={() => setCount(count + 1)}>증가</button>
```

### 수정 이유

화살표 함수를 이벤트 핸들러로 전달해 사용자가 클릭했을 때만 상태가 변경되도록 했다. 프로젝트의 검색 결과, 관심 종목, 차트 기간 버튼도 같은 방식으로 작성했다.
