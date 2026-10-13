type HasOperation = (actionId: string) => boolean;

export function paymentOrderPermissions(hasOperation: HasOperation) {
  return {
    canRead: hasOperation("read"),
    canUpdateSettings: false,
    canCreateOrders: hasOperation("create"),
    canManageOrders: hasOperation("manage"),
    canRefund: hasOperation("refund"),
  };
}

export function paymentSettingsPermissions(hasOperation: HasOperation) {
  return {
    canRead: hasOperation("read"),
    canUpdateSettings: hasOperation("update"),
    canCreateOrders: false,
    canManageOrders: false,
    canRefund: false,
  };
}
