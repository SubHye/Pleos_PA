// MCP side of contracts/vehicle-read: tool input schemas and tool results built from a validated bridge result.
import * as z from 'zod';
import { isArea, propertiesOf, propertyOf, toolOf } from './contract.ts';
import type { BridgeResponse, BridgeResult, Catalog, ToolEntry } from './contract.ts';

export const TOOL_ANNOTATIONS = Object.freeze({
  readOnlyHint: true,
  destructiveHint: false,
  idempotentHint: false,
  openWorldHint: false,
});

type Item = BridgeResponse['items'][number];

const RECEIPT_NOTE = '수신 시각은 앱이 콜백을 받은 시각이며 차량 측 측정 시각이나 값의 최신성을 보장하지 않습니다.';
const SNAPSHOT_NOTE = '항목들은 순차로 읽었으며 동시에 읽은 스냅샷이 아닙니다.';
const LOCK_NOTE = '잠김/잠금 해제는 SDK 불리언을 메서드 이름 기준(true=잠김)으로 해석한 것입니다.';
const NO_GUESS =
  '확인하지 못한 값을 추정하지 마세요. 자동 재시도하지 않았으며, 필요하면 사용자가 새로 요청할 수 있습니다.';
const SIMULATION_NOTE = '시뮬레이션 결과이며 실제 차량 값이 아닙니다.';

/** Zod schema of one tool's arguments. Legacy tools take none; areas exist only where a property has one. */
export function toolInputSchema(catalog: Catalog, tool: ToolEntry) {
  if (tool.legacy) return z.strictObject({});
  const unique = (values: string[]) => new Set(values).size === values.length;
  const ids = [...propertiesOf(catalog, tool)];
  const shape: Record<string, z.ZodType> = {
    properties: z
      .array(z.enum(ids))
      .min(1)
      .refine(unique)
      .optional()
      .describe('읽을 속성. 생략하면 이 도구의 모든 속성을 읽습니다.'),
  };
  const vocabulary = new Set(ids.flatMap((id) => catalog.areas[propertyOf(catalog, id)?.areaType ?? ''] ?? []));
  if (vocabulary.size > 0) {
    const [first, second] = vocabulary;
    shape.areas = z
      .array(z.string().refine((area) => isArea(area, vocabulary)))
      .min(1)
      .refine(unique)
      .optional()
      .describe(
        `읽을 영역. 값은 ${[...vocabulary].join(', ')} 중 하나이고, 묶음 영역은 ${first}+${second}처럼 차량이 보고한 묶음 그대로 +로 잇습니다. 묶음의 일부만 고르면 AREA_UNSUPPORTED가 될 수 있으니, 차량이 보고하는 영역을 모르면 생략하세요.`,
      );
  }
  return z.strictObject(shape);
}

const textResult = (lines: string[], structuredContent: Record<string, unknown>, isError: boolean) => ({
  content: [{ type: 'text' as const, text: lines.join('\n') }],
  structuredContent,
  ...(isError ? { isError: true } : {}),
});

const notes = (...entries: (string | false | null)[]) => entries.filter((entry): entry is string => Boolean(entry));
const failureLines = (code: string, origin: string) => [`확인하지 못했습니다 [${code}, ${origin}].`, NO_GUESS];
const sourceNote = (source: string) => (source === 'simulation' ? SIMULATION_NOTE : null);

function value(item: Item) {
  const raw = typeof item.rawValue === 'object' ? JSON.stringify(item.rawValue) : item.rawValue;
  return `${raw}${item.unit ? ` ${item.unit}` : ''}${item.meaning ? ` (${item.meaning})` : ''}`;
}

function itemLine(item: Item) {
  const name = `[${item.property}]${item.area ? ` ${item.area}` : ''}`;
  if (item.status === 'OK') return `${name}: ${value(item)}, 콜백 수신 ${item.receivedAt}.`;
  const observed =
    item.receivedAt === null ? '' : ` 관측 원시값 ${JSON.stringify(item.rawValue)}, 콜백 수신 ${item.receivedAt}.`;
  return `${name}: 확인 실패 [${item.status}].${observed}`;
}

function itemsResult(response: BridgeResponse) {
  const { items, outcome, query, source, sdkVersion } = response;
  const isError = outcome !== 'COMPLETE';
  return textResult(
    notes(
      `${query} 결과 ${outcome}. 실행 환경 ${source}, Vehicle SDK ${sdkVersion}.`,
      ...items.map(itemLine),
      items.some((item) => item.property === 'DOOR_LOCK' && item.status === 'OK') && LOCK_NOTE,
      items.some((item) => item.receivedAt !== null) && RECEIPT_NOTE,
      items.length > 1 && SNAPSHOT_NOTE,
      sourceNote(source),
      isError && NO_GUESS,
    ),
    response,
    isError,
  );
}

function scalarResult(response: BridgeResponse, area: string | null) {
  const { items, outcome, requestId, source, sdkVersion } = response;
  const item = items[0] ?? null;
  if (item?.status === 'OK') {
    return textResult(
      notes(
        `${area}: ${item.meaning}. 원시값 ${item.rawValue}, 콜백 수신 ${item.receivedAt}.`,
        RECEIPT_NOTE,
        sourceNote(source),
      ),
      {
        ok: true,
        requestId,
        source,
        area,
        status: item.meaning === 'open' ? 'OPEN' : 'CLOSED',
        state: item.meaning,
        rawValue: item.rawValue,
        receivedAt: item.receivedAt,
        sdkVersion,
        capability: item.capability,
      },
      false,
    );
  }
  const unfinished = outcome === 'TIMEOUT' || outcome === 'CANCELLED';
  const code = unfinished ? outcome : item!.status;
  const observation =
    item && item.receivedAt !== null ? { rawValue: item.rawValue, receivedAt: item.receivedAt } : null;
  return textResult(
    notes(
      ...failureLines(code, 'android'),
      `${area} 문 상태입니다.`,
      observation && `관측 원시값 ${observation.rawValue}, 콜백 수신 ${observation.receivedAt}.`,
      observation && RECEIPT_NOTE,
      sourceNote(source),
    ),
    {
      ok: false,
      origin: 'android',
      code,
      requestId,
      source,
      area,
      status: code,
      sdkVersion,
      capability: item?.capability ?? null,
      errorType: item?.errorType ?? null,
      errorCode: item?.errorCode ?? null,
      observation,
    },
    true,
  );
}

/**
 * Builds the MCP result of one call. `result` comes from validateResponse, or is a Node-side failure such as
 * ANDROID_UNREACHABLE, TIMEOUT or CANCELLED. Text and structuredContent always come from that one result.
 */
export function toToolResult(catalog: Catalog, query: string, result: BridgeResult, requestId: string) {
  const tool = toolOf(catalog, query)!;
  const scalar = tool.resultForm === 'scalar';
  const area = scalar ? tool.fixedItems[0].area : null;
  if (result.ok) return scalar ? scalarResult(result.response, area) : itemsResult(result.response);
  const { code, origin } = result;
  const where = scalar ? { area } : { query };
  return textResult(failureLines(code, origin), { ok: false, origin, code, requestId, ...where }, true);
}
