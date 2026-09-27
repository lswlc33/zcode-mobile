/** One-shot scrubber: strip provider secrets from captured transcripts. */
import { readFileSync, writeFileSync, readdirSync } from 'node:fs';

function scrub(value) {
  if (typeof value === 'string') return value.replace(/\bsk-[A-Za-z0-9_\-]{12,}/g, '<REDACTED-KEY>');
  if (Array.isArray(value)) return value.map(scrub);
  if (value && typeof value === 'object') {
    const out = {};
    for (const [k, v] of Object.entries(value)) {
      out[k] = /^(apiKey|authorization|x-api-key|api_key|secret|password|token)$/i.test(k)
        ? '<REDACTED-KEY>' : scrub(v);
    }
    return out;
  }
  return value;
}

for (const f of readdirSync('.').filter((n) => n.endsWith('.json'))) {
  const before = readFileSync(f, 'utf8');
  const n = (before.match(/\bsk-[A-Za-z0-9_\-]{12,}/g) ?? []).length;
  let after = before;
  try { after = JSON.stringify(scrub(JSON.parse(before)), null, 2); }
  catch { after = before.replace(/\bsk-[A-Za-z0-9_\-]{12,}/g, '<REDACTED-KEY>'); }
  if (after !== before) { writeFileSync(f, after); console.log(`${f}: scrubbed ${n} key(s)`); }
  else console.log(`${f}: clean`);
}
