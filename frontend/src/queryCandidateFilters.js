/**
 * Exact Query Store ID filtering, within candidates already collected by DBPulse.
 * Preserves backend order (recent, duration or priority); never creates fake data.
 */
export function filterQueryCandidates(candidates, { priority = 'ALL', queryId = '', planId = '' } = {}) {
  const q = String(queryId).trim();
  const p = String(planId).trim();
  return (candidates || []).filter(item =>
    (priority === 'ALL' || item.priority === priority) &&
    (q === '' || String(item.queryId) === q) &&
    (p === '' || String(item.planId) === p)
  );
}

/** Only suppresses known DBPulse query-text fingerprints, never every SELECT. */
export function filterQuerySources(rows, source = 'exclude-dbpulse') {
  return (rows || []).filter(row => {
    if (source === 'all') return true;
    const matched = row.sourceCategory === 'DBPULSE_PATTERN';
    return source === 'dbpulse-only' ? matched : !matched;
  });
}
