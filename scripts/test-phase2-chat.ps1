# 2차 개발 API 자동 점검 2 (기업·실적 · AI 챗봇 · 대화 문맥)
# 사용법: 백엔드(8085) + AI 서버(8087)를 켠 뒤
#   powershell -ExecutionPolicy Bypass -File scripts\test-phase2-chat.ps1
# DART_API_KEY · OPENAI_API_KEY 가 없어도 실행된다. 키가 없으면 해당 항목은 "키 미설정 안내"가 맞는지 확인한다.
# 실행할 때마다 t2c 로 시작하는 테스트 계정 1개가 만들어진다.

param([string]$BaseUrl = 'http://localhost:8085', [string]$AiUrl = 'http://127.0.0.1:8087')

$ErrorActionPreference = 'Stop'
$script:pass = 0
$script:fail = 0

function Call($method, $url, $body = $null, $session = $null, $headers = @{}, $timeoutSec = 90) {
  # 값을 바꾸는 요청에는 CSRF 토큰이 필요하다 (세션이 없으면 임시 세션으로 받는다)
  if ($method -in @('POST', 'DELETE') -and $url.StartsWith($BaseUrl)) {
    if (-not $session) { $session = New-Object Microsoft.PowerShell.Commands.WebRequestSession }
    $csrf = Invoke-WebRequest -Uri "$BaseUrl/api/auth/csrf" -WebSession $session -UseBasicParsing
    $token = ([Text.Encoding]::UTF8.GetString($csrf.RawContentStream.ToArray()) | ConvertFrom-Json).token
    $headers = @{} + $headers
    $headers['X-CSRF-TOKEN'] = $token
  }
  $params = @{ Uri = $url; Method = $method; UseBasicParsing = $true; Headers = $headers; TimeoutSec = $timeoutSec }
  if ($session) { $params.WebSession = $session }
  if ($null -ne $body) {
    $json = if ($body -is [string]) { $body } else { $body | ConvertTo-Json -Compress -Depth 5 }
    $params.Body = [Text.Encoding]::UTF8.GetBytes($json)
    $params.ContentType = 'application/json; charset=utf-8'
  }
  try {
    $res = Invoke-WebRequest @params
    $status = [int]$res.StatusCode
    $raw = [Text.Encoding]::UTF8.GetString($res.RawContentStream.ToArray())
  } catch [System.Net.WebException] {
    $r = $_.Exception.Response
    if (-not $r) { throw "서버에 연결할 수 없습니다: $url" }
    $status = [int]$r.StatusCode
    $raw = (New-Object IO.StreamReader($r.GetResponseStream(), [Text.Encoding]::UTF8)).ReadToEnd()
  }
  $parsed = $null
  if ($raw) { try { $parsed = $raw | ConvertFrom-Json } catch {} }
  return [pscustomobject]@{ Status = $status; Json = $parsed; Raw = $raw }
}

function Check($name, [bool]$ok, $detail = '') {
  if ($ok) { $script:pass++; Write-Host "  [PASS] $name" -ForegroundColor Green }
  else { $script:fail++; Write-Host "  [FAIL] $name  $detail" -ForegroundColor Red }
}
function Info($text) { Write-Host "         $text" -ForegroundColor DarkGray }

function NewRoom($session, $symbol = '005930') { return Call POST "$BaseUrl/api/chat/conversations" @{ symbol = $symbol } $session }
# 대화방 번호를 넘기지 않으면 새 대화방을 만들어 질문한다
function Ask($session, $question, $symbol = '005930', $conversationId = $null) {
  if (-not $conversationId) { $conversationId = (NewRoom $session $symbol).Json.conversation.conversationId }
  return Call POST "$BaseUrl/api/chat" @{ symbol = $symbol; conversationId = $conversationId; question = $question } $session
}

$health = (Call GET "$AiUrl/health").Json
$openai = [bool]$health.openaiConfigured
Write-Host "AI 서버: 모델 $($health.model), OpenAI 키 $(if ($openai) { '있음' } else { '없음' })"

