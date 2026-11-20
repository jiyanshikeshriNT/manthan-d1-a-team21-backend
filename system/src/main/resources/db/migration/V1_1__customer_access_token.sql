-- ReturnRight V1.1: customer access tokens (LLD §2, G12). Numbered 1.1 so V2/V3 keep the
-- meaning the LLD gives them (returns; refunds/ledger). Only the SHA-256 hash is stored.

CREATE TABLE customer_access_token (
    token_hash VARCHAR(64) PRIMARY KEY,
    order_id   BIGINT NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_customer_access_token_order FOREIGN KEY (order_id) REFERENCES customer_order (id)
);
CREATE INDEX idx_customer_access_token_expires ON customer_access_token (expires_at);
