-- Sensitive-word category is descriptive metadata; matcher policy remains
-- based only on the stored word, severity, and action columns.
INSERT INTO platform_dictionary(id, code, name, enabled, structure, remark)
VALUES (
    '00000000-0000-0000-0000-000000000205',
    'sensitive_word_category',
    '敏感词分类',
    true,
    'FLAT',
    'Labels organize sensitive words only; matching decisions do not depend on categories.'
)
ON CONFLICT (code) DO NOTHING;

-- Seed every retained category value without changing sensitive-word rows.
-- DEFAULT and IMPORTED are guaranteed choices for manual creation/import.
WITH categories AS (
    SELECT category::text AS value FROM platform_sensitive_word
    UNION
    SELECT 'DEFAULT'::text
    UNION
    SELECT 'IMPORTED'::text
), ranked AS (
    SELECT value,
           CASE value
               WHEN 'DEFAULT' THEN '默认分类'
               WHEN 'IMPORTED' THEN '导入词'
               ELSE value
           END AS label,
           (row_number() OVER (
               ORDER BY CASE value WHEN 'DEFAULT' THEN 0 WHEN 'IMPORTED' THEN 1 ELSE 2 END,
                        value COLLATE "C"
           ) * 10)::integer AS sort_order
    FROM categories
)
INSERT INTO platform_dictionary_item(id, dictionary_id, value, label, sort_order, enabled, remark)
SELECT md5('sensitive-word-category:' || ranked.value)::uuid,
       dictionary.id,
       ranked.value,
       ranked.label,
       ranked.sort_order,
       true,
       ''
FROM ranked
JOIN platform_dictionary dictionary ON dictionary.code = 'sensitive_word_category'
ON CONFLICT (dictionary_id, value) DO NOTHING;

CREATE INDEX IF NOT EXISTS platform_sensitive_word_category_recent
    ON platform_sensitive_word (category, updated_at DESC, id)
    WHERE deleted = false;
