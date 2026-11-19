-- 11주차 제공 SQL 기반. 기존 데이터 초기화 없이 테스트 모집글만 중복 방지하여 추가한다.
-- 이미지 파일은 SQL에 포함되지 않는다. 작성자 계정으로 글 수정 화면에서 포스터를 첨부한다.
USE spring;
INSERT INTO posts (title, content, member_id, created_at, view_count, capacity, applied_count)
SELECT t.title, t.content, m.id, NOW(), 0, t.capacity, 0
FROM member m
JOIN (
    SELECT '[테스트] 모집글 A - 자바 기초 스터디' AS title, '매주 화요일 저녁, 자바 기초 문법을 함께 공부합니다.' AS content, 5 AS capacity
    UNION ALL SELECT '[테스트] 모집글 B - 알고리즘 스터디', '백준 문제를 하루 2개씩 풀고 서로 리뷰합니다.', 5
    UNION ALL SELECT '[테스트] 모집글 C - 현직자 면접 특강 (정원 1명)', '현직 개발자와 1:1 모의 면접. 단 1명만 모집합니다.', 1
) t
WHERE m.username = 'admin'
  AND NOT EXISTS (SELECT 1 FROM posts p WHERE p.title = t.title AND p.member_id = m.id);

SHOW TABLES;
SELECT id, title, capacity, applied_count FROM posts WHERE capacity IS NOT NULL;
