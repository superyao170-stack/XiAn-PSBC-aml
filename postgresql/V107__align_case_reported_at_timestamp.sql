-- CfRiskCase uses LocalDateTime for all business-facing case timestamps.
-- V61 introduced reported_at after the original timestamp normalization in V2,
-- so it was the only cf_risk_case timestamp still using TIMESTAMPTZ. PostgreSQL's
-- driver cannot map a non-null TIMESTAMPTZ directly to LocalDateTime.
ALTER TABLE cf_risk_case
    ALTER COLUMN reported_at TYPE TIMESTAMP
    USING reported_at AT TIME ZONE current_setting('TIMEZONE');
