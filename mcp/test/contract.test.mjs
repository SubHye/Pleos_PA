import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { loadCatalog, parseRequest, validateResponse } from '../src/contract.mjs';

const fixture = (name) => JSON.parse(readFileSync(new URL(`../../contracts/vehicle-read/fixtures/${name}.json`, import.meta.url), 'utf8'));
const catalog = loadCatalog();

test('requests match their fixtures', () => {
  for (const { name, body, accept } of fixture('requests')) {
    assert.equal(parseRequest(catalog, JSON.stringify(body)) !== null, accept, name);
  }
});

test('an oversized request is rejected', () => {
  const properties = Array.from({ length: catalog.bridge.maxRequestBytes }, () => 'X');
  const text = JSON.stringify({ requestId: '11111111-1111-4111-8111-111111111111', remainingMs: 9000, query: 'seat_status', properties });
  assert.equal(parseRequest(catalog, text), null);
});

test('responses match their fixtures', () => {
  for (const { name, http, body, requestId, query, selection, expect } of fixture('responses')) {
    const result = validateResponse(catalog, { http, text: JSON.stringify(body), requestId, query, selection });
    if (expect.ok) {
      assert.equal(result.ok, true, name);
      assert.equal(result.response.outcome, expect.outcome, name);
      assert.deepEqual(result.response, body, name);
    } else {
      assert.deepEqual(result, { ok: false, code: expect.code, origin: expect.origin }, name);
    }
  }
});

test('malformed and oversized replies are invalid', () => {
  const invalid = { ok: false, code: 'INVALID_RESPONSE', origin: 'pc' };
  const common = { http: 200, requestId: 'id', query: 'door_status' };
  assert.deepEqual(validateResponse(catalog, { ...common, text: 'not json' }), invalid);
  assert.deepEqual(validateResponse(catalog, { ...common, text: ' '.repeat(catalog.bridge.maxResponseBytes + 1) }), invalid);
});

test('a non-finite value is invalid', () => {
  const item = { property: 'LOW_TIRE_PRESSURE', area: 'LEFT_FRONT', status: 'OK', capability: { access: 'READ', matchingAreas: 1, reportedAreas: 'LEFT_FRONT', minimum: null, maximum: null }, rawValue: 0, meaning: null, unit: 'kPa', receivedAt: '2026-11-02T09:30:00.123Z', errorType: null, errorCode: null };
  const requestId = '22222222-2222-4222-8222-222222222222';
  const body = { requestId, query: 'tire_status', outcome: 'COMPLETE', source: 'simulation', sdkVersion: catalog.sdk.version, items: [item] };
  const text = JSON.stringify(body).replace('"rawValue":0', '"rawValue":1e999');
  assert.deepEqual(validateResponse(catalog, { http: 200, text, requestId, query: 'tire_status' }), { ok: false, code: 'INVALID_RESPONSE', origin: 'pc' });
});

test('fixtures only claim mock or observed evidence', () => {
  const evidence = new Set(['mock', 'source_only', 'sdk_emulator']);
  for (const name of ['requests', 'responses', 'failures', 'tool-inputs', 'tool-results']) {
    for (const entry of fixture(name)) assert.ok(evidence.has(entry.evidence), `${name}: ${entry.name}`);
  }
  for (const entry of [...fixture('requests'), ...fixture('responses'), ...fixture('tool-inputs'), ...fixture('tool-results')]) {
    assert.equal(entry.evidence, 'mock', `wire shape not produced by the SDK yet: ${entry.name}`);
  }
});

test('the catalog keeps the existing tools and offers no excluded read', () => {
  const legacy = catalog.tools.filter((tool) => tool.legacy).map((tool) => tool.name);
  assert.deepEqual(legacy, ['get_door_status', 'get_window_status', 'get_energy_level', 'get_front_left_door_position']);
  const offered = new Set(catalog.properties.map((p) => `${p.api}.${p.method}/${p.arity}`));
  for (const entry of catalog.excluded) assert.ok(!offered.has(`${entry.api}.${entry.method}/${entry.arity}`), entry.method);
  assert.ok(catalog.excluded.some((entry) => entry.method === 'getVINInfo' && entry.reason === 'identifier'));
  assert.ok(catalog.properties.every((p) => p.verification === 'none'));
});
