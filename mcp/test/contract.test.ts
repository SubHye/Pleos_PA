import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { loadCatalog, parseRequest, propertiesOf, propertyOf, validateResponse } from '../src/contract.ts';

const fixture = (name: string) =>
  JSON.parse(readFileSync(new URL(`../../contracts/vehicle-read/fixtures/${name}.json`, import.meta.url), 'utf8'));
const catalog = loadCatalog();

test('requests match their fixtures', () => {
  for (const { name, body, accept } of fixture('requests')) {
    assert.equal(parseRequest(catalog, JSON.stringify(body)) !== null, accept, name);
  }
});

test('an oversized request is rejected', () => {
  const properties = Array.from({ length: catalog.bridge.maxRequestBytes }, () => 'X');
  const text = JSON.stringify({
    requestId: '11111111-1111-4111-8111-111111111111',
    remainingMs: 9000,
    query: 'seat_status',
    properties,
  });
  assert.equal(parseRequest(catalog, text), null);
});

test('responses match their fixtures', () => {
  for (const { name, http, body, requestId, query, selection, expect } of fixture('responses')) {
    const result = validateResponse(catalog, { http, text: JSON.stringify(body), requestId, query, selection });
    if (expect.ok) {
      assert.ok(result.ok, name);
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
  assert.deepEqual(
    validateResponse(catalog, { ...common, text: ' '.repeat(catalog.bridge.maxResponseBytes + 1) }),
    invalid,
  );
});

test('a non-finite value is invalid', () => {
  const item = {
    property: 'LOW_TIRE_PRESSURE',
    area: 'LEFT_FRONT',
    status: 'OK',
    capability: { access: 'READ', matchingAreas: 1, reportedAreas: 'LEFT_FRONT', minimum: null, maximum: null },
    rawValue: 0,
    meaning: null,
    unit: 'kPa',
    receivedAt: '2026-11-02T09:30:00.123Z',
    errorType: null,
    errorCode: null,
  };
  const requestId = '22222222-2222-4222-8222-222222222222';
  const body = {
    requestId,
    query: 'tire_status',
    outcome: 'COMPLETE',
    source: 'simulation',
    sdkVersion: catalog.sdk.version,
    items: [item],
  };
  const text = JSON.stringify(body).replace('"rawValue":0', '"rawValue":1e999');
  assert.deepEqual(validateResponse(catalog, { http: 200, text, requestId, query: 'tire_status' }), {
    ok: false,
    code: 'INVALID_RESPONSE',
    origin: 'pc',
  });
});

test('fixtures only claim mock or observed evidence', () => {
  const evidence = new Set(['mock', 'source_only', 'sdk_emulator']);
  for (const name of ['requests', 'responses', 'failures', 'tool-inputs', 'tool-results']) {
    for (const entry of fixture(name)) assert.ok(evidence.has(entry.evidence), `${name}: ${entry.name}`);
  }
  for (const entry of [
    ...fixture('requests'),
    ...fixture('responses'),
    ...fixture('tool-inputs'),
    ...fixture('tool-results'),
  ]) {
    assert.equal(entry.evidence, 'mock', `wire shape not produced by the SDK yet: ${entry.name}`);
  }
});

test('the catalog keeps the existing tools and offers no excluded read', () => {
  const legacy = catalog.tools.filter((tool) => tool.legacy).map((tool) => tool.name);
  assert.deepEqual(legacy, [
    'get_door_status',
    'get_window_status',
    'get_energy_level',
    'get_front_left_door_position',
  ]);
  const offered = new Set(catalog.properties.map((p) => `${p.api}.${p.method}/${p.arity}`));
  for (const entry of catalog.excluded)
    assert.ok(!offered.has(`${entry.api}.${entry.method}/${entry.arity}`), entry.method);
  assert.ok(catalog.excluded.some((entry) => entry.method === 'getVINInfo' && entry.reason === 'identifier'));
});

test('verification and adoption records are consistent', () => {
  const verification = new Set(['none', 'executed', 'console_matched', 'console_mismatch']);
  for (const property of catalog.properties) assert.ok(verification.has(property.verification), property.id);
  if (catalog.properties.some((property) => property.verification !== 'none')) {
    assert.ok(catalog.verifiedOn?.includes(`Vehicle SDK ${catalog.sdk.version}`));
  }
  for (const tool of catalog.tools) {
    assert.ok(['undecided', 'adopted', 'rejected'].includes(tool.adoption), tool.name);
    if (tool.adoption === 'rejected') {
      assert.ok(tool.adoptionNote, tool.name);
      assert.ok(!tool.legacy, tool.name);
    }
    if (tool.adoption !== 'adopted') continue;
    const properties = [...propertiesOf(catalog, tool)].map((id) => propertyOf(catalog, id)!);
    assert.ok(
      properties.every((property) => property.verification !== 'none' || property.noCapabilityApi),
      tool.name,
    );
    for (const property of properties.filter((entry) => entry.verification === 'console_mismatch')) {
      assert.ok(tool.adoptionNote?.includes(property.id), `${tool.name} ${property.id}`);
    }
  }
});

test('a Console verdict is recorded only for a property the Console map compares', () => {
  const rows = JSON.parse(readFileSync(new URL('../test-support/vehicle-console-map.json', import.meta.url), 'utf8'));
  const compared = new Set(rows.map((row: { property: string }) => row.property));
  const recorded = catalog.properties
    .filter((property) => property.verification === 'console_matched' || property.verification === 'console_mismatch')
    .map((property) => property.id);
  assert.deepEqual(recorded.sort(), [...compared].sort());
});

test('adoption changes only with a visible edit here, after a sweep report', () => {
  const named = (adoption: string) =>
    catalog.tools.filter((tool) => tool.adoption === adoption).map((tool) => tool.name);
  assert.deepEqual(named('rejected').sort(), ['get_fuel_economy', 'get_toll_status']);
  assert.deepEqual(named('undecided'), []);
  assert.equal(named('adopted').length, 20);
});

test('only a property the SDK gives no capability API for is exempt, and it stays none', () => {
  const exempt = catalog.properties.filter((property) => property.noCapabilityApi);
  assert.deepEqual(
    exempt.map((property) => [property.id, property.verification]),
    [['SMART_GLASS_CONFIG', 'none']],
  );
});