Write-Host "`n=== 1. 기업 정보 · 실적 (OpenDART) ==="
$r = Call GET "$BaseUrl/api/companies/005930"
Check '기업 정보 (DART 고유번호 포함)' ($r.Status -eq 200 -and $r.Json.company.dartCorpCode -eq '00126380') $r.Raw
$r = Call GET "$BaseUrl/api/companies/999999"
Check '없는 기업 404' ($r.Status -eq 404) "status=$($r.Status)"
$r = Call GET "$BaseUrl/api/companies/005930/financials?year=20xx"
Check '사업연도 형식 오류 400' ($r.Status -eq 400) "status=$($r.Status)"
$r = Call GET "$BaseUrl/api/companies/005930/financials?report=99999"
Check '보고서 종류 오류 400' ($r.Status -eq 400) "status=$($r.Status)"

$fin = Call GET "$BaseUrl/api/companies/005930/financials" -timeoutSec 60
$dart = $fin.Status -ne 503
if (-not $dart) {
  Check 'DART_API_KEY 없음 → 503 안내' ($fin.Json.error -match 'DART_API_KEY') $fin.Raw
} else {
  $f = $fin.Json.financial
  Check '최근 실적 조회 200' ($fin.Status -eq 200 -and $f.items.Count -gt 0) $fin.Raw
  Info "$($f.name) $($f.bsnsYear)년 $($f.reportName) ($($f.fsName)) 접수번호 $($f.rceptNo)"
  $rev = $f.items | Where-Object { $_.accountId -eq 'REVENUE' } | Select-Object -First 1
  if ($rev) { Info "매출액 당기 $($rev.current) / 전기 $($rev.previous) 원" }
  Check '연결/별도 · 보고서 종류 · 원문 링크' ($f.fsName -and $f.reportName -and $f.url -match 'dart.fss.or.kr') $fin.Raw
  $again = Call GET "$BaseUrl/api/companies/005930/financials?year=$($f.bsnsYear)&report=$($f.reprtCode)"
  Check '같은 공시 다시 조회 → 같은 접수번호 (DB 재사용)' ($again.Json.financial.rceptNo -eq $f.rceptNo) $again.Raw
}

Write-Host "`n=== 2. 로그인한 사용자만 챗봇 이용 ==="
$r = Call POST "$BaseUrl/api/chat" @{ symbol = '005930'; conversationId = '00000000-0000-0000-0000-000000000000'; question = '삼성전자 현재가' }
Check '로그인 없이 질문 401' ($r.Status -eq 401) "status=$($r.Status)"
$r = NewRoom $null
Check '로그인 없이 대화방 생성 401' ($r.Status -eq 401) "status=$($r.Status)"
$r = Call GET "$BaseUrl/api/chat/conversations?symbol=005930"
Check '로그인 없이 대화 조회 401' ($r.Status -eq 401) "status=$($r.Status)"
$r = Call POST "$AiUrl/chat" @{ question = '삼성전자 현재가'; symbol = '005930' }
Check 'AI 서버 직접 호출 차단 (내부 키 없음) 403' ($r.Status -eq 403) "status=$($r.Status) (AI_INTERNAL_KEY 가 비어 있으면 200)"

$s = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$id = "t2c$(Get-Random -Minimum 10000 -Maximum 99999)"
$null = Call POST "$BaseUrl/api/auth/signup" @{ loginId = $id; password = 'password123'; nickname = '챗봇테스트' } $s
$null = Call POST "$BaseUrl/api/auth/login" @{ loginId = $id; password = 'password123' } $s

