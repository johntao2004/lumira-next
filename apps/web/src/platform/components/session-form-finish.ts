/** Form libraries can finish validation after unmount; never submit a retired form. */
export function guardFormFinish<T>(
  signal: () => AbortSignal,
  onFinish: (values: T) => Promise<boolean | void> | boolean | void,
): (values: T) => Promise<boolean | void> {
  return async values => {
    if (signal().aborted) return false;
    return onFinish(values);
  };
}
