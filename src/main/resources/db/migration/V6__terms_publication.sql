-- DB 설치와 운영 웹 배포 시점이 다를 수 있으므로 여기서는 판본별 시행일의 저장소만 만듭니다.
CREATE TABLE terms_publications (
    terms_version VARCHAR(32) PRIMARY KEY CHECK (btrim(terms_version) <> ''),
    effective_date DATE NOT NULL
);
