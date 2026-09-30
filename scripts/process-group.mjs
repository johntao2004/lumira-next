import { readFileSync, readdirSync } from 'node:fs';

// A zombie has exited; only its parent can reap the process table entry.
// Include every group member so a dead leader cannot hide live descendants.
export function linuxGroupActivity(stats, groupId) {
  let found = false;
  for (const stat of stats) {
    const fields = stat.slice(stat.lastIndexOf(')') + 2).trim().split(/\s+/);
    if (Number(fields[2]) !== groupId) continue;
    found = true;
    if (fields[0] !== 'Z' && fields[0] !== 'X') return true;
  }
  return found ? false : null;
}

function signalable(groupId) {
  try { process.kill(-groupId, 0); return true; }
  catch (error) {
    if (error.code === 'ESRCH') return false;
    if (error.code === 'EPERM') return true;
    throw error;
  }
}

export function processGroupRunning(groupId) {
  if (!signalable(groupId)) return false;
  if (process.platform !== 'linux') return true;
  const stats = [];
  try {
    for (const pid of readdirSync('/proc').filter(name => /^\d+$/.test(name))) {
      try { stats.push(readFileSync(`/proc/${pid}/stat`, 'utf8')); }
      catch (error) {
        if (error.code !== 'ENOENT' && error.code !== 'ESRCH') return true;
      }
    }
  } catch { return true; }
  return linuxGroupActivity(stats, groupId) ?? signalable(groupId);
}
