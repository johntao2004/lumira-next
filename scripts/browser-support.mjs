/** Start observing before a UI action. Callers must await the returned promise.
 * Handle an early timeout while the click is still pending so fixture cleanup
 * runs through the caller's finally block instead of terminating Node.
 */
export function observeResponse(page, predicate) {
  const response = page.waitForResponse(predicate);
  void response.catch(() => {});
  return response;
}
