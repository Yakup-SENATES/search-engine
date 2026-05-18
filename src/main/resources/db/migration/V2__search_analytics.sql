CREATE TABLE search_analytics (
  id               BIGSERIAL PRIMARY KEY,
  requested_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  q                VARCHAR(200) NOT NULL,
  type             VARCHAR(16),
  sort             VARCHAR(16)  NOT NULL,
  page             INTEGER      NOT NULL,
  "limit"          INTEGER      NOT NULL,
  total_results    BIGINT,
  latency_ms       INTEGER      NOT NULL,
  cache_hit        BOOLEAN      NOT NULL,
  request_id       VARCHAR(64),
  client_ip_hash   CHAR(64),
  error_code       VARCHAR(32)
);

CREATE INDEX idx_search_analytics_requested_at ON search_analytics (requested_at DESC);
CREATE INDEX idx_search_analytics_q             ON search_analytics (q);
