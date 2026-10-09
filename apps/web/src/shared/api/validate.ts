import operations from '@contracts/operations.json';
import schema from '@contracts/p1.schema.json';
import Ajv2020 from 'ajv/dist/2020';
import addFormats from 'ajv-formats';

const ajv = new Ajv2020({ strict: false, allErrors: false });
addFormats(ajv);
ajv.addSchema({ ...schema, $id: 'p1' });
const cache = new Map<string, ReturnType<typeof ajv.compile>>();
export function validateResponse(
  path: string,
  method: string,
  status: number,
  body: unknown,
): void {
  const pathname = path.split('?')[0] ?? path;
  const operation = operations.find(
    (item) =>
      item.method === method &&
      new RegExp(`^${item.path.replace(/\{[^}]+\}/g, '[^/]+')}$`).test(pathname),
  );
  const response = operation?.responses as Record<string, string | null> | undefined;
  const name = response?.[String(status)];
  if (!name) throw new Error('Respuesta sin contrato público.');
  let validate = cache.get(name);
  if (!validate) {
    validate = ajv.compile({ $ref: `p1#/$defs/${name}` });
    cache.set(name, validate);
  }
  if (!validate(body)) throw new Error('El servicio devolvió una respuesta incompatible.');
}

export function validateFrame(frame: unknown): void {
  const kinds: Record<string, string> = {
    'chat.ready': 'ChatReady',
    'chat.status': 'ChatStatus',
    'message.accepted': 'MessageAccepted',
    'message.created': 'MessageCreated',
    error: 'ChatError',
  };
  const type = (frame as { type?: unknown } | null)?.type;
  const name = typeof type === 'string' ? kinds[type] : undefined;
  if (!name) throw new Error('Tipo de frame de chat desconocido.');
  let validate = cache.get(name);
  if (!validate) {
    validate = ajv.compile({ $ref: `p1#/$defs/${name}` });
    cache.set(name, validate);
  }
  if (!validate(frame)) throw new Error('Frame de chat incompatible.');
}
