-- Reorganize the built-in navigation into the current product domains.
-- Existing custom menus and user-controlled enabled/deleted state are preserved.
-- owner=navigation

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM platform_navigation_menu
        WHERE menu_code IN ('communication', 'payments')
          AND NOT (built_in = true AND menu_type = 'CATALOG')
    ) THEN
        RAISE EXCEPTION 'Navigation catalog code communication or payments is already owned by a non-built-in catalog/menu';
    END IF;
END;
$$;

INSERT INTO platform_navigation_menu(
    id,parent_id,menu_code,label_key,label_default,menu_type,section_key,path,
    icon,permission_key,global_only,module_id,sort_order,enabled,built_in,deleted,version,component_key
)
VALUES
    ('00000000-0000-0000-0000-000000000062',NULL,'communication','communicationCenter','消息与反馈','CATALOG',NULL,NULL,NULL,NULL,false,NULL,50,true,true,false,1,NULL),
    ('00000000-0000-0000-0000-000000000063',NULL,'payments','paymentManagement','支付管理','CATALOG',NULL,NULL,NULL,NULL,false,NULL,60,true,true,false,1,NULL)
ON CONFLICT(menu_code) DO UPDATE SET
    parent_id = NULL,
    label_key = EXCLUDED.label_key,
    label_default = EXCLUDED.label_default,
    menu_type = 'CATALOG',
    section_key = NULL,
    path = NULL,
    permission_key = NULL,
    global_only = false,
    module_id = NULL,
    sort_order = EXCLUDED.sort_order,
    version = platform_navigation_menu.version + 1,
    updated_at = now(),
    component_key = NULL
WHERE platform_navigation_menu.built_in = true
  AND platform_navigation_menu.menu_type = 'CATALOG';

WITH root_catalog(menu_code,label_key,label_default,sort_order) AS (
    VALUES
        ('overview','overview','工作空间',10),
        ('ai-management','aiManagement','AI 中心',20),
        ('user-management','userManagement','用户与权限',30),
        ('content-management','contentManagement','内容与资源',40),
        ('communication','communicationCenter','消息与反馈',50),
        ('payments','paymentManagement','支付管理',60),
        ('operations','operationsCenter','系统运维',70),
        ('administration','administration','平台配置',80)
)
UPDATE platform_navigation_menu catalog
SET label_key = root_catalog.label_key,
    label_default = root_catalog.label_default,
    sort_order = root_catalog.sort_order,
    version = catalog.version + 1,
    updated_at = now()
FROM root_catalog
WHERE catalog.menu_code = root_catalog.menu_code
  AND catalog.built_in = true
  AND catalog.menu_type = 'CATALOG'
  AND catalog.parent_id IS NULL
  AND (catalog.label_key IS DISTINCT FROM root_catalog.label_key
       OR catalog.label_default IS DISTINCT FROM root_catalog.label_default
       OR catalog.sort_order IS DISTINCT FROM root_catalog.sort_order);

WITH placement(menu_code,from_catalog,to_catalog,sort_order,label_key,label_default) AS (
    VALUES
        ('home','overview','overview',10,NULL,NULL),
        ('files','overview','overview',20,NULL,NULL),
        ('ai-console','ai-management','ai-management',10,NULL,NULL),
        ('ai-models','ai-management','ai-management',20,NULL,NULL),
        ('ai-judgment','ai-management','ai-management',30,NULL,NULL),
        ('ai-capabilities','ai-management','ai-management',40,NULL,NULL),
        ('users','user-management','user-management',10,NULL,NULL),
        ('online-users','user-management','user-management',20,NULL,NULL),
        ('roles','user-management','user-management',30,NULL,NULL),
        ('organizations','user-management','user-management',40,NULL,NULL),
        ('profile-fields','administration','user-management',50,NULL,NULL),
        ('security','administration','user-management',60,'authenticationSecurity','认证与安全'),
        ('authentication','administration','user-management',70,NULL,NULL),
        ('passkey','administration','user-management',80,NULL,NULL),
        ('wechat','administration','user-management',90,NULL,NULL),
        ('captcha','administration','user-management',100,NULL,NULL),
        ('registration','administration','user-management',110,NULL,NULL),
        ('files-all','content-management','content-management',10,NULL,NULL),
        ('dictionaries','content-management','content-management',20,NULL,NULL),
        ('sensitive-words','content-management','content-management',30,NULL,NULL),
        ('localization','administration','content-management',40,NULL,NULL),
        ('notifications','operations','communication',10,NULL,NULL),
        ('smtp','administration','communication',20,'messageChannels','消息渠道'),
        ('sms','administration','communication',30,'messageChannels','消息渠道'),
        ('mail-deliveries','operations','communication',40,'deliveryRecords','投递记录'),
        ('sms-deliveries','operations','communication',50,'deliveryRecords','投递记录'),
        ('feedback','operations','communication',60,NULL,NULL),
        ('monitoring','operations','operations',10,NULL,NULL),
        ('api-docs','operations','operations',20,NULL,NULL),
        ('audit','operations','operations',30,NULL,NULL),
        ('event-outbox','operations','operations',40,NULL,NULL),
        ('modules','administration','administration',10,NULL,NULL),
        ('branding','administration','administration',20,'siteSettings','站点设置'),
        ('watermark','administration','administration',30,NULL,NULL),
        ('agreements','administration','administration',40,NULL,NULL),
        ('maintenance','administration','administration',50,NULL,NULL),
        ('navigation','administration','administration',60,NULL,NULL)
)
UPDATE platform_navigation_menu page
SET parent_id = CASE
        WHEN parent_catalog.id IS NOT NULL THEN target_catalog.id
        ELSE page.parent_id
    END,
    sort_order = placement.sort_order,
    label_key = COALESCE(placement.label_key, page.label_key),
    label_default = COALESCE(placement.label_default, page.label_default),
    version = page.version + 1,
    updated_at = now()
