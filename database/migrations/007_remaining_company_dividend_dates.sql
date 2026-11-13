USE k_fincheck;

-- Derived dates use the source URL without reusing the unique filing receipt number.
INSERT INTO company_event
    (stock_code, event_type, title, event_date, source, source_url)
SELECT company.stock_code, dividend.event_type, dividend.title,
       dividend.event_date, 'OPENDART',
       CONCAT('https://dart.fss.or.kr/dsaf001/main.do?rcpNo=', dividend.receipt)
FROM company
JOIN (
    SELECT '000660' AS stock_code, 'DIVIDEND_RECORD_DATE' AS event_type,
           '2025년 결산배당 기준일 (주당 1,875원)' AS title, '2026-02-28' AS event_date, '20260128800597' AS receipt
    UNION ALL
    SELECT '000660', 'DIVIDEND_RECORD_DATE',
           '2026년 1분기 배당기준일 (주당 375원)', '2026-05-31', '20260422800788'
    UNION ALL
    SELECT '000660', 'DIVIDEND_RECORD_DATE',
           '2026년 2분기 배당기준일 (주당 375원)', '2026-08-31', '20260807800589'
    UNION ALL
    SELECT '000660', 'DIVIDEND_RECORD_DATE',
           '2026년 3분기 배당기준일 (주당 20,000원)', '2026-11-30', '20261028800045'
    UNION ALL
    SELECT '035720', 'DIVIDEND_RECORD_DATE',
           '2025년 결산배당 기준일 (주당 75원)', '2026-03-10', '20260326800754'
    UNION ALL
    SELECT '035720', 'DIVIDEND_PAYMENT',
           '2025년 결산배당금 지급 예정일 (주당 75원)', '2026-04-23', '20260326800754'
    UNION ALL
    SELECT '000270', 'DIVIDEND_RECORD_DATE',
           '2025년 결산배당 기준일 (주당 6,800원)', '2026-03-25', '20260128800342'
    UNION ALL
    SELECT '051910', 'DIVIDEND_RECORD_DATE',
           '2025년 결산배당 기준일 (보통주 주당 2,000원)', '2026-03-31', '20260128800943'
) dividend ON dividend.stock_code = company.stock_code
WHERE NOT EXISTS (
    SELECT 1 FROM company_event existing
    WHERE existing.stock_code = company.stock_code
      AND existing.event_type = dividend.event_type
      AND existing.event_date = dividend.event_date
);
