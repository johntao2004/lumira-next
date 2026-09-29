-- Feedback owns the category values persisted on platform_feedback. The seeded
-- lookup rows use the dictionaries module's shared storage and lookup contract.
ALTER TABLE platform_feedback
    ADD COLUMN category character varying(100),
    ADD CONSTRAINT platform_feedback_category_check
        CHECK (category IS NULL OR category ~ '^[A-Z][A-Z0-9_]{0,31}$');

COMMENT ON COLUMN platform_feedback.category IS
    'Stable value from work_order_feedback_category; NULL preserves legacy uncategorized feedback.';

CREATE INDEX platform_feedback_category_recent
    ON platform_feedback (category, created_at DESC, id DESC);

INSERT INTO platform_dictionary(id, code, name, enabled, structure, remark)
VALUES (
    '00000000-0000-0000-0000-000000000204',
    'work_order_feedback_category',
    '工单反馈分类',
    true,
    'FLAT',
    'Stable category values are stored on feedback; edit labels and order, keep values unchanged.'
)
ON CONFLICT (code) DO NOTHING;

INSERT INTO platform_dictionary_item(id, dictionary_id, value, label, sort_order, enabled, remark)
SELECT seed.id::uuid, d.id, seed.value, seed.label, seed.sort_order, true, ''
FROM (VALUES
    ('00000000-0000-0000-0000-000000000314', 'QUESTION', '业务咨询', 10),
    ('00000000-0000-0000-0000-000000000315', 'BUG', '问题反馈', 20),
    ('00000000-0000-0000-0000-000000000316', 'FEATURE', '功能建议', 30),
    ('00000000-0000-0000-0000-000000000317', 'ACCOUNT', '账号与权限', 40),
    ('00000000-0000-0000-0000-000000000318', 'OTHER', '其他', 50)
) AS seed(id, value, label, sort_order)
JOIN platform_dictionary d ON d.code = 'work_order_feedback_category'
ON CONFLICT (dictionary_id, value) DO NOTHING;
