// COM-13 experiment: one script drives both paths so their results and timings compare like for like.
//   node test-support/embedded-check.ts embedded [count] [report.json]  -> http://127.0.0.1:18766/mcp (adb forward)
//   node test-support/embedded-check.ts node [count] [report.json]      -> src/server.ts over stdio (adb forward 18765)
// The app must be connected. Values come from whatever the app reads; this script judges nothing.
import { writeFileSync } from 'node:fs';
import { performance } from 'node:perf_hooks';
import { fileURLToPath } from 'node:url';
import { Client } from '@modelcontextprotocol/client';
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/client';
import { StdioClientTransport } from '@modelcontextprotocol/client/stdio';

const EMBEDDED_URL = 'http://127.0.0.1:18766/mcp';
const SERVER = fileURLToPath(new URL('../src/server.ts', import.meta.url));
const TOOL = 'get_energy_level';

type Item = { property: string; status: string; rawValue: unknown; unit: string | null; receivedAt: string | null };

const [target, countArg = '10', reportPath] = process.argv.slice(2);
if (target !== 'embedded' && target !== 'node') {
  console.error('usage: node test-support/embedded-check.ts <embedded|node> [count] [report.json]');
  process.exit(2);
}
const count = Number.parseInt(countArg, 10);

const transport =
  target === 'embedded'
    ? new StreamableHTTPClientTransport(new URL(EMBEDDED_URL))
    : new StdioClientTransport({ command: process.execPath, args: [SERVER], env: { PATH: process.env.PATH ?? '' } });
const client = new Client({ name: 'com13-embedded-check', version: '0' });

const connectStart = performance.now();
await client.connect(transport);
const connectMs = performance.now() - connectStart;
const listed = await client.listTools();

const calls = [];
let firstText: string | null = null;
for (let index = 0; index < count; index += 1) {
  const start = performance.now();
  const result = await client.callTool({ name: TOOL, arguments: {} });
  const elapsedMs = performance.now() - start;
  const structured = (result.structuredContent ?? {}) as { outcome?: string; code?: string; items?: Item[] };
  const text = Array.isArray(result.content) && result.content[0]?.type === 'text' ? result.content[0].text : '';
  firstText ??= text;
  calls.push({
    elapsedMs: Math.round(elapsedMs * 10) / 10,
    isError: result.isError === true,
    outcome: structured.outcome ?? null,
    code: structured.code ?? null,
    items: (structured.items ?? []).map(({ property, status, rawValue, unit, receivedAt }) => ({
      property,
      status,
      rawValue,
      unit,
      receivedAt,
    })),
  });
}
await client.close();

const sorted = calls.map((call) => call.elapsedMs).sort((a, b) => a - b);
const median = sorted.length === 0 ? null : (sorted[(sorted.length - 1) >> 1] + sorted[sorted.length >> 1]) / 2;
const report = {
  target,
  endpoint: target === 'embedded' ? EMBEDDED_URL : 'stdio src/server.ts -> 127.0.0.1:18765',
  protocolVersion: client.getNegotiatedProtocolVersion() ?? null,
  server: client.getServerVersion() ?? null,
  connectMs: Math.round(connectMs * 10) / 10,
  tools: listed.tools.map((tool) => tool.name),
  count,
  medianMs: median,
  minMs: sorted[0] ?? null,
  maxMs: sorted.at(-1) ?? null,
  firstText,
  calls,
};
const out = `${JSON.stringify(report, null, 2)}\n`;
if (reportPath) writeFileSync(reportPath, out);
process.stdout.write(out);
