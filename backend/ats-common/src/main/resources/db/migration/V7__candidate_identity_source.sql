-- Where a candidate's name/email came from:
--   form            typed in by the applicant / API caller (every existing row)
--   resume_pending  bulk upload: a placeholder until the extractor reads the resume
--   resume          bulk upload: name/email/phone taken from the resume itself
ALTER TABLE candidates ADD COLUMN identity_source TEXT NOT NULL DEFAULT 'form'
    CHECK (identity_source IN ('form', 'resume_pending', 'resume'));
