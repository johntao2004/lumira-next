export type MenuState = { activeGroup: string | null; openGroups: string[] };

/** Route changes may reveal a new group, but never close the user's other groups. */
export function syncMenuState(state: MenuState, availableGroups: readonly string[], activeGroup: string | null): MenuState {
  const active = activeGroup && availableGroups.includes(activeGroup) ? activeGroup : null;
  const openGroups = state.openGroups.filter(group => availableGroups.includes(group));
  if (active && active !== state.activeGroup && !openGroups.includes(active)) openGroups.push(active);
  if (active === state.activeGroup && openGroups.length === state.openGroups.length && openGroups.every((group, index) => group === state.openGroups[index])) return state;
  return { activeGroup: active, openGroups };
}
