import { TOOL_TEXTS } from '../src/tool-texts.ts';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { loadCatalog, parseRequest, validateResponse } from '../src/contract.ts';
import { TOOL_ANNOTATIONS, toToolResult, toolInputSchema } from '../src/tool-result.ts';

const fixture = (name: string) =>
  JSON.parse(readFileSync(new URL(`../../contracts/vehicle-read/fixtures/${name}.json`, import.meta.url), 'utf8'));
const catalog = loadCatalog();
const toolNamed = (name: string) => catalog.tools.find((tool) => tool.name === name)!;

test('tool inputs match their fixtures', () => {
  for (const { name, tool, args, accept } of fixture('tool-inputs')) {
    assert.equal(toolInputSchema(catalog, toolNamed(tool)).safeParse(args).success, accept, name);
  }
});

test('accepted tool inputs make requests the bridge contract accepts', () => {
  for (const { name, tool, args, accept } of fixture('tool-inputs')) {
    if (!accept) continue;
    const request = {
      requestId: '11111111-1111-4111-8111-111111111111',
      remainingMs: 9000,
      query: toolNamed(tool).query,
      ...args,
    };
    assert.notEqual(parseRequest(catalog, JSON.stringify(request)), null, name);
  }
});

test('every catalog tool has wording that warns against guessing', () => {
  assert.deepEqual(Object.keys(TOOL_TEXTS).sort(), catalog.tools.map((tool) => tool.name).sort());
  for (const { title, description } of Object.values(TOOL_TEXTS)) {
    assert.ok(title);
    assert.match(description, /추정/);
  }
  assert.match(TOOL_TEXTS.get_tire_status.description, /현재 타이어 공기압이 아니/);
});

test('every tool has an input schema and only read annotations', () => {
  for (const tool of catalog.tools) assert.equal(toolInputSchema(catalog, tool).safeParse({}).success, true, tool.name);
  assert.deepEqual(TOOL_ANNOTATIONS, {
    readOnlyHint: true,
    destructiveHint: false,
    idempotentHint: false,
    openWorldHint: false,
  });
});

test('exactly the single door tool uses the scalar form', () => {
  const scalar = catalog.tools.filter((tool) => tool.resultForm === 'scalar').map((tool) => tool.name);
  assert.deepEqual(scalar, ['get_front_left_door_position']);
  assert.ok(catalog.tools.every((tool) => ['items', 'scalar'].includes(tool.resultForm)));
});

test('tool results match their fixtures', () => {
  for (const { name, requestId, query, bridge, expect } of fixture('tool-results')) {
    const result = toToolResult(catalog, query, bridge, requestId);
    assert.equal(result.isError === true, expect.isError, name);
    assert.deepEqual(result.structuredContent, expect.structuredContent, name);
    assert.equal(result.content.length, 1, name);
    assert.equal(result.content[0].type, 'text', name);
    const { text } = result.content[0];
    for (const part of expect.textIncludes) assert.ok(text.includes(part), `${name}: missing ${part}\n${text}`);
    for (const part of expect.textExcludes) assert.ok(!text.includes(part), `${name}: unexpected ${part}\n${text}`);
  }
});

test('bridge responses in the tool result fixtures pass the bridge validator', () => {
  for (const { name, requestId, query, bridge } of fixture('tool-results')) {
    if (!bridge.ok) continue;
    const verdict = validateResponse(catalog, { http: 200, text: JSON.stringify(bridge.response), requestId, query });
    assert.equal(verdict.ok, true, name);
  }
});

test('a failed item is never written as a value', () => {
  const requestId = '22222222-2222-4222-8222-222222222222';
  const response = {
    requestId,
    query: 'brake_status',
    outcome: 'FAILED',
    source: 'pleos_emulator',
    sdkVersion: '2.3.2',
    items: [
      {
        property: 'BRAKE_PEDAL_STATUS',
        area: null,
        status: 'READ_FAILED',
        capability: { access: 'READ', matchingAreas: null, reportedAreas: null, minimum: null, maximum: null },
        rawValue: null,
        meaning: null,
        unit: null,
        receivedAt: null,
        errorType: 'Exception',
        errorCode: null,
      },
    ],
  };
  const { content } = toToolResult(catalog, 'brake_status', { ok: true, response }, requestId);
  assert.ok(!/false|null/.test(content[0].text), content[0].text);
});
