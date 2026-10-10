/** Invalidates async authentication continuations when a session boundary occurs. */
export function createSessionActionEpoch() {
  let revision = 0;
  return {
    capture() {
      return revision;
    },
    renew() {
      revision += 1;
      return revision;
    },
    isCurrent(operationRevision) {
      return operationRevision === revision;
    },
  };
}
