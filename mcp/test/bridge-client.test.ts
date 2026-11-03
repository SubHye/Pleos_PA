// Logic tests without an MCP transport. The fake bridge makes every result here mock evidence.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { readVehicle } from '../src/bridge-client.ts';
import { loadCatalog } from '../src/contract.ts';
import { createServer, runTool } from '../src/server.ts';
import { energyReply, json, startBridge, waitFor } from '../test-support/fake-bridge.ts';

const catalog = loadCatalog();
const fixture = (name: string) =>
  JSON.parse(readFileSync(new URL(`../../contracts/vehicle-read/fixtures/${name}.json`, import.meta.url), 'utf8'));
const energyTool = catalog.tools.find((tool) => tool.query === 'energy_level')!;
const pcFailure = (code: string) => ({ ok: false, code, origin: 'pc' });
const androidFailure = (code: string) => ({ ok: false, code, origin: 'android' });

test('a read sends one POST to the catalog path and returns the validated reply', async () => {
  const fake = await startBridge((body, res) => json(res, 200, energyReply(body.requestId)));
  try {
    const { requestId, result } = await readVehicle(catalog, 'energy_level', { port: fake.port });
    assert.ok(result.ok);
    assert.equal(result.response.requestId, requestId);
    assert.equal(fake.seen.length, 1);
    const [seen] = fake.seen;
    assert.equal(seen.method, 'POST');
    assert.equal(seen.url, catalog.bridge.path);
    assert.equal(seen.authorization, undefined);
    assert.deepEqual(Object.keys(seen.body).sort(), ['query', 'remainingMs', 'requestId']);
    assert.equal(seen.body.requestId, requestId);
    assert.ok(seen.body.remainingMs >= 1 && seen.body.remainingMs <= catalog.bridge.deadlineMs);
  } finally {
    await fake.close();
  }
});

test('selectors reach the bridge and also bound the reply', async () => {
  const outside = fixture('responses').find(
    (entry: { name: string }) => entry.name === 'property outside the selection',
  );
  const fake = await startBridge((request, res) => json(res, 200, { ...outside.body, requestId: request.requestId }));
  try {
    const { query, selection } = outside;
    const bounded = await readVehicle(catalog, query, { port: fake.port, selection });
    assert.deepEqual(fake.seen[0].body.properties, selection.properties);
    assert.deepEqual(bounded.result, pcFailure('INVALID_RESPONSE'));

    const unbounded = await readVehicle(catalog, query, { port: fake.port });
    assert.equal(unbounded.result.ok, true, 'the same reply is valid without the selection');
  } finally {
    await fake.close();
  }
});

test('bridge replies map to the contract failures', async () => {
  const cases: [number, (requestId: string) => unknown, { ok: boolean; code: string; origin: string }][] = [
    [409, (requestId) => ({ requestId, ok: false, code: 'BUSY' }), androidFailure('BUSY')],
    [503, (requestId) => ({ requestId, ok: false, code: 'APP_NOT_READY' }), androidFailure('APP_NOT_READY')],
    [503, (requestId) => ({ requestId, ok: false, code: 'CONNECTION_STOPPED' }), androidFailure('CONNECTION_STOPPED')],
    [403, () => ({ ok: false, code: 'REQUEST_FORBIDDEN' }), androidFailure('REQUEST_FORBIDDEN')],
    [400, () => ({ ok: false, code: 'INVALID_REQUEST' }), androidFailure('INVALID_REQUEST')],
    [413, () => ({ ok: false, code: 'INVALID_REQUEST' }), androidFailure('INVALID_REQUEST')],
    [415, () => ({ ok: false, code: 'INVALID_REQUEST' }), androidFailure('INVALID_REQUEST')],
    [409, () => ({ requestId: 'other', ok: false, code: 'BUSY' }), pcFailure('INVALID_RESPONSE')],
    [500, () => ({ ok: false, code: 'BUSY' }), pcFailure('INVALID_RESPONSE')],
    [200, () => energyReply('33333333-3333-4333-8333-333333333333'), pcFailure('INVALID_RESPONSE')],
  ];
  for (const [status, body, expected] of cases) {
    const fake = await startBridge((request, res) => json(res, status, body(request.requestId)));
    try {
      const { result } = await readVehicle(catalog, 'energy_level', { port: fake.port });
      assert.deepEqual(result, expected, `${status} ${expected.code}`);
    } finally {
      await fake.close();
    }
  }
});

