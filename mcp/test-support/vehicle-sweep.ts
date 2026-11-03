// Runs every catalog tool against a real Android bridge and reports the facts a tool's adoption is judged on.
// Test-only and not part of the MCP server. One request at a time, no retries. Reports stay out of the repository.
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { readVehicle } from '../src/bridge-client.ts';
import { loadCatalog, propertiesOf } from '../src/contract.ts';
import type { BridgeResponse, BridgeResult, Catalog, Selection, ToolEntry } from '../src/contract.ts';

type Item = BridgeResponse['items'][number];

export interface ConsoleRow {
  property: string;
  area: string | null;
  endpoint: string;
  field: string;
  params?: Record<string, string>;
  statePath: string;
  values: [string, string];
  expected: [unknown, unknown];
}

export interface SweepOptions {
  port?: number;
  consoleUrl?: string;
  consoleMap?: ConsoleRow[];
  repeat?: number;
  expectedSource?: string;
  log?: (line: string) => void;
}

const FINISHED = new Set(['COMPLETE', 'PARTIAL', 'FAILED']);
const UNSUPPORTED = new Set(['PROPERTY_UNSUPPORTED', 'AREA_UNSUPPORTED', 'READ_UNAVAILABLE', 'CAPABILITY_FAILED']);

const median = (values: number[]) => [...values].sort((a, b) => a - b)[Math.floor((values.length - 1) / 2)];
const describe = (result: BridgeResult) => (result.ok ? result.response.outcome : result.code);

export class SweepStopped extends Error {}

async function call(catalog: Catalog, tool: ToolEntry, selection: Selection, options: SweepOptions) {
  const started = performance.now();
  const { result } = await readVehicle(catalog, tool.query, { port: options.port, selection });
  const elapsedMs = Math.round(performance.now() - started);
  const expected = options.expectedSource ?? 'pleos_emulator';
  if (result.ok && result.response.source !== expected) {
    throw new SweepStopped(`source ${result.response.source} is not ${expected}`);
  }
  return { result, elapsedMs };
}

async function sweepTool(catalog: Catalog, tool: ToolEntry, options: SweepOptions) {
  const repeat = options.repeat ?? 5;
  const calls: { outcome: string; elapsedMs: number; bytes: number; items: Item[] }[] = [];
  let lastItems: Item[] = [];
  for (let run = 0; run < repeat; run += 1) {
    const { result, elapsedMs } = await call(catalog, tool, {}, options);
    const items = result.ok ? result.response.items : [];
    if (items.length > 0) lastItems = items;
    const bytes = result.ok ? Buffer.byteLength(JSON.stringify(result.response)) : 0;
    calls.push({ outcome: describe(result), elapsedMs, bytes, items });
    options.log?.(`${tool.name} ${run + 1}/${repeat} ${describe(result)} ${elapsedMs}ms`);
  }

  // A property a whole call never reached may still answer alone, which tells a slow tool from a silent property.
  const properties = [...propertiesOf(catalog, tool)];
  const classified = new Map<string, boolean>(
    properties.map((id) => [
      id,
      calls.every((entry) => entry.items.some((item) => item.property === id && item.status !== 'NOT_READ')),
    ]),
  );
  const alone: Record<string, string> = {};
  if (!tool.legacy) {
    for (const id of properties) {
      if (classified.get(id)) continue;
      const { result } = await call(catalog, tool, { properties: [id] }, options);
      alone[id] = describe(result);
    }
  }

  const statuses: Record<string, number> = {};
  for (const item of lastItems) statuses[item.status] = (statuses[item.status] ?? 0) + 1;
  const okItems = statuses.OK ?? 0;
  const times = calls.map((entry) => entry.elapsedMs);
  const reachedAll = calls.every((entry) => FINISHED.has(entry.outcome));
  const hasOk = calls.every((entry) => entry.items.some((item) => item.status === 'OK'));
  const allClassified = [...classified.values()].every(Boolean);
  return {
    name: tool.name,
    query: tool.query,
    legacy: tool.legacy,
    outcomes: calls.map((entry) => entry.outcome),
    items: lastItems.length,
    statuses,
    okRatio: lastItems.length === 0 ? 0 : Math.round((okItems / lastItems.length) * 1000) / 1000,
    unsupported: lastItems
      .filter((item) => UNSUPPORTED.has(item.status))
      .map((item) => `${item.property}${item.area ? `/${item.area}` : ''}:${item.status}`),
    timeMs: { min: Math.min(...times), median: median(times), max: Math.max(...times) },
    timeouts: calls.filter((entry) => entry.outcome === 'TIMEOUT').length,
    responseBytes: Math.max(...calls.map((entry) => entry.bytes)),
    classified: Object.fromEntries(classified),
    alone,
    finalItems: lastItems,
    r19: { allClassified, hasOk, noTimeout: reachedAll, passed: allClassified && hasOk && reachedAll },
  };
}

