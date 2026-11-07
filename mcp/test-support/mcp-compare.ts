// COM-12: drives the Node MCP server (stdio, Bridge 18765) and the app's embedded MCP server (HTTP 18766) with
// the same calls, one at a time, and reports where their results differ. Both must be forwarded and the app
// connected. Only the request ID and the receipt time may differ; the script judges nothing about the vehicle.
//   node test-support/mcp-compare.ts <report.json> [repeat]
import { writeFileSync } from 'node:fs';
import { isDeepStrictEqual } from 'node:util';
import { fileURLToPath } from 'node:url';
import { Client, StreamableHTTPClientTransport } from '@modelcontextprotocol/client';
import { StdioClientTransport } from '@modelcontextprotocol/client/stdio';

const EMBEDDED_URL = 'http://127.0.0.1:18766/mcp';
const SERVER = fileURLToPath(new URL('../src/server.ts', import.meta.url));
const RECEIVED_AT = /\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,9})?Z/g;

type Result = Awaited<ReturnType<Client['callTool']>>;

/** What must match: everything but the request ID and the receipt times. */
function comparable(result: Result) {
  const text = Array.isArray(result.content) && result.content[0]?.type === 'text' ? result.content[0].text : '';
  const strip = (value: unknown): unknown => {
    if (Array.isArray(value)) return value.map(strip);
    if (value === null || typeof value !== 'object') return value;
    return Object.fromEntries(
      Object.entries(value)
        .filter(([key]) => key !== 'requestId')
        .map(([key, inner]) => [key, key === 'receivedAt' && inner !== null ? '<receivedAt>' : strip(inner)]),
    );
  };
  return {
    isError: result.isError === true,
    text: text.replace(RECEIVED_AT, '<receivedAt>'),
    structuredContent: strip(result.structuredContent ?? null),
  };
}

async function timed(client: Client, name: string) {
  const started = performance.now();
  const result = await client.callTool({ name, arguments: {} });
  return { result, elapsedMs: Math.round(performance.now() - started) };
}

const [reportPath, repeatArg = '1'] = process.argv.slice(2);
if (!reportPath) {
  console.error('usage: node test-support/mcp-compare.ts <report.json> [repeat]');
  process.exit(2);
}
const repeat = Number.parseInt(repeatArg, 10);

const node = new Client({ name: 'com12-compare', version: '0' });
await node.connect(
  new StdioClientTransport({ command: process.execPath, args: [SERVER], env: { PATH: process.env.PATH ?? '' } }),
);
const embedded = new Client({ name: 'com12-compare', version: '0' });
await embedded.connect(new StreamableHTTPClientTransport(new URL(EMBEDDED_URL)));

const nodeTools = (await node.listTools()).tools;
const embeddedTools = (await embedded.listTools()).tools;
const report = {
  embedded: { url: EMBEDDED_URL, server: embedded.getServerVersion() ?? null },
  node: { server: node.getServerVersion() ?? null },
  protocol: { node: node.getNegotiatedProtocolVersion(), embedded: embedded.getNegotiatedProtocolVersion() },
  toolNames: { node: nodeTools.map((tool) => tool.name), embedded: embeddedTools.map((tool) => tool.name) },
  toolListDifferences: [] as { name: string; node: unknown; embedded: unknown }[],
  calls: [] as {
    tool: string;
    run: number;
    same: boolean;
    nodeMs: number;
    embeddedMs: number;
    node: ReturnType<typeof comparable>;
    embedded?: ReturnType<typeof comparable>;
  }[],
};
for (const tool of nodeTools) {
  const other = embeddedTools.find((entry) => entry.name === tool.name);
  // The SDK's ToolSchema cannot carry additionalProperties; CONTRACT.md records this one difference.
  const { additionalProperties, ...schema } = tool.inputSchema;
  if (!other || additionalProperties !== false || !isDeepStrictEqual({ ...tool, inputSchema: schema }, other)) {
    report.toolListDifferences.push({ name: tool.name, node: tool, embedded: other ?? null });
  }
}

for (const { name } of nodeTools) {
  for (let run = 1; run <= repeat; run += 1) {
    // Alternate which path goes first so a value that changes between reads shows on both sides.
    const [first, second] = run % 2 === 1 ? [node, embedded] : [embedded, node];
    const a = await timed(first, name);
    const b = await timed(second, name);
    const [fromNode, fromEmbedded] = first === node ? [a, b] : [b, a];
    const left = comparable(fromNode.result);
    const right = comparable(fromEmbedded.result);
    const same = isDeepStrictEqual(left, right);
    report.calls.push({
      tool: name,
      run,
      same,
      nodeMs: fromNode.elapsedMs,
      embeddedMs: fromEmbedded.elapsedMs,
      node: left,
      ...(same ? {} : { embedded: right }),
    });
    console.log(
      `${name} ${run}/${repeat} ${same ? 'same' : 'DIFFERENT'} node ${fromNode.elapsedMs}ms embedded ${fromEmbedded.elapsedMs}ms`,
    );
  }
}
await node.close();
await embedded.close();

const different = report.calls.filter((call) => !call.same).length;
console.log(
  `tools/list differences ${report.toolListDifferences.length}; calls ${report.calls.length}, different ${different}`,
);
writeFileSync(reportPath, `${JSON.stringify(report, null, 2)}\n`);
