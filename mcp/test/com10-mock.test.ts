import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { Client } from '@modelcontextprotocol/client';
import { StdioClientTransport } from '@modelcontextprotocol/client/stdio';
import { waitFor } from '../test-support/fake-bridge.ts';

const ENTRY = fileURLToPath(new URL('../test-support/serve-com10-mock.ts', import.meta.url));
const ENV = { PATH: process.env.PATH ?? '' };

for (const era of ['2025-11-25', '2026-07-28'] as const) {
  test(`${era}: the COM-10 entry point exposes one labelled mock and answers repeated calls`, async () => {
    const client = new Client(
      { name: 'com10-smoke', version: '0' },
      era === '2026-07-28' ? { versionNegotiation: { mode: { pin: era } } } : {},
    );
    const transport = new StdioClientTransport({ command: process.execPath, args: [ENTRY], env: ENV, stderr: 'pipe' });
    try {
      await client.connect(transport);
      const { tools } = await client.listTools();
      assert.deepEqual(
        tools.map((tool) => tool.name),
        ['get_energy_level'],
      );
      assert.match(tools[0].description ?? '', /모의/);
      assert.match(tools[0].title ?? '', /MOCK/);
      const requestIds = new Set();
      for (let index = 0; index < 2; index++) {
        const result = await client.callTool({ name: 'get_energy_level', arguments: {} });
        assert.notEqual(result.isError, true);
        const body = result.structuredContent as {
          source: string;
          requestId: string;
          items: { rawValue: number; unit: string }[];
        };
        assert.equal(body.source, 'simulation');
        assert.equal(body.items.length, 2);
        for (const item of body.items) {
          assert.equal(item.rawValue, 50);
          assert.equal(item.unit, '%');
        }
        assert.match(JSON.stringify(result.content), /실제 차량 값이 아닙니다/);
        requestIds.add(body.requestId);
      }
      assert.equal(requestIds.size, 2);
    } finally {
      await client.close();
    }
  });
}

test('the COM-10 mock releases its bridge and exits when stdin ends', async () => {
  const child = spawn(process.execPath, [ENTRY], { env: ENV, stdio: ['pipe', 'pipe', 'pipe'] });
  let stdout = '';
  child.stdout.on('data', (chunk) => {
    stdout += chunk;
  });
  const exited = new Promise((resolve) => child.once('exit', (code) => resolve(code)));
  try {
    child.stdin.write(
      `${JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'initialize', params: { protocolVersion: '2025-11-25', capabilities: {}, clientInfo: { name: 'raw', version: '0' } } })}\n`,
    );
    await waitFor(() => stdout.includes('"id":1'));
    child.stdin.end();
    let timeout: ReturnType<typeof setTimeout> | undefined;
    try {
      const code = await Promise.race([
        exited,
        new Promise((resolve) => {
          timeout = setTimeout(() => resolve('hung'), 3000);
        }),
      ]);
      assert.equal(code, 0);
    } finally {
      clearTimeout(timeout);
    }
    for (const line of stdout.trim().split('\n')) assert.equal(JSON.parse(line).jsonrpc, '2.0');
  } finally {
    if (child.exitCode === null) child.kill();
  }
});
