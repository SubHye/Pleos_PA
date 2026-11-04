// Node side of contracts/vehicle-read. Gives the same verdicts as the Android validator over the same fixtures.
import { readFileSync } from 'node:fs';
import * as z from 'zod';

const CATALOG_URL = new URL('../../contracts/vehicle-read/catalog.json', import.meta.url);

export interface BridgeConfig {
  host: string;
  port: number;
  path: string;
  maxRequestBytes: number;
  maxResponseBytes: number;
  deadlineMs: number;
}
export interface ItemKey {
  property: string;
  area: string | null;
}
export interface ToolEntry {
  name: string;
  query: string;
  legacy: boolean;
  resultForm: 'items' | 'scalar';
  adoption: 'undecided' | 'adopted' | 'rejected';
  adoptionNote?: string;
  fixedItems: ItemKey[];
}
export interface PropertyEntry {
  id: string;
  query: string;
  api: string;
  method: string;
  arity: number;
  areaType: string | null;
  valueType: string;
  unit: string | null;
  verification: 'none' | 'executed' | 'console_matched' | 'console_mismatch';
}
export interface Catalog {
  verifiedOn: string | null;
  sdk: { artifact: string; version: string };
  bridge: BridgeConfig;
  areas: Record<string, string[]>;
  enums: Record<string, string[]>;
  tools: ToolEntry[];
  properties: PropertyEntry[];
  excluded: { api: string; method: string; arity: number; reason: string }[];
}
export interface Selection {
  properties?: string[];
  areas?: string[];
}
export interface BridgeFailure {
  ok: false;
  code: string;
  origin: 'android' | 'pc';
}
export type BridgeResult = { ok: true; response: BridgeResponse } | BridgeFailure;

const UUID_RE = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;
const RECEIVED_AT_RE = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,9})?Z$/;
const ERROR_TYPE_RE = /^[A-Za-z_$][A-Za-z0-9_$]{0,99}$/;
const SOURCES = ['pleos_emulator', 'real_vehicle', 'simulation'];
const READABLE = ['READ', 'READ_WRITE'];
const OUTCOMES = ['COMPLETE', 'PARTIAL', 'FAILED', 'TIMEOUT', 'CANCELLED'];
const STATUSES = [
  'OK',
  'NO_VALUE',
  'UNKNOWN_VALUE',
  'CAPABILITY_FAILED',
  'READ_UNAVAILABLE',
  'AREA_UNSUPPORTED',
  'SUPPORT_UNCONFIRMED',
  'READ_FAILED',
  'PERMISSION_DENIED',
  'PROPERTY_UNSUPPORTED',
  'DRIVING_RESTRICTED',
  'SERVICE_UNAVAILABLE',
  'NOT_READ',
];
const CODES_BY_STATUS: Record<string, (string | null)[]> = {
  NO_VALUE: ['ERROR_EMPTY_RESULT'],
  CAPABILITY_FAILED: [
    null,
    'ERROR_INTERNAL_SERVICE',
    'ERROR_UNDEFINED',
    'ERROR_NOT_SUPPORTED_AREA',
    'ERROR_EMPTY_RESULT',
  ],
  READ_FAILED: [null, 'ERROR_INTERNAL_SERVICE', 'ERROR_UNDEFINED'],
  AREA_UNSUPPORTED: [null, 'ERROR_NOT_SUPPORTED_AREA'],
  PERMISSION_DENIED: [null, 'ERROR_SECURITY_PERMISSION'],
  PROPERTY_UNSUPPORTED: ['ERROR_NOT_SUPPORTED_PROPERTY', 'ERROR_NOT_SUPPORTED_VALUE_TYPE'],
  DRIVING_RESTRICTED: ['ERROR_DRIVING_RESTRICTED'],
  SERVICE_UNAVAILABLE: ['ERROR_SERVICE_CONNECTION_FAILURE'],
};
const INVALID: BridgeFailure = Object.freeze({ ok: false, code: 'INVALID_RESPONSE', origin: 'pc' });

