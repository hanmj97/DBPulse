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