test('a reply over the size limit or with another content type is not used', async () => {
  const oversized = await startBridge((body, res) =>
    json(res, 200, { ...energyReply(body.requestId), padding: 'x'.repeat(catalog.bridge.maxResponseBytes) }),
  );
  const wrongType = await startBridge((body, res) => json(res, 200, energyReply(body.requestId), 'text/plain'));
  try {
    assert.deepEqual(
      (await readVehicle(catalog, 'energy_level', { port: oversized.port })).result,
      pcFailure('INVALID_RESPONSE'),
    );
    assert.deepEqual(
      (await readVehicle(catalog, 'energy_level', { port: wrongType.port })).result,
      pcFailure('INVALID_RESPONSE'),
    );
  } finally {
    await oversized.close();
    await wrongType.close();
  }
});

test('an unreachable bridge is ANDROID_UNREACHABLE', async () => {
  const fake = await startBridge(() => {});
  const { port } = fake;
  await fake.close();
  const { result } = await readVehicle(catalog, 'energy_level', { port });
  assert.deepEqual(result, pcFailure('ANDROID_UNREACHABLE'));
});

test('a dropped connection is not retried', async () => {
  const fake = await startBridge((_body, res) => res.destroy());
  try {
    const { result } = await readVehicle(catalog, 'energy_level', { port: fake.port });
    assert.deepEqual(result, pcFailure('ANDROID_UNREACHABLE'));
    assert.equal(fake.seen.length, 1);
  } finally {
    await fake.close();
  }
});

/** Every combination of seat areas that isArea accepts, as a pool to build a body of an exact size from. */
function seatAreaPool() {
  const areas = catalog.areas.SeatArea;
  const pool: string[] = [];
  for (let mask = 1; mask < 1 << areas.length; mask += 1) {
    pool.push(areas.filter((_, index) => mask & (1 << index)).join('+'));
  }
  return pool.sort((a, b) => a.length - b.length);
}

/** Selects areas so the JSON body is exactly `target` bytes, given the size of the body without any area. */
function areasForBodyOf(target: number, emptyBodyBytes: number, pool: string[]) {
  const cost = (area: string) => Buffer.byteLength(JSON.stringify(area));
  const chosen: string[] = [];
  let size = emptyBodyBytes;
  const remaining = [...pool];
  while (size < target) {
    const separator = chosen.length === 0 ? 0 : 1;
    const gap = target - size - separator;
    const exact = remaining.findIndex((area) => cost(area) === gap);
    const index = exact >= 0 ? exact : remaining.findIndex((area) => cost(area) * 2 + 1 <= gap);
    assert.ok(index >= 0, 'the pool can fill the body exactly');
    const [area] = remaining.splice(index, 1);
    chosen.push(area);
    size += separator + cost(area);
  }
  assert.equal(size, target);
  return chosen;
}

test('the request limit is checked against the actual body bytes', async () => {
  const fake = await startBridge((body, res) => json(res, 409, { requestId: body.requestId, ok: false, code: 'BUSY' }));
  try {
    // A frozen clock fixes remainingMs at 9000, so the size of everything except `areas` is known.
    const options = { port: fake.port, deadlineMs: 9000, now: () => 0 };
    const empty = JSON.stringify({
      requestId: '11111111-1111-4111-8111-111111111111',
      remainingMs: 9000,
      query: 'seat_status',
      areas: [],
    });
    const limit = catalog.bridge.maxRequestBytes;
    const atLimit = areasForBodyOf(limit, Buffer.byteLength(empty), seatAreaPool());

    const sent = await readVehicle(catalog, 'seat_status', { ...options, selection: { areas: atLimit } });
    assert.deepEqual(sent.result, androidFailure('BUSY'));
    assert.equal(fake.seen.length, 1);
    assert.equal(Buffer.byteLength(fake.seen[0].raw), limit);

    const over = areasForBodyOf(limit + 1, Buffer.byteLength(empty), seatAreaPool());
    const rejected = await readVehicle(catalog, 'seat_status', { ...options, selection: { areas: over } });
    assert.deepEqual(rejected.result, pcFailure('INVALID_REQUEST'));
    assert.equal(fake.seen.length, 1, 'an oversized body is never sent');
  } finally {
    await fake.close();
  }
});

