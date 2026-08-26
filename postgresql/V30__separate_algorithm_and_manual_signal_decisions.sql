ALTER TABLE risk_signal ADD COLUMN IF NOT EXISTS manual_decision VARCHAR(32);
ALTER TABLE risk_signal ADD COLUMN IF NOT EXISTS manual_recommended_action VARCHAR(255);

-- V29 initially reused the algorithm decision fields. Preserve the human values,
-- then restore the immutable algorithm output from the first CLAIM audit record.
UPDATE risk_signal s
SET manual_decision = s.decision,
    manual_recommended_action = s.recommended_action
WHERE EXISTS (
    SELECT 1 FROM risk_signal_review_record r
    WHERE r.signal_id=s.signal_id AND r.action='EDIT'
);

UPDATE risk_signal s
SET decision = (
        SELECT r.decision FROM risk_signal_review_record r
        WHERE r.signal_id=s.signal_id AND r.action='CLAIM'
        ORDER BY r.created_at,r.id LIMIT 1
    ),
    recommended_action = (
        SELECT r.recommended_action FROM risk_signal_review_record r
        WHERE r.signal_id=s.signal_id AND r.action='CLAIM'
        ORDER BY r.created_at,r.id LIMIT 1
    )
WHERE EXISTS (
    SELECT 1 FROM risk_signal_review_record r
    WHERE r.signal_id=s.signal_id AND r.action='EDIT'
);
