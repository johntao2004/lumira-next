# 自定义资料字段与页面位置

后台“资料字段”页面统一配置字段类型、字典、公开注册准入、展示页签与排序。同一字段可同时用于多个页面，沿用同一个 key 和同一份账号资料；管理员移动展示位置后，用户已有资料不会丢失。

首批可配置位置如下，页面控件均复用全局表单组件和主题：

| 页面 | 可选位置 | 产品规则 |
| --- | --- | --- |
| 个人资料 | 基本信息、详细信息、自定义字段 | 在对应页签展示；一次保存覆盖已修改的全部页签，保留未打开页签的值 |
| 管理员用户编辑 | 资料页签 | 只加载该宿主准入字段；管理员专用字段不出现在个人资料或公开注册 |
| 公开注册 | 附加资料区 | 必须显式开启公开准入；始终可选，不阻断最低注册流程 |

字段位置采用固定白名单。新增页面或页签需要登记宿主契约，后台不能输入任意路由、HTML、组件或脚本。隐藏、移动和移除字段只改变展示和校验范围，不自动清除数据库中已保存的资料。

以下为接口、兼容性与容量约束。

Profile fields are described by the versioned `profile-fields` settings document. A placement is data for one registered host and tab; it never chooses a route, component, HTML fragment, or script. The supported target registry is:

| Page | Tab | Host |
| --- | --- | --- |
| `account-profile` | `basic` | Account profile drawer, basic information |
| `account-profile` | `details` | Account profile drawer, details |
| `account-profile` | `custom` | Account profile drawer, custom fields |
| `admin-user-editor` | `profile` | Administrator user editor, profile tab |
| `public-registration` | `additional` | Optional public registration fields |

Each field can have at most one placement in each host. Its `placements` entries use `{ page, tab, sortNo }`; sort order is scoped to that placement. The same key may appear in several hosts, but its value remains a single entry in the account's existing `platform_user.profile_values` JSON map. Moving, hiding, or deleting a field or placement changes where it is available and does not delete saved values.

## Compatibility and required values

When a saved field has no `placements` property, the reader supplies the legacy locations and order: custom fields stay on `account-profile/custom`; built-in profile fields stay on `account-profile/basic`; `displayName` also remains on `admin-user-editor/profile`. The reader preserves each field's existing `sortNo`. Saving an older schema materializes those defaults without changing its displayed order.

Required validation applies only to visible fields assigned to the active host. Fields on another host, hidden fields, and retained values for removed fields do not block the current save. A visible required custom field must have at least one non-registration placement. A field placed on public registration is always optional there, even if it is required in the authenticated profile or user editor.

The settings writer accepts only the registered targets above, rejects duplicate locations within a host, and caps the complete schema at 100 fields. Keys are stable identifiers and may not use identity-sensitive or JavaScript prototype names. Field values are validated against the current type and options on the server. Each custom value is limited to 200 characters (2,000 for text areas), and the profile-values patch is limited to 64 KiB of UTF-8 key/value data. HTTP endpoints also apply explicit request-body limits.

## Public registration projection

`GET /api/auth/registration/profile-schema` is the only public schema projection. A field is returned only when it is custom, visible, explicitly marked `publicRegistrationAllowed`, and placed at `public-registration/additional`. The projected schema is limited to 20 fields, at most 200 options per field, 1,000 option slots overall, and 1 MiB of UTF-8 JSON. Reusing a dictionary in several fields counts its options for every field. Configuration saves count current enabled dictionary items in one batch and reject excess options with `registrationDictionaryTooLarge`. If a dictionary grows beyond the published limits later, the projection returns `503 registrationProfileSchemaUnavailable`; it does not truncate valid choices. The projected schema contains only the version and renderer data: key, label, description, type, placeholder, optional options, and `required: false`. It does not return administrative placement data, dictionary codes, profile values, or unrelated profile fields.

Static options project to `{ value, label }` pairs. Dictionary-backed options are batch-loaded by distinct dictionary code and include only enabled dictionaries and enabled items. The projection counts options before loading labels and requests at most 201 per dictionary, so it can detect an over-limit dictionary without materializing a 20,000-item dataset. Optional schema loading failure does not block the minimum registration flow. A registration completion service must pass `{ schemaVersion, values }` to `ProfileRegistrationPort.validateAndStoreByAccount` inside the account-creation transaction. The port rejects schema-version conflicts and values outside the current allow-list. The registration request body budget is 128 KiB; the limit is separate from ordinary profile patch limits. Registration remains independent of authentication and does not create a login session.

## API hosts and storage

The account profile uses `/api/account/profile` for built-in fields and `/api/account/profile-values` for custom-value reads. Its combined drawer submits built-in and custom edits atomically through the account profile update. The administrator editor reads custom values from `/api/platform/users/{id}/profile-values` and saves them through `/api/platform/users/{id}/editor`, alongside versioned user edits. Both hosts receive only their assigned fields and the dictionary label metadata needed to display current selections, including a previously selected item that has since been disabled.

Schema writes take the exclusive profile-schema advisory lock. Profile value writes take the matching shared lock before locking the account row, so unrelated account writes can proceed concurrently while a schema change cannot race with validation. Dictionary values are validated in batches. Writes replace only keys assigned to the relevant host; values outside that host remain unchanged.

## Frontend contract

`apps/web/src/platform/profile-fields/profile-field-renderer.tsx` exports `ProfileFieldRenderer({ fields, disabled?, dictionarySelections? })`. Hosts pass only the projected fields they are allowed to render. The renderer supports text, text area, number, date, and select controls; it treats labels and options as text data, never as executable markup. Contiguous field groups stay in their original placement order so group labels cannot reorder administrator-specified sort positions.

Public registration uses the same renderer with its narrower projection. The account profile places the renderer within the basic, details, or custom tabs. The administrator editor uses it only in the profile tab. Authentication identifiers, passwords, roles, email, phone, MFA, and account-state controls are not custom profile fields.
