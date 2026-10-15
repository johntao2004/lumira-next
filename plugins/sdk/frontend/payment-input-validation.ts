export function validPaymentText(value: string, maximumBytes: number): boolean {
  return value.trim().length > 0
    && !value.includes("\0")
    && new TextEncoder().encode(value).length <= maximumBytes;
}
