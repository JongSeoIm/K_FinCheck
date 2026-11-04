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

function Ask($session, $question, $symbol = '005930') {
  return Call POST "$BaseUrl/api/chat/messages" @{ symbol = $symbol; question = $question } $session
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
$r = Call POST "$BaseUrl/api/chat/messages" @{ symbol = '005930'; question = '삼성전자 현재가' }
Check '로그인 없이 질문 401' ($r.Status -eq 401) "status=$($r.Status)"
$r = Call GET "$BaseUrl/api/chat/conversations/005930"
Check '로그인 없이 대화 조회 401' ($r.Status -eq 401) "status=$($r.Status)"
$r = Call POST "$AiUrl/chat" @{ question = '삼성전자 현재가'; symbol = '005930' }
Check 'AI 서버 직접 호출 차단 (내부 키 없음) 403' ($r.Status -eq 403) "status=$($r.Status) (AI_INTERNAL_KEY 가 비어 있으면 200)"

$s = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$id = "t2c$(Get-Random -Minimum 10000 -Maximum 99999)"
$null = Call POST "$BaseUrl/api/auth/signup" @{ loginId = $id; password = 'password123'; nickname = '챗봇테스트' } $s

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

Write-Host "`n=== 5. 대화 문맥 (최근 10개 · 새 대화 · 로그아웃) ==="
$null = Call DELETE "$BaseUrl/api/chat/conversations/005930" $null $s
for ($i = 1; $i -le 6; $i++) { $null = Ask $s "점심 메뉴 $i 추천해줘" }   # 금융 무관 → 고정 안내, 대화에는 남는다
$r = Call GET "$BaseUrl/api/chat/conversations/005930" $null $s
$msgs = @($r.Json.messages)
Check '12개 중 최근 10개만 유지' ($msgs.Count -eq 10 -and $msgs[0].content -eq '점심 메뉴 2 추천해줘') "count=$($msgs.Count) first=$($msgs[0].content)"
$r = Call GET "$BaseUrl/api/chat/conversations/000660" $null $s
Check '종목별 대화 분리 (SK하이닉스는 비어 있음)' (@($r.Json.messages).Count -eq 0) $r.Raw
$r = Call DELETE "$BaseUrl/api/chat/conversations/005930" $null $s
$after = Call GET "$BaseUrl/api/chat/conversations/005930" $null $s
Check '새 대화 → 204, 문맥 삭제' ($r.Status -eq 204 -and @($after.Json.messages).Count -eq 0) "status=$($r.Status)"
$null = Ask $s '점심 메뉴 추천해줘'
$null = Call POST "$BaseUrl/api/auth/logout" $null $s
$s = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$null = Call POST "$BaseUrl/api/auth/login" @{ loginId = $id; password = 'password123' } $s
$r = Call GET "$BaseUrl/api/chat/conversations/005930" $null $s
Check '로그아웃 → 재로그인 시 대화 문맥 삭제됨' (@($r.Json.messages).Count -eq 0) $r.Raw

Write-Host ''
$color = if ($script:fail -eq 0) { 'Green' } else { 'Red' }
Write-Host "결과: 통과 $($script:pass) / 실패 $($script:fail)   (테스트 계정: $id)" -ForegroundColor $color
if ($script:fail -gt 0) { exit 1 }
