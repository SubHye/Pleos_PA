// The sweep tool against a fake bridge and a fake Console. Everything here is mock evidence.
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import type { AddressInfo } from 'node:net';
import { test } from 'node:test';
import { loadCatalog } from '../src/contract.ts';
import type { ConsoleRow } from '../test-support/vehicle-sweep.ts';
import { SweepStopped, sweep } from '../test-support/vehicle-sweep.ts';
import { json, startBridge } from '../test-support/fake-bridge.ts';

const catalog = loadCatalog();
const SOURCE = 'simulation';
const BOUNDS: Record<string, number> = { DOOR_POSITION: 1, WINDOW_POSITION: 100 };

function itemOf(id: string, area: string | null, status: string, rawValue: unknown = null) {
  const entry = catalog.properties.find((p) => p.id === id)!;
  const readable = status === 'OK' || status === 'READ_FAILED';
  const reported = entry.areaType ? area : null;
  return {
    property: id,
    area,
    status,
    capability: readable
      ? {
          access: 'READ',
          matchingAreas: reported ? 1 : null,
          reportedAreas: reported,
          minimum: BOUNDS[id] ? 0 : null,
          maximum: BOUNDS[id] ?? null,
        }
      : null,
    rawValue,
    meaning: id === 'DOOR_POSITION' ? (rawValue === 1 ? 'open' : 'closed') : id === 'DOOR_LOCK' ? 'locked' : null,
    unit: status === 'OK' ? entry.unit : null,
    receivedAt: status === 'OK' ? '2026-11-03T01:02:03.456Z' : null,
    errorType: status === 'PROPERTY_UNSUPPORTED' ? 'Exception' : null,
    errorCode: status === 'PROPERTY_UNSUPPORTED' ? 'ERROR_NOT_SUPPORTED_PROPERTY' : null,
  };
}

const sampleValue = (valueType: string) => {
  if (valueType === 'boolean') return true;
  if (valueType === 'int' || valueType === 'float') return 1;
  if (valueType === 'smart_glass_config') return { zoneCount: 1, levelCount: 1 };
  return catalog.enums[valueType.replace('enum:', '')][0];
};

/** One OK item per property and fixed item, so any tool's answer is valid. */
function okItems(query: string) {
  const tool = catalog.tools.find((entry) => entry.query === query)!;
  const fixed = new Set(tool.fixedItems.map((item) => item.property));
  const items = tool.fixedItems.map((item) =>
    itemOf(item.property, item.area, 'OK', item.property === 'DOOR_POSITION' ? 0 : 50),
  );
  for (const property of catalog.properties.filter((p) => p.query === query && !fixed.has(p.id))) {
    const area = property.areaType ? catalog.areas[property.areaType][0] : null;
    items.push(itemOf(property.id, area, 'OK', sampleValue(property.valueType)));
  }
  return items;
}

const reply = (body: Record<string, any>, items: unknown[], outcome = 'COMPLETE', source = SOURCE) => ({
  requestId: body.requestId,
  query: body.query,
  outcome,
  source,
  sdkVersion: catalog.sdk.version,
  items,
});

function startConsole(options: { apply?: boolean; clients?: number } = {}) {
  const state: any = {
    browser: { clients: options.clients ?? 1 },
    availability: { available: true },
    body: { doors: { front_left: false } },
  };
  const puts: string[] = [];
  const server = createServer((req, res) => {
    const url = new URL(req.url!, 'http://localhost');
    if (req.method === 'PUT') {
      puts.push(url.search);
      if (options.apply !== false) state.body.doors.front_left = url.searchParams.get('door_fl') === 'true';
    }
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(state));
  });
  return new Promise<{ url: string; puts: string[]; state: any; close: () => Promise<void> }>((resolve) => {
    server.listen(0, '127.0.0.1', () => {
      const { port } = server.address() as AddressInfo;
      resolve({
        url: `http://127.0.0.1:${port}`,
        puts,
        state,
        close: () => new Promise((done) => server.close(() => done())),
      });
    });
  });
}

const doorRow: ConsoleRow = {
  property: 'DOOR_POSITION',
  area: 'ROW_1_LEFT',
  endpoint: '/vehicle/body',
  field: 'door_fl',
  statePath: 'body.doors.front_left',
  values: ['true', 'false'],
  expected: [1, 0],
};

const options = (port: number, extra = {}) => ({ port, repeat: 2, expectedSource: SOURCE, ...extra });

test('every tool is called the given number of times, one request at a time', async () => {
  let active = 0;
  let peak = 0;
  const fake = await startBridge((body, res) => {
    active += 1;
    peak = Math.max(peak, active);
    setImmediate(() => {
      active -= 1;
      json(res, 200, reply(body, okItems(body.query)));
    });
  });
  try {
    const report = await sweep(catalog, options(fake.port));
    assert.equal(report.tools.length, catalog.tools.length);
    assert.equal(fake.seen.length, catalog.tools.length * 2);
    assert.equal(peak, 1);
    assert.ok(report.tools.every((tool) => tool.r19.passed));
    assert.ok(Object.values(report.verification).every((value) => value === 'executed'));
  } finally {
    await fake.close();
  }
});

