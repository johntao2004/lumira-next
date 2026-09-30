import { test } from 'node:test';
import assert from 'node:assert/strict';
import { linuxGroupActivity } from '../scripts/process-group.mjs';

const stat = (pid, state, group) => `${pid} (name with ) spaces) ${state} 1 ${group} 0 0 0 0`;

test('exited zombie group members do not block shutdown', () => {
  assert.equal(linuxGroupActivity([stat(41, 'Z', 41), stat(42, 'Z', 41), stat(55, 'S', 55)], 41), false);
});

test('a zombie leader cannot hide a live child in the same group', () => {
  assert.equal(linuxGroupActivity([stat(41, 'Z', 41), stat(42, 'S', 41)], 41), true);
  assert.equal(linuxGroupActivity([stat(55, 'S', 55)], 41), null);
});