const nullableText = z.string().nullable();
const number = z.number().nullable();
const capabilitySchema = z
  .strictObject({
    access: z.string(),
    matchingAreas: z.int32().nullable(),
    reportedAreas: nullableText,
    minimum: number,
    maximum: number,
  })
  .refine(
    ({ minimum, maximum }) =>
      (minimum === null) === (maximum === null) && (minimum === null || maximum === null || minimum <= maximum),
  );
const smartGlassSchema = z.strictObject({ zoneCount: z.int32(), levelCount: z.int32() });
const itemSchema = z.strictObject({
  property: z.string(),
  area: nullableText,
  status: z.enum(STATUSES),
  capability: capabilitySchema.nullable(),
  rawValue: z.union([z.boolean(), z.number(), z.string(), smartGlassSchema]).nullable(),
  meaning: nullableText,
  unit: nullableText,
  receivedAt: z.string().regex(RECEIVED_AT_RE).nullable(),
  errorType: z.string().regex(ERROR_TYPE_RE).nullable(),
  errorCode: nullableText,
});
const responseSchema = z.strictObject({
  requestId: z.string(),
  query: z.string(),
  outcome: z.enum(OUTCOMES),
  source: z.enum(SOURCES),
  sdkVersion: z.string(),
  items: z.array(itemSchema),
});
type Capability = z.infer<typeof capabilitySchema>;
type BridgeItem = z.infer<typeof itemSchema>;
export type BridgeResponse = z.infer<typeof responseSchema>;
const admissionSchema = z.strictObject({ requestId: z.string(), ok: z.literal(false), code: z.string() });
const rejectionSchema = z.strictObject({ ok: z.literal(false), code: z.string() });

export function loadCatalog(): Catalog {
  return JSON.parse(readFileSync(CATALOG_URL, 'utf8'));
}

export const toolOf = (catalog: Catalog, query: string) => catalog.tools.find((tool) => tool.query === query);
const fixedProperties = (tool: ToolEntry) => new Set(tool.fixedItems.map((item) => item.property));
export const propertyOf = (catalog: Catalog, id: string) => catalog.properties.find((property) => property.id === id);

export function propertiesOf(catalog: Catalog, tool: ToolEntry) {
  const fixed = fixedProperties(tool);
  const dynamic = catalog.properties.filter((p) => p.query === tool.query && !fixed.has(p.id)).map((p) => p.id);
  return new Set([...fixed, ...dynamic]);
}

export function isArea(area: string, vocabulary: Set<string>) {
  const parts = area.split('+');
  return parts.every((part) => vocabulary.has(part)) && new Set(parts).size === parts.length;
}

/** Returns the validated request, or null for anything outside the contract. */
export function parseRequest(catalog: Catalog, text: string) {
  if (Buffer.byteLength(text) > catalog.bridge.maxRequestBytes) return null;
  let body;
  try {
    body = JSON.parse(text);
  } catch {
    return null;
  }
  const selector = z
    .array(z.string())
    .min(1)
    .refine((values) => new Set(values).size === values.length)
    .optional();
  const parsed = z
    .strictObject({
      requestId: z.string().regex(UUID_RE),
      remainingMs: z.number().int().min(1).max(catalog.bridge.deadlineMs),
      query: z.string(),
      properties: selector,
      areas: selector,
    })
    .safeParse(body);
  if (!parsed.success) return null;
  const request = parsed.data;
  const tool = toolOf(catalog, request.query);
  if (!tool) return null;
  if (tool.legacy && (request.properties || request.areas)) return null;
  const allowed = propertiesOf(catalog, tool);
  if (request.properties?.some((id) => !allowed.has(id))) return null;
  const vocabulary = new Set(
    [...allowed].flatMap((id) => catalog.areas[propertyOf(catalog, id)?.areaType ?? ''] ?? []),
  );
  if (request.areas?.some((area) => !isArea(area, vocabulary))) return null;
  return request;
}

