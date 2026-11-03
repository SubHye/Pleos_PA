// One tool call is one HTTP request to the Android bridge, under one deadline, with no retry.
import { randomUUID } from 'node:crypto';
import { request as httpRequest } from 'node:http';
import { parseRequest, validateResponse } from './contract.ts';
import type { BridgeFailure, BridgeResult, Catalog, Selection } from './contract.ts';

const JSON_TYPE_RE = /^application\/json(\s*;\s*charset="?utf-8"?)?$/i;
const TIMED_OUT = Symbol('timeout');
const CANCELLED = Symbol('cancelled');

export interface ReadOptions {
  signal?: AbortSignal;
  selection?: Selection;
  port?: number;
  deadlineMs?: number;
  now?: () => number;
  startedAt?: number;
}

type Reply = { status: number | undefined; text: string } | { failure: 'aborted' | 'invalid' | 'unreachable' };

export const pcFailure = (code: string): BridgeFailure => ({ ok: false, code, origin: 'pc' });

export async function readVehicle(
  catalog: Catalog,
  query: string,
  options: ReadOptions = {},
): Promise<{ requestId: string; result: BridgeResult }> {
  const { signal, selection = {}, port = catalog.bridge.port, deadlineMs = catalog.bridge.deadlineMs } = options;
  const { now = () => performance.now(), startedAt = now() } = options;
  const requestId = randomUUID();
  const finish = (result: BridgeResult) => ({ requestId, result });
  if (signal?.aborted) return finish(pcFailure('CANCELLED'));
  const remainingMs = Math.floor(deadlineMs - (now() - startedAt));
  if (remainingMs < 1) return finish(pcFailure('TIMEOUT'));

  const payload = JSON.stringify({ requestId, remainingMs, query, ...selection });
  if (parseRequest(catalog, payload) === null) return finish(pcFailure('INVALID_REQUEST'));

  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(TIMED_OUT), remainingMs);
  const onCancel = () => controller.abort(CANCELLED);
  signal?.addEventListener('abort', onCancel, { once: true });
  const late = () => now() - startedAt >= deadlineMs;
  const aborted = () => pcFailure(controller.signal.reason === CANCELLED ? 'CANCELLED' : 'TIMEOUT');
  try {
    const reply = await exchange(catalog, port, payload, controller.signal);
    if (controller.signal.aborted) return finish(aborted());
    if (late()) return finish(pcFailure('TIMEOUT'));
    if ('failure' in reply) {
      if (reply.failure === 'unreachable') return finish(pcFailure('ANDROID_UNREACHABLE'));
      return finish(reply.failure === 'aborted' ? aborted() : pcFailure('INVALID_RESPONSE'));
    }
    const result = validateResponse(catalog, { http: reply.status, text: reply.text, requestId, query, selection });
    return finish(late() ? pcFailure('TIMEOUT') : result);
  } finally {
    clearTimeout(timer);
    signal?.removeEventListener('abort', onCancel);
  }
}

function exchange(catalog: Catalog, port: number, payload: string, signal: AbortSignal) {
  const { host, path, maxResponseBytes } = catalog.bridge;
  return new Promise<Reply>((resolve) => {
    const req = httpRequest(
      {
        host,
        port,
        path,
        method: 'POST',
        agent: false,
        signal,
        headers: {
          Host: `${host}:${port}`,
          'Content-Type': 'application/json',
          'Content-Length': Buffer.byteLength(payload),
        },
      },
      (res) => {
        if (!JSON_TYPE_RE.test(res.headers['content-type'] ?? '')) {
          res.destroy();
          resolve({ failure: 'invalid' });
          return;
        }
        const chunks: Buffer[] = [];
        let size = 0;
        res.on('data', (chunk: Buffer) => {
          size += chunk.length;
          if (size > maxResponseBytes) {
            res.destroy();
            resolve({ failure: 'invalid' });
          } else {
            chunks.push(chunk);
          }
        });
        res.on('end', () => resolve({ status: res.statusCode, text: Buffer.concat(chunks).toString('utf8') }));
        res.on('error', () => resolve({ failure: signal.aborted ? 'aborted' : 'invalid' }));
      },
    );
    req.on('error', (error: NodeJS.ErrnoException) => {
      if (signal.aborted) resolve({ failure: 'aborted' });
      else resolve({ failure: error.code?.startsWith('HPE_') ? 'invalid' : 'unreachable' });
    });
    req.end(payload);
  });
}
