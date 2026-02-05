-- 스테이징 테이블: CSV 원본 데이터 적재
CREATE TABLE IF NOT EXISTS customer_stg (
    id              BIGSERIAL PRIMARY KEY,
    customer_id     VARCHAR(50) NOT NULL,
    email           VARCHAR(255),
    name            VARCHAR(100),
    phone           VARCHAR(20),
    run_date        DATE NOT NULL,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_customer_stg_run_date ON customer_stg(run_date);
CREATE INDEX IF NOT EXISTS idx_customer_stg_customer_id ON customer_stg(customer_id);

-- 타깃 테이블: 정제/업서트 결과
CREATE TABLE IF NOT EXISTS customer (
    id              BIGSERIAL PRIMARY KEY,
    customer_id     VARCHAR(50) NOT NULL UNIQUE,
    email           VARCHAR(255),
    name            VARCHAR(100),
    phone           VARCHAR(20),
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_customer_email ON customer(email);

-- 오류 레코드 격리 테이블
CREATE TABLE IF NOT EXISTS customer_err (
    id              BIGSERIAL PRIMARY KEY,
    customer_id     VARCHAR(50),
    email           VARCHAR(255),
    name            VARCHAR(100),
    phone           VARCHAR(20),
    error_message   TEXT,
    run_date        DATE NOT NULL,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_customer_err_run_date ON customer_err(run_date);

-- 일별 집계 테이블 (선택)
CREATE TABLE IF NOT EXISTS customer_daily_stats (
    id              BIGSERIAL PRIMARY KEY,
    run_date        DATE NOT NULL UNIQUE,
    total_count     INTEGER DEFAULT 0,
    success_count   INTEGER DEFAULT 0,
    error_count     INTEGER DEFAULT 0,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);