Write-Host "`n=== 3. 질문 분류 · 고정 안내 (LLM 호출 없음) ==="
$r = Ask $s '오늘 점심 뭐 먹지?'
Check '금융과 무관 → out_of_scope' ($r.Status -eq 200 -and $r.Json.status -eq 'out_of_scope') $r.Raw
Info $r.Json.answer
$r = Ask $s '카카오 주가 알려줘'
Check '지원하지 않는 기업 → need_more_info' ($r.Json.status -eq 'need_more_info') $r.Raw
Info $r.Json.answer
$r = Ask $s '삼성전자랑 비교해줘'
Check '비교 대상 부족 → need_more_info' ($r.Json.status -eq 'need_more_info') $r.Raw
$r = Ask $s ''
Check '빈 질문 400' ($r.Status -eq 400) "status=$($r.Status)"
$r = Ask $s ('가' * 501)
Check '500자 초과 400' ($r.Status -eq 400) "status=$($r.Status)"

Write-Host "`n=== 4. Tool 조회 + AI 답변 ==="
$cases = @(
  @{ q = '부채비율이 뭐야?'; provider = $null },
  @{ q = '삼성전자 현재가 알려줘'; provider = 'KIS' },
  @{ q = '삼성전자 관련 뉴스 알려줘'; provider = 'NAVER API HUB' },
  @{ q = '삼성전자 최근 실적 알려줘'; provider = 'OpenDART' },
  @{ q = '삼성전자 왜 떨어졌어?'; provider = 'KIS' }
)
foreach ($c in $cases) {
  $r = Ask $s $c.q
  $status = $r.Json.status
  $providers = @($r.Json.sources | ForEach-Object { $_.provider }) -join ','
  if ($openai) {
    $okStatus = $status -eq 'answered' -or ($c.provider -eq 'OpenDART' -and -not $dart -and $status -eq 'unavailable')
    $okSource = (-not $c.provider) -or ($status -ne 'answered') -or ($providers -match [regex]::Escape($c.provider))
    Check "`"$($c.q)`" → $status [$providers]" ($okStatus -and $okSource) $r.Raw
    Info (($r.Json.answer -replace "`n", ' ') | ForEach-Object { if ($_.Length -gt 160) { $_.Substring(0, 160) + '…' } else { $_ } })
  } else {
    $expected = if ($c.provider -eq 'OpenDART' -and -not $dart) { 'unavailable' } else { 'error' }
    Check "`"$($c.q)`" → OpenAI 키 없음 안내 ($status)" ($r.Status -eq 200 -and $status -eq $expected) $r.Raw
  }
}

Write-Host "`n=== 5. 대화방 (여러 개 · DB 저장 · 삭제 · 재로그인 복원) ==="
function Rooms($session, $symbol = '005930') { return @((Call GET "$BaseUrl/api/chat/conversations?symbol=$symbol" $null $session).Json.conversations) }
function Messages($session, $room) { return Call GET "$BaseUrl/api/chat/conversations/$room/messages" $null $session }

foreach ($old in (Rooms $s)) { $null = Call DELETE "$BaseUrl/api/chat/conversations/$($old.conversationId)" $null $s }
$created = NewRoom $s
$roomA = $created.Json.conversation.conversationId
Check 'POST 로 대화방 생성 → 201, 제목 새 대화' ($created.Status -eq 201 -and $roomA.Length -eq 36 -and $created.Json.conversation.title -eq '새 대화') $created.Raw
$r = NewRoom $s '999999'
Check '지원하지 않는 종목으로 대화방 생성 404' ($r.Status -eq 404) "status=$($r.Status)"
$r = Call POST "$BaseUrl/api/chat" @{ symbol = '005930'; question = '점심 메뉴 추천해줘' } $s
Check '대화방 번호 없이 질문 400' ($r.Status -eq 400) "status=$($r.Status)"
$first = Ask $s '점심 메뉴 1 추천해줘' '005930' $roomA   # 금융 무관 → 고정 안내, 대화에는 남는다
Check '만든 대화방으로 질문 → 같은 번호 응답' ($first.Status -eq 200 -and $first.Json.conversationId -eq $roomA) $first.Raw
for ($i = 2; $i -le 6; $i++) { $null = Ask $s "점심 메뉴 $i 추천해줘" '005930' $roomA }
$r = Messages $s $roomA
$msgs = @($r.Json.messages)
Check '같은 대화방에 12개 모두 저장' ($msgs.Count -eq 12 -and $msgs[0].content -eq '점심 메뉴 1 추천해줘') "count=$($msgs.Count) first=$($msgs[0].content)"
$second = Ask $s '저녁 메뉴 추천해줘'
$roomB = $second.Json.conversationId
Check '새 대화 → 다른 대화방, 기존 대화방 유지' ($roomB -and $roomB -ne $roomA -and @((Messages $s $roomA).Json.messages).Count -eq 12) $second.Raw
$rooms = @(Rooms $s)
Check '대화방 목록 2개, 최근 대화가 먼저, 제목은 첫 질문' ($rooms.Count -eq 2 -and $rooms[0].conversationId -eq $roomB -and $rooms[0].title -eq '저녁 메뉴 추천해줘') ($rooms | ConvertTo-Json -Compress)
Check '대화방별 메시지 분리' (@((Messages $s $roomB).Json.messages).Count -eq 2) ''
Check '종목별 대화방 분리 (SK하이닉스는 비어 있음)' (@(Rooms $s '000660').Count -eq 0) ''
$r = Ask $s '점심 메뉴 추천해줘' '000660' $roomA
Check '다른 종목의 대화방으로 질문 404' ($r.Status -eq 404) "status=$($r.Status)"
$r = Ask $s '점심 메뉴 추천해줘' '005930' '00000000-0000-0000-0000-000000000000'
Check '없는 대화방으로 질문 404' ($r.Status -eq 404) "status=$($r.Status)"
$r = Call GET "$BaseUrl/api/chat/conversations/abc/messages" $null $s
Check '대화방 번호 형식 오류 400' ($r.Status -eq 400) "status=$($r.Status)"

$other = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$otherId = "t2o$(Get-Random -Maximum 99999999)"
$null = Call POST "$BaseUrl/api/auth/signup" @{ loginId = $otherId; password = 'password123'; nickname = 'other' } $other
$null = Call POST "$BaseUrl/api/auth/login" @{ loginId = $otherId; password = 'password123' } $other
Check '다른 회원의 대화방 조회 404' ((Messages $other $roomA).Status -eq 404) ''
$r = Ask $other '점심 메뉴 추천해줘' '005930' $roomA
Check '다른 회원의 대화방으로 질문 404' ($r.Status -eq 404) "status=$($r.Status)"
$r = Call DELETE "$BaseUrl/api/chat/conversations/$roomA" $null $other
Check '다른 회원의 대화방 삭제 404' ($r.Status -eq 404) "status=$($r.Status)"
Check '다른 회원에게 내 대화방이 보이지 않음' (@(Rooms $other).Count -eq 0) ''

$r = Call DELETE "$BaseUrl/api/chat/conversations/$roomB" $null $s
Check '대화방 삭제 → 204, 그 대화방만 삭제' ($r.Status -eq 204 -and @(Rooms $s).Count -eq 1 -and (Messages $s $roomB).Status -eq 404) "status=$($r.Status)"
$r = Call DELETE "$BaseUrl/api/chat/conversations/$roomB" $null $s
Check '이미 지운 대화방 삭제 404' ($r.Status -eq 404) "status=$($r.Status)"
$null = Call POST "$BaseUrl/api/auth/logout" $null $s
$s = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$null = Call POST "$BaseUrl/api/auth/login" @{ loginId = $id; password = 'password123' } $s
$r = Messages $s $roomA
Check '로그아웃 → 재로그인 시 대화 복원' ($r.Status -eq 200 -and @($r.Json.messages).Count -eq 12) $r.Raw

Write-Host ''
$color = if ($script:fail -eq 0) { 'Green' } else { 'Red' }
Write-Host "결과: 통과 $($script:pass) / 실패 $($script:fail)   (테스트 계정: $id)" -ForegroundColor $color
if ($script:fail -gt 0) { exit 1 }
