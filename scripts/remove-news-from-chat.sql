-- 대화 기록에 저장된 뉴스 자료를 지운다 (뉴스 카드·출처가 있는 답변과 그 질문)
-- 실행 전에 mariadb-dump 로 백업하고, 백엔드를 끈 상태에서 한 번 실행한다.
-- 실행: mariadb -P 3300 -u root -p --default-character-set=utf8mb4 stockdb < scripts/remove-news-from-chat.sql

SET NAMES utf8mb4;

CREATE TEMPORARY TABLE news_answer AS
SELECT message_id, conversation_id
FROM chat_message
WHERE role = 'assistant'
  AND (response_json LIKE '%NAVER API HUB%' OR response_json LIKE '%"type":"news"%');

-- 답변 바로 앞의 질문
CREATE TEMPORARY TABLE news_question AS
SELECT MAX(q.message_id) AS message_id
FROM news_answer a
JOIN chat_message q ON q.conversation_id = a.conversation_id AND q.message_id < a.message_id AND q.role = 'user'
GROUP BY a.message_id;

DELETE FROM chat_message WHERE message_id IN (SELECT message_id FROM news_question);
DELETE FROM chat_message WHERE message_id IN (SELECT message_id FROM news_answer);

-- 지운 뒤 메시지가 하나도 없는 대화방 (질문 전의 새 대화 방은 그대로 둔다)
DELETE c FROM chat_conversation c
LEFT JOIN chat_message m ON m.conversation_id = c.conversation_id
WHERE m.message_id IS NULL AND c.title <> '새 대화';

DROP TEMPORARY TABLE news_question;
DROP TEMPORARY TABLE news_answer;
