-- Second demo job: fresher-friendly, so the weight shift (experience → projects/
-- education) is visible in the dashboard, not just in unit tests. Fixed UUID
-- ...0002 so tools and demos can target it.
INSERT INTO jobs (id, title, description, requirements) VALUES (
    '00000000-0000-0000-0000-000000000002',
    'Graduate Software Engineer (Fresher Friendly)',
    'Entry-level backend role: we care about fundamentals, projects and the ability to learn — not years on the clock. Java and SQL basics required; everything else is teachable.',
    '{
      "must_have": ["java", "sql"],
      "nice_to_have": ["spring-boot", "git", "docker", "react"],
      "min_exp_months": 0,
      "fresher_friendly": true,
      "weights": "default"
    }'::jsonb
);
