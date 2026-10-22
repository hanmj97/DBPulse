// Phase20: Query Store CPU recorded for ONE selected database.
// The resulting rate is ONE-logical-core equivalent, not the real % of SQL
// Server instance CPU or precise real-time per-DB CPU utilization.
// Query Store runtime statistics are asynchronously aggregated in intervals,
// may fall outside the 15m rolling lookup and omit executions by policy.
export function appendSelectedDbCpuSample(previous, status, targetId) {
  const rows = Array.isArray(previous) ? previous : [];
  if (!targetId || !['ONLINE','QUERY_STORE_READ_ONLY'].includes(status?.status) ||
      status?.databaseCpuMs == null || !status?.lastCollectedAt) return rows;
  const at = Date.parse(status.lastCollectedAt);
  const cpuMs = Number(status.databaseCpuMs);
  if (!Number.isFinite(at) || !Number.isFinite(cpuMs) || cpuMs < 0) return rows;
  const sameTarget = rows.filter(row => row.targetId === targetId);
  const last = sameTarget.at(-1);
  if (last && at <= last.at) return sameTarget;
  // Avoid fabricating zero for the first snapshot, a Query Store flush gap,
  // or when a 15m-old interval rolls off and the cumulative sum decreases.
  const elapsedMs = last ? at-last.at : 0;
  const deltaMs = last ? cpuMs-last.cpuMs : NaN;
  const corePercent = elapsedMs >= 20000 && elapsedMs <= 300000 &&
      deltaMs >= 0 && deltaMs <= elapsedMs * 128
    ? deltaMs / elapsedMs * 100 : null;
  const next = {targetId, at, cpuMs, corePercent};
  return [...sameTarget, next].slice(-30);
}