test('the deadline ends the request and closes the connection', async () => {
  const fake = await startBridge(() => {});
  try {
    const { result } = await readVehicle(catalog, 'energy_level', { port: fake.port, deadlineMs: 30 });
    assert.deepEqual(result, pcFailure('TIMEOUT'));
    await waitFor(() => fake.seen[0]?.closed === true);
  } finally {
    await fake.close();
  }
});

test('an exhausted deadline sends nothing', async () => {
  const fake = await startBridge(() => {});
  let clock = 0;
  const now = () => {
    clock += 10_000;
    return clock;
  };
  try {
    const { result } = await readVehicle(catalog, 'energy_level', { port: fake.port, now });
    assert.deepEqual(result, pcFailure('TIMEOUT'));
    assert.equal(fake.seen.length, 0);
  } finally {
    await fake.close();
  }
});

test('a cancellation ends the request, and a cancelled call sends nothing', async () => {
  const fake = await startBridge(() => {});
  try {
    const cancel = new AbortController();
    const pending = readVehicle(catalog, 'energy_level', { port: fake.port, signal: cancel.signal });
    await waitFor(() => fake.seen.length === 1);
    cancel.abort();
    assert.deepEqual((await pending).result, pcFailure('CANCELLED'));
    await waitFor(() => fake.seen[0].closed);

    const { result } = await readVehicle(catalog, 'energy_level', { port: fake.port, signal: cancel.signal });
    assert.deepEqual(result, pcFailure('CANCELLED'));
    assert.equal(fake.seen.length, 1);
  } finally {
    await fake.close();
  }
});

test('tool results fixtures come out of a real bridge exchange unchanged', async () => {
  for (const { name, requestId, query, bridge, expect } of fixture('tool-results')) {
    if (!bridge.ok) continue;
    const swap = (value: unknown, id: string) => JSON.parse(JSON.stringify(value).replaceAll(requestId, id));
    const fake = await startBridge((body, res) => json(res, 200, swap(bridge.response, body.requestId)));
    try {
      const tool = catalog.tools.find((entry) => entry.query === query)!;
      const result = await runTool(catalog, tool, {}, { port: fake.port });
      assert.equal(result.isError === true, expect.isError, name);
      const sent = fake.seen[0].body.requestId;
      assert.deepEqual(result.structuredContent, swap(expect.structuredContent, sent), name);
    } finally {
      await fake.close();
    }
  }
});

test('a node-side failure is an error result with the request id and no value', async () => {
  const fake = await startBridge(() => {});
  const { port } = fake;
  await fake.close();
  const result = await runTool(catalog, energyTool, {}, { port });
  assert.equal(result.isError, true);
  const content = result.structuredContent as Record<string, unknown>;
  assert.equal(content.code, 'ANDROID_UNREACHABLE');
  assert.equal(content.origin, 'pc');
  assert.equal(content.query, 'energy_level');
  assert.equal(typeof content.requestId, 'string');
  assert.equal('items' in content, false);
});

test('presentation counts against the same deadline as the fetch', async () => {
  const fake = await startBridge((body, res) => json(res, 200, energyReply(body.requestId)));
  try {
    let clock = 0;
    const late = await runTool(
      catalog,
      energyTool,
      {},
      {
        port: fake.port,
        now: () => clock,
        render: (value) => {
          clock = catalog.bridge.deadlineMs;
          return value;
        },
      },
    );
    assert.equal(late.isError, true);
    const content = late.structuredContent as Record<string, unknown>;
    assert.equal(content.code, 'TIMEOUT');
    assert.equal(content.origin, 'pc');
    assert.equal('items' in content, false);

    const quick = await runTool(catalog, energyTool, {}, { port: fake.port });
    assert.notEqual(quick.isError, true);
  } finally {
    await fake.close();
  }
});

test('the adopted tool table only names catalog tools', () => {
  assert.doesNotThrow(() => createServer());
  assert.throws(() => createServer({ tools: [{ name: 'get_unknown', title: 't', description: 'd' }] }), /get_unknown/);
});
