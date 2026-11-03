// Fake Android bridge for tests. Everything it answers is mock evidence, not an SDK result.
import { createServer } from 'node:http';
import type { AddressInfo } from 'node:net';
import type { ServerResponse } from 'node:http';

export interface SeenRequest {
  raw: string;
  body: Record<string, any>;
  closed: boolean;
  authorization: string | undefined;
  method: string | undefined;
  url: string | undefined;
}

export type Reply = (body: Record<string, any>, res: ServerResponse) => void;

export async function startBridge(reply: Reply, listenPort = 0) {
  const seen: SeenRequest[] = [];
  const server = createServer((req, res) => {
    let raw = '';
    req.on('data', (chunk) => {
      raw += chunk;
    });
    req.on('end', () => {
      const entry: SeenRequest = {
        raw,
        body: JSON.parse(raw),
        closed: false,
        authorization: req.headers.authorization,
        method: req.method,
        url: req.url,
      };
      res.on('close', () => {
        entry.closed = true;
      });
      seen.push(entry);
      reply(entry.body, res);
    });
  });
  await new Promise<void>((resolve, reject) => {
    server.once('error', reject);
    server.listen(listenPort, '127.0.0.1', () => {
      server.off('error', reject);
      resolve();
    });
  });
  return {
    port: (server.address() as AddressInfo).port,
    seen,
    close: () =>
      new Promise<void>((resolve) => {
        server.closeAllConnections();
        server.close(() => resolve());
      }),
  };
}

export function json(res: ServerResponse, status: number, value: unknown, contentType = 'application/json') {
  const body = JSON.stringify(value);
  res.writeHead(status, { 'Content-Type': contentType, 'Content-Length': Buffer.byteLength(body) });
  res.end(body);
}

/** Polls instead of sleeping for a fixed time, so a slow machine only makes the test slower. */
export async function waitFor(condition: () => boolean, timeoutMs = 3_000) {
  const started = performance.now();
  while (!condition()) {
    if (performance.now() - started > timeoutMs) throw new Error('condition not met in time');
    await new Promise((resolve) => setTimeout(resolve, 5));
  }
}

const capability = { access: 'READ', matchingAreas: null, reportedAreas: null, minimum: null, maximum: null };

/** A complete energy_level reply for the request it answers. */
export const energyReply = (requestId: string) => ({
  requestId,
  query: 'energy_level',
  outcome: 'COMPLETE',
  source: 'simulation',
  sdkVersion: '2.3.2',
  items: ['FUEL_PERCENT', 'EV_BATTERY_PERCENT'].map((property) => ({
    property,
    area: null,
    status: 'OK',
    capability,
    rawValue: 50,
    meaning: null,
    unit: '%',
    receivedAt: '2026-11-03T01:02:03.456Z',
    errorType: null,
    errorCode: null,
  })),
});