const isInt32 = (value: unknown) =>
  typeof value === 'number' && Number.isInteger(value) && value >= -2147483648 && value <= 2147483647;

function isValueType(catalog: Catalog, raw: unknown, valueType: string) {
  if (valueType === 'int') return isInt32(raw);
  if (valueType === 'float') return typeof raw === 'number';
  if (valueType === 'boolean') return typeof raw === 'boolean';
  if (valueType === 'smart_glass_config') return typeof raw === 'object' && raw !== null;
  if (valueType.startsWith('enum:')) return typeof raw === 'string' && catalog.enums[valueType.slice(5)].includes(raw);
  return false;
}

/** A value outside the range capability reports is not a confirmed reading. */
function withinBounds(raw: unknown, capability: Capability | null) {
  if (typeof raw !== 'number' || capability === null || capability.minimum === null || capability.maximum === null)
    return true;
  return raw >= capability.minimum && raw <= capability.maximum;
}

/** Whether the value's type, range and unit are confirmed for this property. */
function confirmed(catalog: Catalog, item: BridgeItem, entry: PropertyEntry) {
  const { rawValue: raw, capability } = item;
  const typed = isValueType(catalog, raw, entry.valueType) && withinBounds(raw, capability);
  switch (entry.id) {
    case 'DOOR_POSITION':
      return capability?.minimum === 0 && capability.maximum === 1 && (raw === 0 || raw === 1);
    case 'WINDOW_POSITION':
      return typed && capability?.minimum !== null && capability?.minimum !== undefined;
    case 'FUEL_PERCENT':
    case 'EV_BATTERY_PERCENT':
      return typed && typeof raw === 'number' && raw >= 0 && raw <= 100;
    default:
      return typed;
  }
}

function expectedMeaning(id: string, raw: unknown) {
  if (id === 'DOOR_POSITION') return raw === 0 ? 'closed' : 'open';
  if (id === 'DOOR_LOCK') return raw === true ? 'locked' : 'unlocked';
  return null;
}

function statusFits(catalog: Catalog, item: BridgeItem, entry: PropertyEntry) {
  const { status, capability, rawValue, meaning, unit, receivedAt, errorType, errorCode } = item;
  const readable = capability !== null && READABLE.includes(capability.access);
  const noValue = rawValue === null && meaning === null && unit === null && receivedAt === null;
  const noError = errorType === null && errorCode === null;
  const typedCode = errorType !== null && (CODES_BY_STATUS[status] ?? []).includes(errorCode);
  const received = receivedAt !== null;
  switch (status) {
    case 'OK':
      return (
        readable &&
        received &&
        noError &&
        (entry.areaType === null || capability.matchingAreas === 1) &&
        confirmed(catalog, item, entry) &&
        meaning === expectedMeaning(entry.id, rawValue) &&
        unit === entry.unit
      );
    case 'NO_VALUE':
      return (
        capability !== null &&
        received &&
        rawValue === null &&
        meaning === null &&
        unit === null &&
        (noError || typedCode)
      );
    case 'UNKNOWN_VALUE':
      return (
        capability !== null &&
        received &&
        rawValue !== null &&
        !confirmed(catalog, item, entry) &&
        meaning === null &&
        unit === null &&
        noError
      );
    case 'CAPABILITY_FAILED':
      return capability === null && noValue && typedCode;
    case 'READ_UNAVAILABLE':
      return capability !== null && !readable && noValue && noError;
    case 'AREA_UNSUPPORTED':
      return readable && noValue && ((noError && capability.matchingAreas === 0) || typedCode);
    case 'SUPPORT_UNCONFIRMED':
      return readable && noValue && noError;
    case 'READ_FAILED':
      return readable && noValue && typedCode;
    case 'NOT_READ':
      return noValue && noError;
    default:
      return noValue && typedCode;
  }
}

