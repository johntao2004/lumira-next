# Organization manual sorting contract

Organization records have a persisted nonnegative 32-bit `sortNo`. Lower values
appear first. The list endpoint orders records by `sortNo`, then `code`, then
`id`, including root and direct-child hierarchy filters. Equal sort numbers
therefore keep a stable, predictable order.

`POST /api/platform/organizations` accepts an optional integer `sortNo`; when it
is omitted or null, the new organization uses `0`. `PUT
/api/platform/organizations/{id}` accepts the same field; when omitted or null,
the existing value is retained for compatibility with clients that predate
manual sorting. Values below `0`, fractional JSON numbers, and values above
`2147483647` are rejected with HTTP 400. Organization responses include
`sortNo`.

Updates still require the current `version`. The version comparison and write
run in one database statement, so concurrent edits cannot overwrite a newer
sort value. Stale writes return HTTP 409 with `staleRecord`.

The host initialization baseline `B52__platform_initialization.sql` defines
`sort_no` with a default of `0` and a nonnegative check. New organizations
without an explicit value therefore start at `0` and retain the documented
stable tie-breaking order.