test('metrics count statuses, unsupported items and times, without changing the verdict rule', async () => {
  const fake = await startBridge((body, res) => {
    if (body.query !== 'energy_level') return json(res, 200, reply(body, okItems(body.query)));
    const items = [itemOf('FUEL_PERCENT', null, 'PROPERTY_UNSUPPORTED'), itemOf('EV_BATTERY_PERCENT', null, 'OK', 80)];
    json(res, 200, reply(body, items, 'PARTIAL'));
  });
  try {
    const report = await sweep(catalog, options(fake.port));
    const energy = report.tools.find((tool) => tool.query === 'energy_level')!;
    assert.equal(energy.okRatio, 0.5);
    assert.deepEqual(energy.statuses, { PROPERTY_UNSUPPORTED: 1, OK: 1 });
    assert.deepEqual(energy.unsupported, ['FUEL_PERCENT:PROPERTY_UNSUPPORTED']);
    assert.ok(energy.timeMs.min <= energy.timeMs.median && energy.timeMs.median <= energy.timeMs.max);
    assert.equal(energy.r19.passed, true);
  } finally {
    await fake.close();
  }
});

test('one timeout among the calls fails the tool, is counted, and is not retried', async () => {
  let calls = 0;
  const fake = await startBridge((body, res) => {
    if (body.query !== 'side_mirror_status') return json(res, 200, reply(body, okItems(body.query)));
    calls += 1;
    json(res, 200, calls === 1 ? reply(body, okItems(body.query), 'TIMEOUT') : reply(body, okItems(body.query)));
  });
  try {
    const report = await sweep(catalog, options(fake.port));
    const mirror = report.tools.find((tool) => tool.query === 'side_mirror_status')!;
    assert.equal(mirror.timeouts, 1);
    assert.equal(calls, 2);
    assert.equal(mirror.r19.noTimeout, false);
    assert.equal(mirror.r19.passed, false);
  } finally {
    await fake.close();
  }
});

test('a tool with no OK item fails, and a property never classified stays none', async () => {
  const fake = await startBridge((body, res) => {
    if (body.query !== 'toll_status') return json(res, 200, reply(body, okItems(body.query)));
    const id = 'ETCS_VOICE_GUIDANCE_VOLUME';
    json(res, 200, reply(body, [itemOf(id, null, 'NOT_READ')], 'TIMEOUT'));
  });
  try {
    const report = await sweep(catalog, options(fake.port));
    const toll = report.tools.find((tool) => tool.query === 'toll_status')!;
    assert.equal(toll.r19.hasOk, false);
    assert.equal(toll.r19.allClassified, false);
    assert.equal(report.verification.ETCS_VOICE_GUIDANCE_VOLUME, 'none');
    assert.ok('ETCS_VOICE_GUIDANCE_VOLUME' in toll.alone);
  } finally {
    await fake.close();
  }
});

test('a read the SDK did not answer stays none and fails the tool', async () => {
  const unanswered = ['PERMISSION_DENIED', 'SERVICE_UNAVAILABLE', 'CAPABILITY_FAILED', 'NOT_READ'];
  const fake = await startBridge((body, res) => {
    if (body.query !== 'toll_status') return json(res, 200, reply(body, okItems(body.query)));
    const status = unanswered[(fake.seen.length - 1) % unanswered.length];
    json(res, 200, reply(body, [itemOf('ETCS_VOICE_GUIDANCE_VOLUME', null, status)], 'FAILED'));
  });
  try {
    const report = await sweep(catalog, options(fake.port));
    const toll = report.tools.find((tool) => tool.query === 'toll_status')!;
    assert.equal(report.verification.ETCS_VOICE_GUIDANCE_VOLUME, 'none');
    assert.equal(toll.r19.passed, false);
  } finally {
    await fake.close();
  }
});

test('a property with no capability API stays none without failing its tool', async () => {
  const fake = await startBridge((body, res) => {
    if (body.query !== 'window_detail_status') return json(res, 200, reply(body, okItems(body.query)));
    const items = okItems(body.query).map((item) =>
      item.property === 'SMART_GLASS_CONFIG'
        ? { ...itemOf(item.property, item.area, 'CAPABILITY_FAILED'), errorType: 'UnsupportedOperationException' }
        : item,
    );
    json(res, 200, reply(body, items, 'PARTIAL'));
  });
  try {
    const report = await sweep(catalog, options(fake.port));
    assert.equal(report.verification.SMART_GLASS_CONFIG, 'none');
    assert.equal(report.tools.find((tool) => tool.query === 'window_detail_status')!.r19.passed, true);
  } finally {
    await fake.close();
  }
});