const lookup = (state: unknown, path: string) =>
  path.split('.').reduce<any>((value, key) => (value === null || value === undefined ? value : value[key]), state);

async function consoleJson(url: string, init?: RequestInit) {
  const response = await fetch(url, { ...init, signal: AbortSignal.timeout(5_000) });
  const body = await response.json().catch(() => null);
  if (!response.ok) throw new SweepStopped(`console ${init?.method ?? 'GET'} refused: ${response.status}`);
  return body;
}

async function matchRow(catalog: Catalog, row: ConsoleRow, consoleUrl: string, options: SweepOptions) {
  const tool = catalog.tools.find(
    (entry) => entry.query === catalog.properties.find((p) => p.id === row.property)?.query,
  );
  if (!tool) throw new SweepStopped(`console map names unknown property ${row.property}`);
  const put = (value: string) =>
    consoleJson(`${consoleUrl}${row.endpoint}?${new URLSearchParams({ ...row.params, [row.field]: value })}`, {
      method: 'PUT',
    });
  const baseline = lookup(await consoleJson(`${consoleUrl}/vehicle/state`), row.statePath);
  let matched = true;
  try {
    for (const index of [0, 1]) {
      await put(row.values[index]);
      const consoleValue = lookup(await consoleJson(`${consoleUrl}/vehicle/state`), row.statePath);
      if (String(consoleValue) !== row.values[index]) {
        throw new SweepStopped(`console value did not change for ${row.property} (${row.field})`);
      }
      const { result } = await call(catalog, tool, {}, options);
      const items = result.ok ? result.response.items : [];
      const item = items.find(
        (entry) =>
          entry.property === row.property &&
          (row.area === null || entry.area === row.area || entry.area?.split('+').includes(row.area)),
      );
      if (item?.status !== 'OK' || item.rawValue !== row.expected[index]) matched = false;
    }
  } finally {
    await put(String(baseline));
  }
  return matched;
}

/** The value a property should record, from what this run observed. */
function verificationOf(classified: boolean, consoleResult: boolean | undefined) {
  if (!classified) return 'none';
  if (consoleResult === undefined) return 'executed';
  return consoleResult ? 'console_matched' : 'console_mismatch';
}

export async function sweep(catalog: Catalog, options: SweepOptions = {}) {
  const tools = [];
  for (const tool of catalog.tools) tools.push(await sweepTool(catalog, tool, options));

  const consoleResults = new Map<string, boolean>();
  const map = options.consoleMap ?? [];
  if (map.length > 0) {
    const { consoleUrl = 'http://127.0.0.1:8088' } = options;
    const state = await consoleJson(`${consoleUrl}/vehicle/state`);
    if (!(state?.browser?.clients >= 1) || state?.availability?.available !== true) {
      throw new SweepStopped('the Virtual Console page is not open and active');
    }
    for (const row of map) {
      const matched = await matchRow(catalog, row, consoleUrl, options);
      consoleResults.set(row.property, (consoleResults.get(row.property) ?? true) && matched);
      options.log?.(`console ${row.property} ${row.field} ${matched ? 'matched' : 'mismatch'}`);
    }
  }

  const verification: Record<string, string> = {};
  for (const property of catalog.properties) {
    const owners = tools.filter((tool) => tool.query === property.query);
    const classified = owners.some((tool) => tool.classified[property.id]);
    verification[property.id] = verificationOf(classified, consoleResults.get(property.id));
  }
  const summary = tools.map((tool) => {
    const properties = catalog.properties.filter((p) => p.query === tool.query).map((p) => p.id);
    const counted = properties.map((id) => consoleResults.get(id));
    return {
      ...tool,
      console: {
        matched: counted.filter((value) => value === true).length,
        mismatch: counted.filter((value) => value === false).length,
        unmapped: counted.filter((value) => value === undefined).length,
      },
    };
  });
  return { tools: summary, verification };
}

// Node runs the main module from its real path.
if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const [, , reportPath, consoleMapPath] = process.argv;
  if (!reportPath) {
    process.stderr.write('usage: node test-support/vehicle-sweep.ts <report.json> [console-map.json]\n');
    process.exit(2);
  }
  const consoleMap = consoleMapPath ? JSON.parse(readFileSync(consoleMapPath, 'utf8')) : [];
  const log = (line: string) => process.stdout.write(`${line}\n`);
  const report = await sweep(loadCatalog(), { consoleMap, log });
  mkdirSync(dirname(reportPath), { recursive: true });
  writeFileSync(reportPath, JSON.stringify(report, null, 2));
}
