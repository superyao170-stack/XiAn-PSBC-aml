UPDATE unstructured_document
SET validation_status = CASE
    WHEN COALESCE(extracted_text, '') = '' THEN 'INVALID'
    ELSE 'VALID'
END
WHERE validation_status IS NULL;

UPDATE unstructured_ingest_batch b
SET source_count = counts.source_count,
    accepted_count = counts.accepted_count,
    rejected_count = counts.rejected_count,
    status = CASE
        WHEN counts.invalid_count > 0 THEN 'FAILED'
        WHEN counts.source_count > 0 THEN 'READY'
        ELSE b.status
    END
FROM (
    SELECT batch_id,
           COUNT(*) AS source_count,
           COUNT(*) FILTER (WHERE validation_status = 'VALID') AS accepted_count,
           COUNT(*) FILTER (WHERE validation_status = 'INVALID') AS rejected_count,
           COUNT(*) FILTER (WHERE validation_status = 'INVALID') AS invalid_count
    FROM unstructured_document
    GROUP BY batch_id
) counts
WHERE counts.batch_id = b.id;
