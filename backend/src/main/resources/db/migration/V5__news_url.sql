-- 기사 원문 주소 (문서 수정 대기 목록 #11)
-- 기준: NEWS_AI_001 #4(요약 실패 시 제목과 링크 표시), 테이블 정의서 '테이블정의서' 71~80행(news)
-- develop에 들어간 뒤에는 이 파일을 고치지 않고 다음 번호로 보탠다(합의서 3절).
--
-- 기존 행이 있으면 NOT NULL 추가가 실패함. V5 전에는 news에 쓰는 코드가 없어 모든 환경에서 비어 있음
-- 중복 수집 방지는 news_id(원문 링크 해시, PK)가 맡으므로 UNIQUE를 따로 두지 않음
-- 앱 계정 권한은 V2의 테이블 단위 GRANT(SELECT, INSERT, UPDATE ON news)가 새 컬럼에도 적용됨

ALTER TABLE news ADD COLUMN news_url VARCHAR(1000) NOT NULL;
