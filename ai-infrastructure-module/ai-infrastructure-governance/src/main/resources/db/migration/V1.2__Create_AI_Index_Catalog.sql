-- Governance module: create minimal index catalog table.
-- Stores inventory for retention and deletion discovery without duplicating full content.

CREATE TABLE IF NOT EXISTS ai_index_catalog (
    catalog_key VARCHAR(300) PRIMARY KEY,
    entity_type VARCHAR(128) NOT NULL,
    entity_id VARCHAR(128) NOT NULL,
    vector_id VARCHAR(255),
    indexed_created_at TIMESTAMP NULL,
    indexed_updated_at TIMESTAMP NULL,
    metadata_json TEXT NULL
);

CREATE INDEX IF NOT EXISTS idx_ai_index_catalog_type
    ON ai_index_catalog (entity_type);

CREATE INDEX IF NOT EXISTS idx_ai_index_catalog_updated
    ON ai_index_catalog (indexed_updated_at);
