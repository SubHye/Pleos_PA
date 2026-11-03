#!/usr/bin/env node
// stdout carries MCP messages only; diagnostics go to stderr as fixed codes only.
import { realpathSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { McpServer } from '@modelcontextprotocol/server';
import { serveStdio } from '@modelcontextprotocol/server/stdio';
import { pcFailure, readVehicle } from './bridge-client.ts';
import { loadCatalog } from './contract.ts';
import type { Catalog, Selection, ToolEntry } from './contract.ts';
import { TOOL_ANNOTATIONS, toToolResult, toolInputSchema } from './tool-result.ts';

export interface AdoptedTool {
  name: string;
  title: string;
  description: string;
}

/**
 * Tools shown to clients. The contract lists a tool only after it passes execution verification, and nothing has yet.
 * COM-23 adds entries here, with their descriptions, as COM-20 verifies them.
 */
export const ADOPTED_TOOLS: readonly AdoptedTool[] = Object.freeze([]);

type ToolResult = ReturnType<typeof toToolResult>;

interface RunOptions {
  signal?: AbortSignal;
  port?: number;
  deadlineMs?: number;
  now?: () => number;
  render?: (result: ToolResult) => ToolResult;
}

/** Runs one call under a single deadline that starts here and also covers building the result. */
export async function runTool(catalog: Catalog, tool: ToolEntry, selection: Selection, options: RunOptions = {}) {
  const { signal, port, deadlineMs = catalog.bridge.deadlineMs, now = () => performance.now() } = options;
  const { render = (value) => value } = options;
  const startedAt = now();
  const { requestId, result } = await readVehicle(catalog, tool.query, {
    signal,
    selection,
    port,
    deadlineMs,
    now,
    startedAt,
  });
  const out = render(toToolResult(catalog, tool.query, result, requestId));
  if (now() - startedAt < deadlineMs) return out;
  return toToolResult(catalog, tool.query, pcFailure('TIMEOUT'), requestId);
}

interface ServerOptions {
  tools?: readonly AdoptedTool[];
  shutdownSignal?: AbortSignal;
  port?: number;
}

/** Factory for serveStdio, which may call it again for a 2025-era client, so it only registers. */
export function createServer({ tools = ADOPTED_TOOLS, shutdownSignal, port }: ServerOptions = {}) {
  const catalog = loadCatalog();
  const server = new McpServer({ name: 'pleos-pa-vehicle', version: '0.1.0' }, { capabilities: { tools: {} } });
  for (const { name, title, description } of tools) {
    const tool = catalog.tools.find((entry) => entry.name === name);
    if (!tool) throw new Error(`Tool is not in the catalog: ${name}`);
    server.registerTool(
      name,
      { title, description, inputSchema: toolInputSchema(catalog, tool), annotations: TOOL_ANNOTATIONS },
      async (args: Selection, ctx) => {
        const signals = [ctx.mcpReq.signal, shutdownSignal].filter((signal) => signal !== undefined);
        return runTool(catalog, tool, args, { port, signal: AbortSignal.any(signals) });
      },
    );
  }
  return server;
}

/** Serves over stdio until stdin ends or the process is told to stop. */
export function serve(options: Omit<ServerOptions, 'shutdownSignal'> = {}) {
  const shutdown = new AbortController();
  const handle = serveStdio(() => createServer({ ...options, shutdownSignal: shutdown.signal }), {
    legacy: 'serve',
    onerror: () => process.stderr.write('pleos-pa-mcp: STDIO_ERROR\n'),
  });
  let stopping = false;
  const stop = () => {
    if (stopping) return;
    stopping = true;
    shutdown.abort();
    handle.close().catch(() => {});
  };
  process.stdin.once('end', stop);
  process.stdin.once('close', stop);
  process.once('SIGINT', stop);
  process.once('SIGTERM', stop);
}

// Node runs the main module from its real path, so compare real paths (junction and symlink safe).
const invokedPath = process.argv[1] ? realpathSync(process.argv[1]) : '';
if (realpathSync(fileURLToPath(import.meta.url)) === invokedPath) serve();
