-- The jobs table is the durable, indexed ready/leased work queue.
CREATE TABLE IF NOT EXISTS llm_jobs (
  id UUID PRIMARY KEY,
  endpoint TEXT NOT NULL CHECK (endpoint IN ('chat','generate','show','embed','embeddings','openai-chat','openai-completion','openai-embedding')),
  model TEXT NOT NULL,
  payload JSONB NOT NULL,
  owner_hash TEXT NOT NULL,
  state TEXT NOT NULL DEFAULT 'READY' CHECK (state IN ('READY','LEASED','COMPLETED','FAILED')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  available_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  lease_owner TEXT,
  lease_token UUID,
  lease_deadline TIMESTAMPTZ,
  attempts INTEGER NOT NULL DEFAULT 0,
  last_error TEXT
);
CREATE INDEX IF NOT EXISTS llm_jobs_ready_idx ON llm_jobs(model, created_at) WHERE state='READY';
-- Defensive invariant: even if two poll requests race, one worker cannot own two leases.
CREATE UNIQUE INDEX IF NOT EXISTS llm_jobs_one_lease_per_worker_idx ON llm_jobs(lease_owner) WHERE state='LEASED';
CREATE INDEX IF NOT EXISTS llm_jobs_lease_idx ON llm_jobs(lease_deadline) WHERE state='LEASED';
CREATE INDEX IF NOT EXISTS llm_jobs_owner_idx ON llm_jobs(owner_hash, id);

-- The second durable queue is keyed by request ID. It survives API process restarts.
CREATE TABLE IF NOT EXISTS llm_results (
  job_id UUID PRIMARY KEY REFERENCES llm_jobs(id) ON DELETE CASCADE,
  success BOOLEAN NOT NULL,
  http_status INTEGER NOT NULL,
  body JSONB NOT NULL,
  completed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS llm_results_retention_idx ON llm_results(completed_at);

CREATE TABLE IF NOT EXISTS llm_workers (
  client_id TEXT PRIMARY KEY,
  models TEXT[] NOT NULL,
  registered_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_seen TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS llm_workers_seen_idx ON llm_workers(last_seen);
