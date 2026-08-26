-- analysis_job.batch_id is a polymorphic business reference: structured jobs
-- point to risk_ingest_batch, while unstructured jobs point to
-- unstructured_ingest_batch. The original FK only allowed the first form and
-- accidentally accepted unstructured jobs when ids happened to collide.
ALTER TABLE analysis_job DROP CONSTRAINT IF EXISTS analysis_job_batch_id_fkey;

COMMENT ON COLUMN analysis_job.batch_id IS
    'Batch id resolved by job_type: UNSTRUCTURED -> unstructured_ingest_batch; otherwise -> risk_ingest_batch';

-- Validation is complete once every document has a valid schema result. Older
-- code left those batches in VALIDATING even after returning VALID to callers.
UPDATE unstructured_ingest_batch b
SET status = 'READY'
WHERE b.status = 'VALIDATING'
  AND EXISTS (
      SELECT 1 FROM unstructured_document d WHERE d.batch_id = b.id
  )
  AND NOT EXISTS (
      SELECT 1 FROM unstructured_document d
      WHERE d.batch_id = b.id AND COALESCE(d.validation_status, '') <> 'VALID'
  );