test('one area the SDK did not answer keeps the whole property at none', async () => {
  const fake = await startBridge((body, res) => {
    if (body.query !== 'window_status') return json(res, 200, reply(body, okItems(body.query)));
    const items = [
      itemOf('WINDOW_POSITION', 'ROW_1_LEFT', 'OK', 5),
      itemOf('WINDOW_POSITION', 'ROW_1_RIGHT', 'NOT_READ'),
    ];
    json(res, 200, reply(body, items, 'PARTIAL'));
  });
  try {
    const report = await sweep(catalog, options(fake.port));
    assert.equal(report.verification.WINDOW_POSITION, 'none');
  } finally {
    await fake.close();
  }
});

test('any source but the expected one stops the sweep before touching the console', async () => {
  const fake = await startBridge((body, res) =>
    json(res, 200, reply(body, okItems(body.query), 'COMPLETE', 'simulation')),
  );
  const fakeConsole = await startConsole();
  try {
    await assert.rejects(
      sweep(catalog, { port: fake.port, repeat: 1, consoleUrl: fakeConsole.url, consoleMap: [doorRow] }),
      SweepStopped,
    );
    assert.deepEqual(fakeConsole.puts, []);
  } finally {
    await fake.close();
    await fakeConsole.close();
  }
});

test('a read that fails during a comparison stops the sweep and restores the console', async () => {
  const fakeConsole = await startConsole();
  let compared = false;
  const fake = await startBridge((body, res) => {
    if (compared && body.query === 'door_status') return json(res, 503, { requestId: body.requestId, code: 'BUSY' });
    json(res, 200, reply(body, okItems(body.query)));
  });
  try {
    const log = (line: string) => {
      if (line.startsWith('get_wiper_status')) compared = true;
    };
    await assert.rejects(
      sweep(catalog, { ...options(fake.port), repeat: 1, consoleUrl: fakeConsole.url, consoleMap: [doorRow], log }),
      SweepStopped,
    );
    assert.match(fakeConsole.puts.at(-1)!, /door_fl=false/);
  } finally {
    await fake.close();
    await fakeConsole.close();
  }
});

/** A bridge whose door value follows the fake console, or stays closed when `follows` is false. */
const doorBridge = (fakeConsole: { state: any }, follows = true) =>
  startBridge((body, res) => {
    if (body.query !== 'door_status') return json(res, 200, reply(body, okItems(body.query)));
    const open = follows && fakeConsole.state.body.doors.front_left;
    const items = okItems(body.query).map((item) =>
      item.property === 'DOOR_POSITION' && item.area === 'ROW_1_LEFT'
        ? itemOf('DOOR_POSITION', 'ROW_1_LEFT', 'OK', open ? 1 : 0)
        : item,
    );
    json(res, 200, reply(body, items));
  });

test('a console change the SDK follows is matched, and the baseline is restored', async () => {
  const fakeConsole = await startConsole();
  const fake = await doorBridge(fakeConsole);
  try {
    const report = await sweep(catalog, options(fake.port, { consoleUrl: fakeConsole.url, consoleMap: [doorRow] }));
    assert.equal(report.verification.DOOR_POSITION, 'console_matched');
    assert.equal(report.tools.find((tool) => tool.query === 'door_status')!.console.matched, 1);
    assert.equal(fakeConsole.puts.at(-1), '?door_fl=false');
    assert.equal(fakeConsole.state.body.doors.front_left, false);
  } finally {
    await fake.close();
    await fakeConsole.close();
  }
});

test('a console change the SDK does not follow is a mismatch', async () => {
  const fakeConsole = await startConsole();
  const fake = await doorBridge(fakeConsole, false);
  try {
    const report = await sweep(catalog, options(fake.port, { consoleUrl: fakeConsole.url, consoleMap: [doorRow] }));
    assert.equal(report.verification.DOOR_POSITION, 'console_mismatch');
  } finally {
    await fake.close();
    await fakeConsole.close();
  }
});

test('a console value that does not change stops the sweep, restoring first', async () => {
  const fakeConsole = await startConsole({ apply: false });
  const fake = await doorBridge(fakeConsole);
  try {
    await assert.rejects(
      sweep(catalog, options(fake.port, { consoleUrl: fakeConsole.url, consoleMap: [doorRow] })),
      /did not change/,
    );
    assert.equal(fakeConsole.puts.at(-1), '?door_fl=false');
  } finally {
    await fake.close();
    await fakeConsole.close();
  }
});

test('a console page that is not open stops the sweep before any write', async () => {
  const fakeConsole = await startConsole({ clients: 0 });
  const fake = await doorBridge(fakeConsole);
  try {
    await assert.rejects(
      sweep(catalog, options(fake.port, { consoleUrl: fakeConsole.url, consoleMap: [doorRow] })),
      /not open/,
    );
    assert.deepEqual(fakeConsole.puts, []);
  } finally {
    await fake.close();
    await fakeConsole.close();
  }
});
