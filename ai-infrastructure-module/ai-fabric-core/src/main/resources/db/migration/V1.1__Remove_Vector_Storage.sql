-- Migration: Remove Vector Storage from AISearchableEntity
-- Version: 1.1
-- Description: Remove embeddings table and add vector reference fields to AISearchableEntity

-- Remove embeddings table
DROP TABLE IF EXISTS ai_embeddings;

-- Add vector reference fields to AISearchableEntity
ALTER TABLE ai_searchable_entities
ADD COLUMN vector_id VARCHAR(255),
ADD COLUMN vector_updated_at TIMESTAMP;

-- Create index on vector_id for performance
CREATE INDEX idx_ai_searchable_entities_vector_id ON ai_searchable_entities(vector_id);

-- Create index on vector_updated_at for performance
CREATE INDEX idx_ai_searchable_entities_vector_updated_at ON ai_searchable_entities(vector_updated_at);

-- Remove old indexes related to embeddings
DROP INDEX IF EXISTS idx_ai_embeddings_entity_id;

-- Add comments for documentation
COMMENT ON COLUMN ai_searchable_entities.vector_id IS 'Reference to vector in external vector database';
COMMENT ON COLUMN ai_searchable_entities.vector_updated_at IS 'Timestamp when vector was last updated in vector database';