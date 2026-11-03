// Protocol tests over real stdio processes. InMemoryTransport only links 2025-era instances, so it cannot cover
// 2026-07-28. Bridge replies still come from a fake, so these prove the MCP path, not an SDK read.
import { loadCatalog } from '../src/contract.ts';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mkdtempSync, rmSync, symlinkSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { Client } from '@modelcontextprotocol/client';
import { StdioClientTransport } from '@modelcontextprotocol/client/stdio';
import { energyReply, json, startBridge, waitFor } from '../test-support/fake-bridge.ts';

const ADOPTED = loadCatalog()
  .tools.filter((tool) => tool.adoption === 'adopted')
  .map((tool) => tool.name);
const REJECTED = loadCatalog()
  .tools.filter((tool) => tool.adoption === 'rejected')
  .map((tool) => tool.name);
const names = (listed: { tools: { name: string }[] }) => listed.tools.map((tool) => tool.name);
const SERVER = fileURLToPath(new URL('../src/server.ts', import.meta.url));
const WITH_TOOLS = fileURLToPath(new URL('../test-support/serve-with-tools.ts', import.meta.url));
const ERAS = [
  { era: '2025-11-25', options: {} },
  { era: '2026-07-28', options: { versionNegotiation: { mode: { pin: '2026-07-28' as const } } } },
];
const BASE_ENV = { PATH: process.env.PATH ?? '' };

async function connect(args: string[], options = {}, env: Record<string, string> = BASE_ENV) {
  const transport = new StdioClientTransport({ command: process.execPath, args, env, stderr: 'pipe' });
  const client = new Client({ name: 'test', version: '0' }, options);
  await client.connect(transport);
  return { client, transport };
}

