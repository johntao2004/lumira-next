export function roleAssignmentCheckboxDisabled(input: {
  pending: boolean;
  committed: boolean;
  canAssignRoles: boolean;
  assignable: boolean;
  selected: boolean;
}) {
  return input.pending || input.committed || !input.canAssignRoles || (!input.assignable && !input.selected);
}
