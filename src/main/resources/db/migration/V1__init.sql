-- =============================================================================
-- V1__init.sql — Initial schema for the Search Engine Aggregator Service
-- Requirements: 5.1, 5.4, 5.5
-- =============================================================================

CREATE TABLE contents (
  id              UUID PRIMARY KEY,
  provider        VARCHAR(64)  NOT NULL,
  external_id     VARCHAR(128) NOT NULL,
  title           TEXT         NOT NULL,
  description     TEXT,
  type            VARCHAR(16)  NOT NULL CHECK (type IN ('VIDEO','TEXT')),
  views           BIGINT       NOT NULL DEFAULT 0,
  likes           BIGINT       NOT NULL DEFAULT 0,
  reading_time    INTEGER      NOT NULL DEFAULT 0,
  reactions       BIGINT       NOT NULL DEFAULT 0,
  duration        VARCHAR(32),
  tags            TEXT[]       NOT NULL DEFAULT '{}',
  published_at    TIMESTAMPTZ  NOT NULL,
  final_score     DOUBLE PRECISION NOT NULL DEFAULT 0,
  popularity_score DOUBLE PRECISION NOT NULL DEFAULT 0,
  relevance_score DOUBLE PRECISION NOT NULL DEFAULT 0,
  version         INTEGER      NOT NULL DEFAULT 0,
  created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  CONSTRAINT uq_contents_provider_external UNIQUE (provider, external_id)
);

CREATE INDEX idx_contents_type        ON contents (type);
CREATE INDEX idx_contents_final_score ON contents (final_score DESC);
CREATE INDEX idx_contents_fts ON contents
  USING GIN (to_tsvector('simple', title || ' ' || COALESCE(description, '')));
