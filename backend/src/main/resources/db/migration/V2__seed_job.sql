-- Placeholder job for the single-recruiter demo. Fixed UUID so tools/seed.sh
-- (and later phases' DoD commands) can target it deterministically.
INSERT INTO jobs (id, title, description, requirements) VALUES (
    '00000000-0000-0000-0000-000000000001',
    'Backend Engineer — Java/Kafka',
    'Design and operate high-throughput, event-driven services: Kafka pipelines, Spring Boot APIs and PostgreSQL persistence. You will own services end to end — schema to deploy — and care about idempotency, backpressure and observability.',
    '{
      "must_have": ["java", "spring-boot", "kafka", "sql"],
      "nice_to_have": ["docker", "aws", "react"],
      "min_exp_months": 24,
      "fresher_friendly": false,
      "weights": "default"
    }'::jsonb
);
