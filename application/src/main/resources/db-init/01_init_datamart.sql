CREATE TABLE region (
    region_id   SERIAL PRIMARY KEY,
    region_name VARCHAR(100) NOT NULL
);

CREATE TABLE merchant (
    merchant_id   SERIAL PRIMARY KEY,
    merchant_name VARCHAR(200) NOT NULL,
    region_id     INT NOT NULL REFERENCES region(region_id)
);

CREATE TABLE transaction (
    transaction_id SERIAL PRIMARY KEY,
    merchant_id     INT NOT NULL REFERENCES merchant(merchant_id),
    transaction_ts  TIMESTAMP NOT NULL,
    amount          NUMERIC(12,2) NOT NULL,
    status          VARCHAR(20) NOT NULL, -- 'APPROVED' | 'DECLINED' | 'REVERSED'
    decline_reason  VARCHAR(100)
);

CREATE INDEX idx_transaction_ts ON transaction(transaction_ts);
CREATE INDEX idx_transaction_status ON transaction(status);

INSERT INTO region (region_name) VALUES
    ('Northeast'), ('Midwest'), ('South'), ('West');

INSERT INTO merchant (merchant_name, region_id) VALUES
    ('Acme Retail', 1), ('Northwind Traders', 2), ('Contoso Goods', 3), ('Fabrikam Supply', 4),
    ('Globex Storefront', 1), ('Initech Mart', 3);

-- One random draw per row (via the CTE) so status and decline_reason stay consistent
-- with each other, across a spread of transactions over the last ~180 days.
WITH seed AS (
    SELECT
        g AS seq,
        (RANDOM() * 5 + 1)::INT AS merchant_id,
        NOW() - (RANDOM() * INTERVAL '180 days') AS transaction_ts,
        ROUND((RANDOM() * 480 + 5)::NUMERIC, 2) AS amount,
        RANDOM() AS outcome_roll,
        FLOOR(RANDOM() * 4 + 1)::INT AS reason_idx
    FROM generate_series(1, 2000) AS g
)
INSERT INTO transaction (merchant_id, transaction_ts, amount, status, decline_reason)
SELECT
    merchant_id,
    transaction_ts,
    amount,
    CASE WHEN outcome_roll < 0.12 THEN 'DECLINED'
         WHEN outcome_roll < 0.15 THEN 'REVERSED'
         ELSE 'APPROVED' END,
    CASE WHEN outcome_roll < 0.12 THEN
        (ARRAY['INSUFFICIENT_FUNDS','SUSPECTED_FRAUD','CARD_EXPIRED','LIMIT_EXCEEDED'])[reason_idx]
    ELSE NULL END
FROM seed;

-- Read-only role for the MCP gateway's postgres server (see mcp-config.yaml).
-- Unlike QueryGuard (an application-layer check the Spring app could
-- theoretically be coded around), REVOKE/GRANT here is enforced by Postgres
-- itself regardless of which client connects as this role.
CREATE ROLE mcp_reader WITH LOGIN PASSWORD 'mcp_reader_password';
GRANT CONNECT ON DATABASE datamart TO mcp_reader;
GRANT USAGE ON SCHEMA public TO mcp_reader;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO mcp_reader;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO mcp_reader;