for (const { era, options } of ERAS) {
  test(`${era}: the default server lists exactly the adopted tools and never reaches the bridge`, async (t) => {
    const fake = await startBridge(() => {}, 18765).catch((error) => {
      if (error.code === 'EADDRINUSE') return null;
      throw error;
    });
    if (!fake) t.diagnostic('127.0.0.1:18765 is in use (for example by an adb forward); bridge count not checked');
    const { client } = await connect([SERVER], options);
    try {
      assert.equal(client.getNegotiatedProtocolVersion(), era);
      assert.deepEqual(names(await client.listTools()), ADOPTED);
      if (fake) assert.equal(fake.seen.length, 0);
    } finally {
      await client.close();
      await fake?.close();
    }
  });

  test(`${era}: arguments and environment cannot add tools to the default server`, async () => {
    const env = { ...BASE_ENV, PLEOS_TOOLS: 'get_toll_status', TOOLS: 'get_toll_status', NODE_ENV: 'test' };
    const { client } = await connect([SERVER, '18765', 'get_toll_status'], options, env);
    try {
      assert.deepEqual(names(await client.listTools()), ADOPTED);
    } finally {
      await client.close();
    }
  });

  test(`${era}: a tool the catalog rejected cannot be called and never reaches the bridge`, async (t) => {
    const fake = await startBridge(() => {}, 18765).catch((error) => {
      if (error.code === 'EADDRINUSE') return null;
      throw error;
    });
    if (!fake) t.diagnostic('127.0.0.1:18765 is in use (for example by an adb forward); bridge count not checked');
    const { client } = await connect([SERVER], options);
    try {
      for (const name of REJECTED) {
        const called = await client
          .callTool({ name, arguments: {} })
          .catch((error: Error) => ({ isError: true, error }));
        assert.equal(called.isError, true, name);
      }
      if (fake) assert.equal(fake.seen.length, 0);
    } finally {
      await client.close();
      await fake?.close();
    }
  });

  test(`${era}: only adopted tools are listed, with strict input and read annotations`, async () => {
    const fake = await startBridge(() => {});
    const { client } = await connect([WITH_TOOLS, String(fake.port), 'get_energy_level', 'get_seat_status'], options);
    try {
      const { tools } = await client.listTools();
      assert.deepEqual(
        tools.map((tool) => tool.name),
        ['get_energy_level', 'get_seat_status'],
      );
      for (const tool of tools) {
        assert.equal(tool.inputSchema.type, 'object', tool.name);
        assert.equal(tool.inputSchema.additionalProperties, false, tool.name);
        assert.deepEqual(tool.annotations, {
          readOnlyHint: true,
          destructiveHint: false,
          idempotentHint: false,
          openWorldHint: false,
        });
      }
      assert.deepEqual(Object.keys(tools[0].inputSchema.properties ?? {}), []);
      assert.deepEqual(Object.keys(tools[1].inputSchema.properties ?? {}).sort(), ['areas', 'properties']);
      const areas = tools[1].inputSchema.properties?.areas as { description?: string };
      assert.match(areas.description ?? '', /ROW_1_LEFT.*\+/);
      assert.equal(fake.seen.length, 0, 'discovery never reaches the bridge');
    } finally {
      await client.close();
      await fake.close();
    }
  });

  test(`${era}: one tool call is one bridge request`, async () => {
    const fake = await startBridge((body, res) => json(res, 200, energyReply(body.requestId)));
    const { client } = await connect([WITH_TOOLS, String(fake.port), 'get_energy_level'], options);
    try {
      const result = await client.callTool({ name: 'get_energy_level', arguments: {} });
      assert.notEqual(result.isError, true);
      assert.equal(fake.seen.length, 1);
      assert.equal(fake.seen[0].url, '/v3/vehicle-read');
      assert.equal(fake.seen[0].authorization, undefined);
      const content = result.structuredContent as Record<string, unknown>;
      assert.equal(content.requestId, fake.seen[0].body.requestId);
      assert.equal(content.source, 'simulation');
    } finally {
      await client.close();
      await fake.close();
    }
  });

  test(`${era}: invalid calls never reach the bridge`, async () => {
    const fake = await startBridge((body, res) => json(res, 200, energyReply(body.requestId)));
    const { client } = await connect([WITH_TOOLS, String(fake.port), 'get_energy_level', 'get_seat_status'], options);
    try {
      const invalid = [
        { name: 'get_energy_level', arguments: { area: 'ROW_1_LEFT' } },
        { name: 'get_seat_status', arguments: { properties: ['HVAC_AC_STATE'] } },
        { name: 'get_seat_status', arguments: { areas: ['HOOD'] } },
        { name: 'get_seat_status', arguments: { timeoutMs: 1000 } },
      ];
      for (const call of invalid) {
        const result = await client.callTool(call);
        assert.equal(result.isError, true, JSON.stringify(call));
        assert.equal(result.structuredContent, undefined, JSON.stringify(call));
      }
      for (const name of ['get_not_in_catalog', 'get_wiper_status']) {
        await assert.rejects(client.callTool({ name, arguments: {} }), { code: -32602 }, name);
      }
      assert.equal(fake.seen.length, 0);
    } finally {
      await client.close();
      await fake.close();
    }
  });

  test(`${era}: MCP cancellation closes the bridge request`, async () => {
    const fake = await startBridge(() => {});
    const { client } = await connect([WITH_TOOLS, String(fake.port), 'get_energy_level'], options);
    try {
      const cancel = new AbortController();
      const call = client.callTool({ name: 'get_energy_level', arguments: {} }, { signal: cancel.signal });
      await waitFor(() => fake.seen.length === 1);
      cancel.abort();
      await assert.rejects(call);
      await waitFor(() => fake.seen[0].closed);
      assert.equal(fake.seen.length, 1);
    } finally {
      await client.close();
      await fake.close();
    }
  });

  test(`${era}: closing the client ends the pending bridge request`, async () => {
    const fake = await startBridge(() => {});
    const { client } = await connect([WITH_TOOLS, String(fake.port), 'get_energy_level'], options);
    try {
      const call = client.callTool({ name: 'get_energy_level', arguments: {} }).catch(() => null);
      await waitFor(() => fake.seen.length === 1);
      await client.close();
      await call;
      await waitFor(() => fake.seen[0].closed);
    } finally {
      await fake.close();
    }
  });

  test(`${era}: two server processes run independently`, async () => {
    const first = await connect([SERVER], options);
    const second = await connect([SERVER], options);
    try {
      assert.notEqual(first.transport.pid, second.transport.pid);
      assert.deepEqual(names(await first.client.listTools()), ADOPTED);
      assert.deepEqual(names(await second.client.listTools()), ADOPTED);
    } finally {
      await first.client.close();
      await second.client.close();
    }
  });

  test(`${era}: a server path through a junction or symlink still serves MCP`, async () => {
    const dir = mkdtempSync(join(tmpdir(), 'pleos-pa-mcp-'));
    const link = join(dir, 'mcp');
    symlinkSync(fileURLToPath(new URL('..', import.meta.url)), link, 'junction');
    const transport = new StdioClientTransport({
      command: process.execPath,
      args: [join(link, 'src', 'server.ts')],
      env: BASE_ENV,
      stderr: 'pipe',
    });
    const client = new Client({ name: 'test', version: '0' }, options);
    try {
      await client.connect(transport);
      assert.deepEqual(names(await client.listTools()), ADOPTED);
    } finally {
      await client.close().catch(() => {});
      rmSync(link);
      rmSync(dir, { recursive: true });
    }
  });
}

