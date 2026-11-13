-- 10주차 제공 SQL 기반. 기존 admin 계정을 사용하며 재실행해도 같은 제목은 추가하지 않는다.
USE spring;
SET @author = (SELECT id FROM member WHERE username = 'admin');
SET SESSION cte_max_recursion_depth = 200;

INSERT INTO posts (title, content, member_id, created_at, view_count)
WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 105)
SELECT CONCAT('테스트 글 ', n),
       CONCAT('페이징 확인용 ', n, '번째 글입니다.', IF(n % 8 = 0, ' 이번 주 스프링 스터디 모집 공지', '')),
       @author, NOW() - INTERVAL (106 - n) MINUTE, 0
FROM seq
WHERE @author IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM posts p WHERE p.member_id = @author AND p.title = CONCAT('테스트 글 ', n));

SELECT DATABASE() AS current_database;
SHOW TABLES;
SELECT COUNT(*) AS post_count FROM posts;
