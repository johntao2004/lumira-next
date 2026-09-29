# Ordinary UI synthetic cleanup check — 2026-09-29

## Sensitive-word CSV fixtures

- Used a new isolated Edge tab, `1021204494`, at `http://127.0.0.1:8020/dashboard/sensitive-words`. The visible header identified the signed-in user as `管理员`.
- The unfiltered table initially showed `共 20 条`. Searched the exact strings one at a time using the visible `敏感词` field and `查询` button:

  | Exact query | Visible result |
  |---|---|
  | `ALPHA` | `暂无数据` |
  | `WITH,COMMA` | `暂无数据` |
  | `WITH "QUOTE"` | `暂无数据` |

- Reloaded the page. The search field returned to its blank state, the unfiltered table loaded, and the visible total remained `共 20 条`. This matches the recorded baseline of eight original words plus twelve demo words in [the UI acceptance record](ui-acceptance-2026-09-28/02-monitor-dict-sensitive.md) and [the acceptance matrix](../ui-acceptance-2026-09-28.md).
- No row matched the three authorized cleanup markers, so no delete action was available or used. No sensitive-word data was changed.

## File fixtures and boundaries at the initial check

- At the time of the initial sensitive-word check, no quota fixture had been moved. The subsequent file-page follow-up and final disposition are recorded below.
- Did not access the 8022 files tab, the protected Edge tab `1021204276`, or port `8010`. Did not enter credentials or use direct API/database access, scripts, DOM injection, or source mutations.

## Quota fixture follow-up and final disposition — 2026-09-29

- Used a fresh isolated Edge tab, `1021204503`, at `http://127.0.0.1:8020/dashboard/files`. The page title was `我的文件` and the signed-in header showed `管理员`.
- Before the stop instruction arrived, each exact filename was searched in the visible file-name field. Each search returned one row in this administrator's personal file list:

  | Exact file | Visible row size / time | Existing evidence ID and exact size |
  |---|---|---|
  | `lumira-quota-chunk-boundary-20260929.txt` | `0.13 MB`; 2026-09-28 12:25 | `d956b884-ddd4-43c7-8c7d-fc4439738553`; 131,072 bytes |
  | `lumira-quota-ui-20260928-181851.txt` | `<0.01 MB`; 2026-09-28 11:34 | `c85825d9-9901-4e79-8938-f85e54deb766`; 126 bytes |

- Before the stop instruction arrived, the visible confirmation named only `lumira-quota-chunk-boundary-20260929.txt` and said it could be restored during the retention period. It was moved to Trash. The next browser observation showed it absent from its exact active-list search and present in Trash at `0.13 MB`; the Trash total was five, including the four protected preview files.
- On receiving the instruction to preserve all seven files, restored that exact chunk-boundary row using the visible `恢复` action in Trash. The Trash total returned to four and the chunk-boundary row disappeared from Trash. No permanent deletion or Trash clearing occurred. The `lumira-quota-ui-20260928-181851.txt` row was not moved.
- Reset the active-list filter and reloaded `/dashboard/files`. After reload, the active table showed `preview-long.txt` (`0.12 MB`), `lumira-quota-chunk-boundary-20260929.txt` (`0.13 MB`), and `lumira-quota-ui-20260928-181851.txt` (`<0.01 MB`), with `共 3 条`. The quota panel showed `263.91 KiB / 1 GiB` and `7 / 1,000`; the page states Trash files count toward quota. Combined with the four preview rows still shown in Trash immediately after restoration, the visible state accounts for all seven protected files.
- Final disposition: both quota fixtures remain active; `preview-long.txt` remains active; the other four protected preview files remain in Trash. The three CSV parser test words remain absent, not deleted in this run; the sensitive-word baseline remains at 20. No role, organization, feedback, or locale cleanup was attempted.
