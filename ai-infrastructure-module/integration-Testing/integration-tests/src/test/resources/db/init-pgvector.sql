-- PostgreSQL with pgvector Extension Initialization Script
-- This script is executed automatically when the pgvector container starts
-- to set up the vector database schema and indexes.

-- Enable pgvector extension
CREATE EXTENSION IF NOT EXISTS vector;

-- Create vectors table for storing vector embeddings
CREATE TABLE IF NOT EXISTS vectors (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_type VARCHAR(255) NOT NULL,
    entity_id VARCHAR(255) NOT NULL,
    content TEXT,
    embedding vector(1536),
    metadata JSONB DEFAULT '{}',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(entity_type, entity_id)
);

-- Create indexes for efficient vector search
-- IVFFlat index for approximate nearest neighbor search using cosine similarity
CREATE INDEX IF NOT EXISTS vectors_embedding_idx
    ON vectors USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);

-- Index for entity lookups
CREATE INDEX IF NOT EXISTS vectors_entity_idx
    ON vectors (entity_type, entity_id);

-- GIN index for JSONB metadata queries
CREATE INDEX IF NOT EXISTS vectors_metadata_idx
    ON vectors USING gin (metadata);