function itemFits(catalog: Catalog, tool: ToolEntry, item: BridgeItem, selection: Selection) {
  if (!propertiesOf(catalog, tool).has(item.property)) return false;
  if (selection.properties?.length && !selection.properties.includes(item.property)) return false;
  const entry = propertyOf(catalog, item.property);
  if (!entry) return false;
  if (entry.areaType === null) {
    if (item.area !== null) return false;
  } else if (item.area === null || !isArea(item.area, new Set(catalog.areas[entry.areaType]))) {
    return false;
  }
  if (selection.areas?.length && (item.area === null || !selection.areas.includes(item.area))) return false;
  const { capability } = item;
  if (capability !== null) {
    const hasArea = capability.matchingAreas !== null && capability.reportedAreas !== null;
    const noArea = capability.matchingAreas === null && capability.reportedAreas === null;
    if (entry.areaType === null ? !noArea : !hasArea) return false;
  }
  return statusFits(catalog, item, entry);
}

function success(
  catalog: Catalog,
  body: unknown,
  requestId: string,
  query: string,
  selection: Selection,
): BridgeResult {
  const parsed = responseSchema.safeParse(body);
  const tool = toolOf(catalog, query);
  if (!parsed.success || !tool) return INVALID;
  const response = parsed.data;
  if (response.requestId !== requestId || response.query !== query || response.sdkVersion !== catalog.sdk.version)
    return INVALID;
  const { items, outcome } = response;
  if (!items.every((item) => itemFits(catalog, tool, item, selection))) return INVALID;

  const key = (item: ItemKey) => `${item.property}|${item.area}`;
  const keys = new Set(items.map(key));
  if (keys.size !== items.length) return INVALID;
  const fixedKeys = new Set(tool.fixedItems.map(key));
  const fixed = fixedProperties(tool);
  if (items.some((item) => fixed.has(item.property) && !fixedKeys.has(key(item)))) return INVALID;
  const unfinished = outcome === 'TIMEOUT' || outcome === 'CANCELLED';
  if (!(unfinished && items.length === 0) && ![...fixedKeys].every((value) => keys.has(value))) return INVALID;
  if (!unfinished) {
    if (items.length === 0 || items.some((item) => item.status === 'NOT_READ')) return INVALID;
    const successes = items.filter((item) => item.status === 'OK').length;
    const derived = successes === items.length ? 'COMPLETE' : successes === 0 ? 'FAILED' : 'PARTIAL';
    if (derived !== outcome) return INVALID;
  }
  return { ok: true, response };
}

function admission(body: unknown, requestId: string, codes: string[]): BridgeFailure {
  const parsed = admissionSchema.safeParse(body);
  if (!parsed.success || parsed.data.requestId !== requestId || !codes.includes(parsed.data.code)) return INVALID;
  return { ok: false, code: parsed.data.code, origin: 'android' };
}

function rejection(body: unknown, code: string): BridgeFailure {
  const parsed = rejectionSchema.safeParse(body);
  return parsed.success && parsed.data.code === code ? { ok: false, code, origin: 'android' } : INVALID;
}

/** Validates one bridge reply. Anything that does not fit is INVALID_RESPONSE and is never used as a result. */
export function validateResponse(
  catalog: Catalog,
  {
    http,
    text,
    requestId,
    query,
    selection = {},
  }: { http: number | undefined; text: string; requestId: string; query: string; selection?: Selection },
): BridgeResult {
  if (Buffer.byteLength(text) > catalog.bridge.maxResponseBytes) return INVALID;
  let body;
  try {
    body = JSON.parse(text);
  } catch {
    return INVALID;
  }
  switch (http) {
    case 200:
      return success(catalog, body, requestId, query, selection);
    case 409:
      return admission(body, requestId, ['BUSY']);
    case 503:
      return admission(body, requestId, ['APP_NOT_READY', 'CONNECTION_STOPPED']);
    case 400:
    case 413:
    case 415:
      return rejection(body, 'INVALID_REQUEST');
    case 403:
      return rejection(body, 'REQUEST_FORBIDDEN');
    default:
      return INVALID;
  }
}