test('stdout carries only MCP JSON-RPC, stderr stays empty and stdin EOF ends the process', async () => {
  const child = spawn(process.execPath, [SERVER], { env: BASE_ENV, stdio: ['pipe', 'pipe', 'pipe'] });
  let stdout = '';
  let stderr = '';
  child.stdout.on('data', (chunk) => {
    stdout += chunk;
  });
  child.stderr.on('data', (chunk) => {
    stderr += chunk;
  });
  const send = (message: unknown) => child.stdin.write(`${JSON.stringify(message)}\n`);
  send({
    jsonrpc: '2.0',
    id: 1,
    method: 'initialize',
    params: { protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'raw', version: '0' } },
  });
  send({ jsonrpc: '2.0', method: 'notifications/initialized' });
  send({ jsonrpc: '2.0', id: 2, method: 'tools/list' });
  await waitFor(() => stdout.includes('"id":2'));
  const exited = new Promise((resolve) => child.once('exit', (code) => resolve(code)));
  child.stdin.end();
  const code = await Promise.race([exited, new Promise((resolve) => setTimeout(() => resolve('hung'), 3_000))]);
  if (code === 'hung') child.kill();
  assert.equal(code, 0);
  const lines = stdout.trim().split('\n');
  for (const line of lines) assert.equal(JSON.parse(line).jsonrpc, '2.0');
  assert.deepEqual(
    JSON.parse(lines.find((line) => line.includes('"id":2'))!).result.tools.map((tool: { name: string }) => tool.name),
    ADOPTED,
  );
  assert.equal(stderr.trim(), '');
});

test('a tool table naming an unknown tool stops the process before any client connects', async () => {
  const child = spawn(process.execPath, [WITH_TOOLS, '18765', 'get_unknown'], {
    env: BASE_ENV,
    stdio: ['pipe', 'pipe', 'pipe'],
  });
  let stdout = '';
  let stderr = '';
  child.stdout.on('data', (chunk) => {
    stdout += chunk;
  });
  child.stderr.on('data', (chunk) => {
    stderr += chunk;
  });
  const exited = new Promise((resolve) => child.once('exit', (code) => resolve(code)));
  const code = await Promise.race([exited, new Promise((resolve) => setTimeout(() => resolve('hung'), 3_000))]);
  if (code === 'hung') child.kill();
  assert.equal(code, 1);
  assert.equal(stdout, '');
  assert.equal(stderr.trim(), 'pleos-pa-mcp: INVALID_TOOL_TABLE');
});

test('stdin EOF during a tool call ends the request and the process without leaking to stdout or stderr', async () => {
  const fake = await startBridge(() => {});
  const child = spawn(process.execPath, [WITH_TOOLS, String(fake.port), 'get_energy_level'], {
    env: BASE_ENV,
    stdio: ['pipe', 'pipe', 'pipe'],
  });
  let stdout = '';
  let stderr = '';
  child.stdout.on('data', (chunk) => {
    stdout += chunk;
  });
  child.stderr.on('data', (chunk) => {
    stderr += chunk;
  });
  const send = (message: unknown) => child.stdin.write(`${JSON.stringify(message)}\n`);
  try {
    send({
      jsonrpc: '2.0',
      id: 1,
      method: 'initialize',
      params: { protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'raw', version: '0' } },
    });
    send({ jsonrpc: '2.0', method: 'notifications/initialized' });
    send({ jsonrpc: '2.0', id: 2, method: 'tools/call', params: { name: 'get_energy_level', arguments: {} } });
    await waitFor(() => fake.seen.length === 1);
    const exited = new Promise((resolve) => child.once('exit', (code) => resolve(code)));
    child.stdin.end();
    const code = await Promise.race([exited, new Promise((resolve) => setTimeout(() => resolve('hung'), 1_500))]);
    if (code === 'hung') child.kill();
    assert.equal(code, 0);
    await waitFor(() => fake.seen[0].closed);
    for (const line of stdout.trim().split('\n')) assert.equal(JSON.parse(line).jsonrpc, '2.0');
    assert.equal(stderr.trim(), '');
  } finally {
    await fake.close();
  }
});
