import test from 'node:test';
import assert from 'node:assert/strict';
import { allowedCommand, validCommandBody } from '../src/protocol.js';

test('page allowlist bounds sizes and rejects ambiguous filters and fragments', () => {
  for (const path of ['/channels/page?source=1', '/channels/page?source=2&cat=local%20news&q=renamed&limit=100&offset=100&revision=42'])
    assert.equal(allowedCommand('GET', path), true, path);
  for (const query of ['source=1&limit=101', 'source=1&limit=0', 'source=1&offset=-1', 'source=1&offset=1000001',
    'source=1&source=2', 'source=1&cat=', 'source=1&q=' + 'x'.repeat(121), 'source=1&revision=bad',
    'source=1&limit=oops', 'source=1&t=secret', 'source=1#fragment', 'cat=only', 'source=0'])
    assert.equal(allowedCommand('GET', '/channels/page?' + query), false, query);
});

test('bulk and state reject oversized duplicate malformed or unauthorized operations before relay', () => {
  const body = { source: 1, revision: '42', action: 'favorite', ids: Array.from({ length: 100 }, (_, i) => i + 1) };
  assert.equal(validCommandBody('/channels/bulk', JSON.stringify(body)), true);
  assert.equal(validCommandBody('/channels/state', JSON.stringify({ ...body, action: undefined })), true);
  for (const invalid of [{ ids: [...body.ids, 101] }, { ids: [] }, { ids: [1, 1] }, { ids: [-1] }, { ids: [1.1] },
    { source: 0 }, { revision: 42 }, { action: 'delete' }, { ids: ['1'] }])
    assert.equal(validCommandBody('/channels/bulk', JSON.stringify({ ...body, ...invalid })), false);
  assert.equal(validCommandBody('/channels/bulk', '{'), false);
});
