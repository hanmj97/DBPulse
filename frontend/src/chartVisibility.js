/**
 * Build only non-zero-valued chart line segments.
 * A 0 -> 0 interval remains an uncolored neutral grid baseline instead
 * of being overpainted by multiple differently colored SQL metrics.
 * Transitions to/from 0 remain visible, with the true measured endpoint.
 *
 * Pure helper shared by DMV-count and instance-process CPU(%) plots.
 * Invalid/missing values and skipped collection gaps are never connected.
 */
export function nonZeroSegments(samples, readValue, readTime, maxGapMs) {
  const segments = [];
  for (let i = 1; i < samples.length; i += 1) {
    const rawPrevious = readValue(samples[i - 1]);
    const rawCurrent = readValue(samples[i]);
    // An omitted sample is unknown, not a measured zero.
    if (rawPrevious == null || rawCurrent == null) continue;
    const previous = Number(rawPrevious);
    const current = Number(rawCurrent);
    if (!Number.isFinite(previous) || !Number.isFinite(current) ||
        previous < 0 || current < 0 || (previous === 0 && current === 0)) continue;
    if (readTime && Number.isFinite(maxGapMs)) {
      const earlier = Number(readTime(samples[i - 1]));
      const later = Number(readTime(samples[i]));
      if (!Number.isFinite(earlier) || !Number.isFinite(later) ||
          later <= earlier || later - earlier > maxGapMs) continue;
    }
    segments.push([i - 1, i]);
  }
  return segments;
}
