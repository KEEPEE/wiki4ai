-- WIKI4AI-197 commit 17: a durable queue for document embeddings.
--
-- The problem this table fixes. Embedding a document used to happen inside the
-- database transaction of the create/update that produced it: DocumentService saved
-- the row and then called the embedding sidecar over HTTP before committing. The
-- HTTP call held the transaction — and with it a pooled connection and the row lock
-- on the document — for as long as the sidecar took to answer, and the read timeout
-- was 120 seconds. A slow or dead sidecar plus a handful of concurrent saves was
-- enough to exhaust the connection pool and stall every request in the instance,
-- not just the saves.
--
-- The fix has two halves. The work moves AFTER_COMMIT, so no transaction outlives
-- the HTTP call. And the intent to embed is written here, in the same transaction as
-- the document itself, so the work survives everything that can happen between the
-- commit and the embed: a failed call, a crashed worker, a restarted container. A
-- row that is still PENDING when the process comes back is picked up again by the
-- scheduled drainer; without this table the request would simply be gone.
--
-- Why document_id carries no foreign key. A queue is a work list, not an ownership
-- relation, and "the document this row refers to no longer exists" is a normal,
-- expected state — a document deleted before its embed ran — rather than an
-- integrity violation. An FK would force an ON DELETE CASCADE on every document
-- delete (an extra write on the delete path, and a cascade chain through projects →
-- documents → queue entries) to represent a state the worker already handles by
-- marking the row DONE. The index below is what the table actually needs: the drainer
-- looks rows up by id.
CREATE TABLE embedding_queue_entries (
    id BIGSERIAL PRIMARY KEY,
    document_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts INTEGER NOT NULL DEFAULT 0,
    last_error VARCHAR(512),
    next_attempt_at TIMESTAMP NOT NULL DEFAULT now(),
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT chk_embedding_queue_status CHECK (status IN ('PENDING', 'DONE', 'FAILED')),
    CONSTRAINT chk_embedding_queue_attempts CHECK (attempts >= 0)
);

-- The drainer's query: "the oldest work that is due for an attempt", restricted to
-- rows that still need doing. Partial because DONE and FAILED rows are never read
-- again and they are the overwhelming majority of the table over time.
CREATE INDEX idx_embedding_queue_due ON embedding_queue_entries (next_attempt_at, id)
    WHERE status = 'PENDING';

-- The other access pattern: everything this table is ever asked about is keyed by
-- document. Deliberately NOT unique. It is tempting to make it a unique partial index
-- on the open rows so a document could never have two pending embeds, but that turns
-- a harmless race into a lost save: two concurrent updates of one document would
-- collide on the index, and because the insert happens inside the document's own
-- transaction a violation would roll back the user's edit. Two rows mean the same
-- text embedded twice and the same vector written twice — duplicate work, no
-- incorrectness — which is the right trade against failing a save.
CREATE INDEX idx_embedding_queue_document ON embedding_queue_entries (document_id);