FROM placement
LEFT JOIN platform_navigation_menu parent_catalog
       ON parent_catalog.menu_code = placement.from_catalog
      AND parent_catalog.menu_type = 'CATALOG'
      AND parent_catalog.built_in = true
LEFT JOIN platform_navigation_menu target_catalog
       ON target_catalog.menu_code = placement.to_catalog
      AND target_catalog.menu_type = 'CATALOG'
      AND target_catalog.built_in = true
WHERE page.menu_code = placement.menu_code
  AND page.menu_type = 'PAGE'
  AND page.built_in = true
  AND page.section_key = placement.menu_code
  AND page.path = '/dashboard/' || placement.menu_code
  AND target_catalog.id IS NOT NULL
  AND (page.parent_id = parent_catalog.id OR page.parent_id = target_catalog.id)
  AND (page.parent_id IS DISTINCT FROM target_catalog.id
       OR page.sort_order IS DISTINCT FROM placement.sort_order
       OR placement.label_key IS NOT NULL AND page.label_key IS DISTINCT FROM placement.label_key
       OR placement.label_default IS NOT NULL AND page.label_default IS DISTINCT FROM placement.label_default);

-- Refresh built-in anchor labels even when an administrator moved the page to
-- a custom catalog; keep its custom parent and order untouched.
WITH anchor_label(menu_code,label_key,label_default) AS (
    VALUES
        ('security','authenticationSecurity','认证与安全'),
        ('smtp','messageChannels','消息渠道'),
        ('sms','messageChannels','消息渠道'),
        ('mail-deliveries','deliveryRecords','投递记录'),
        ('sms-deliveries','deliveryRecords','投递记录'),
        ('branding','siteSettings','站点设置')
)
UPDATE platform_navigation_menu page
SET label_key = anchor_label.label_key,
    label_default = anchor_label.label_default,
    version = page.version + 1,
    updated_at = now()
FROM anchor_label
WHERE page.menu_code = anchor_label.menu_code
  AND page.menu_type = 'PAGE'
  AND page.built_in = true
  AND page.section_key = anchor_label.menu_code
  AND page.path = '/dashboard/' || anchor_label.menu_code
  AND (page.label_key IS DISTINCT FROM anchor_label.label_key
       OR page.label_default IS DISTINCT FROM anchor_label.label_default);

-- Plugin route rows are not built-in rows. Move only known plugin-owned routes
-- that are still in their manifest's previous default catalog; an administrator
-- may have intentionally moved one to a custom catalog.
WITH placement(menu_code,module_id,from_catalog,to_catalog,sort_order) AS (
    VALUES
        ('feedback','feedback','operations','communication',60),
        ('localization','localization','administration','content-management',40),
        ('sensitive-words','sensitive-words','content-management','content-management',30),
        ('mfa-authenticator','mfa','overview','user-management',30),
        ('mfa-management','mfa','administration','user-management',120),
        ('payment-core-orders','payment-core','operations','payments',10),
        ('wechat-pay-orders','wechat-pay','operations','payments',20),
        ('alipay-pay-orders','alipay-pay','operations','payments',30),
        ('wechat-pay-settings','wechat-pay','administration','payments',40),
        ('alipay-pay-settings','alipay-pay','administration','payments',50)
)
UPDATE platform_navigation_menu page
SET parent_id = target_catalog.id,
    sort_order = placement.sort_order,
    version = page.version + 1,
    updated_at = now()
FROM placement
JOIN platform_navigation_menu source_catalog
  ON source_catalog.menu_code = placement.from_catalog
 AND source_catalog.menu_type = 'CATALOG'
 AND source_catalog.built_in = true
JOIN platform_navigation_menu target_catalog
  ON target_catalog.menu_code = placement.to_catalog
 AND target_catalog.menu_type = 'CATALOG'
 AND target_catalog.built_in = true
WHERE page.menu_code = placement.menu_code
  AND page.section_key = placement.menu_code
  AND page.path = '/dashboard/' || placement.menu_code
  AND page.module_id = placement.module_id
  AND page.menu_type = 'PAGE'
  AND page.built_in = false
  AND page.parent_id = source_catalog.id
  AND (page.parent_id IS DISTINCT FROM target_catalog.id
       OR page.sort_order IS DISTINCT FROM placement.sort_order);

-- Payment-channel pages call the shared payment-core APIs. Keep their menu
-- permissions aligned with the actual global payment read authority.
UPDATE platform_navigation_menu page
SET permission_key = 'platform:payment-core:read',
    global_only = true,
    version = page.version + 1,
    updated_at = now()
WHERE (page.menu_code, page.module_id, page.section_key, page.path) IN (
    ('wechat-pay-orders','wechat-pay','wechat-pay-orders','/dashboard/wechat-pay-orders'),
    ('alipay-pay-orders','alipay-pay','alipay-pay-orders','/dashboard/alipay-pay-orders'),
    ('wechat-pay-settings','wechat-pay','wechat-pay-settings','/dashboard/wechat-pay-settings'),
    ('alipay-pay-settings','alipay-pay','alipay-pay-settings','/dashboard/alipay-pay-settings')
)
  AND page.menu_type = 'PAGE'
  AND page.built_in = false
  AND (page.permission_key IS DISTINCT FROM 'platform:payment-core:read'
       OR page.global_only IS DISTINCT FROM true);
