-- WIKI4AI-33: pgvector support for semantic document search (epic WIKI4AI-26)
-- Requires the pgvector PostgreSQL image (pgvector/pgvector:pg16).

CREATE EXTENSION IF NOT EXISTS vector;

ALTER TABLE documents ADD COLUMN embedding vector(1024);

-- HNSW index with cosine distance ops. Overkill for a few hundred vectors,
-- but trivially cheap and ready for corpus growth.
CREATE INDEX idx_documents_embedding_hnsw ON documents USING hnsw (embedding vector_cosine_ops);
