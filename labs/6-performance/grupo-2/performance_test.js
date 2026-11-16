import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate } from 'k6/metrics';

const handle = 'lab6_canal';
const path = '/api/screens/channels/' + handle;
const baseUrl = (__ENV.BASE_URL || '').trim().replace(/\/$/, '');
const mode = __ENV.MODO || 'verificar';
const users = Number(__ENV.VUS || 1);
const runId = __ENV.RUN_ID || '';
const verify = mode === 'verificar';

function isLabOrigin(value) {
  const origin = /^https:\/\/([a-z0-9.-]+)(?::(\d{1,5}))?$/i.exec(value);
  if (!origin || (origin[2] && (Number(origin[2]) < 1 || Number(origin[2]) > 65535))) return false;
  const host = origin[1].toLowerCase();

  if (/^(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.)+local$/.test(host)) return true;
  if (verify && (host === 'localhost' || host === '127.0.0.1')) return true;

  const octets = host.split('.');
  if (octets.length !== 4 || octets.some((octet) => !/^(0|[1-9]\d{0,2})$/.test(octet) || Number(octet) > 255)) {
    return false;
  }
  const [first, second] = octets.map(Number);
  return first === 10 || (first === 172 && second >= 16 && second <= 31) || (first === 192 && second === 168);
}

if (!isLabOrigin(baseUrl)) {
  throw new Error('BASE_URL debe ser el origen HTTPS LAN del Grupo 1, con IPv4 privada o nombre .local sin ruta. Verificar también admite localhost y 127.0.0.1.');
}
if (!['verificar', 'calentar', 'medir'].includes(mode)) {
  throw new Error('MODO debe ser verificar, calentar o medir.');
}
if (!Number.isInteger(users) || users < 1 || users > 2000 || (mode !== 'medir' && users !== 1)) {
  throw new Error('VUS debe ser un entero entre 1 y 2000 para medir, y 1 en los demás modos.');
}
if (!/^[a-z0-9_-]{1,80}$/i.test(runId)) {
  throw new Error('RUN_ID debe identificar la corrida con letras, números, guiones o guiones bajos.');
}

const durationSeconds = verify ? null : mode === 'calentar' ? 10 : 30;
const invalidChannelResponse = new Rate('canal_contrato_invalido_200');

http.setResponseCallback(http.expectedStatuses(200));

export const options = {
  insecureSkipTLSVerify: false,
  summaryTrendStats: ['avg', 'p(95)'],
  summaryTimeUnit: 'ms',
  scenarios: {
    canal_publico: verify
      ? { executor: 'shared-iterations', vus: 1, iterations: 3, maxDuration: '40s', gracefulStop: '12s' }
      : { executor: 'constant-vus', vus: users, duration: durationSeconds + 's', gracefulStop: '12s' },
  },
  thresholds: verify
    ? {
        http_reqs: ['count==3'],
        http_req_failed: ['rate==0'],
        canal_contrato_invalido_200: ['rate==0'],
      }
    : {},
};

function hasExpectedChannel(response) {
  const contentType = response.headers['Content-Type'] || '';
  if (!/^application\/json(?:\s*;|$)/i.test(contentType)) return false;

  try {
    const screen = response.json();
    const id = screen?.channel?.channel?.channelId;
    // The Lab 6 profile has neither VOD nor a live session. The visitor is anonymous.
    return (
      screen?.channel?.handle === handle &&
      typeof id === 'string' && id.trim() !== '' &&
      screen.meta?.partial === true &&
      screen.vods?.status === 'UNAVAILABLE' &&
      screen.live?.status === 'EMPTY' &&
      screen.follow?.status === 'DENIED'
    );
  } catch {
    return false;
  }
}

export default function () {
  const response = http.get(baseUrl + path, {
    headers: { Accept: 'application/json' },
    redirects: 0,
    timeout: '10s',
  });

  check(response, { 'Estado HTTP 200': (result) => result.status === 200 });
  if (response.status === 200) {
    const valid = hasExpectedChannel(response);
    invalidChannelResponse.add(!valid);
    check(valid, { 'Pantalla esperada del canal público': (result) => result });
    if (verify && !valid) console.warn('HTTP 200 con un contenido distinto del perfil Lab 6.');
  } else if (verify) {
    console.warn(response.status ? 'HTTP ' + response.status : 'No se obtuvo una respuesta HTTP.');
  }
  sleep(1);
}

function metric(data, name, stat) {
  const value = data.metrics?.[name]?.values?.[stat];
  return Number.isFinite(value) ? value : null;
}

function percent(data, name) {
  const values = data.metrics?.[name]?.values;
  // Thresholds can add an empty rate; a missing sample is not zero failures.
  return values && values.passes + values.fails > 0 && Number.isFinite(values.rate) ? values.rate * 100 : null;
}

const display = (number, unit, digits = 2) => number === null ? 'sin datos' : number.toFixed(digits) + unit;

export function handleSummary(data) {
  const row = {
    run_id: runId,
    fecha_utc: new Date().toISOString(),
    modo: mode,
    origen: baseUrl,
    ruta: path,
    usuarios: users,
    duracion_configurada_s: durationSeconds,
    duracion_real_s: Number.isFinite(data.state?.testRunDurationMs) ? data.state.testRunDurationMs / 1000 : null,
    solicitudes: metric(data, 'http_reqs', 'count'),
    solicitudes_por_s: metric(data, 'http_reqs', 'rate'),
    promedio_ms: metric(data, 'http_req_duration', 'avg'),
    p95_ms: metric(data, 'http_req_duration', 'p(95)'),
    fallos_http_pct: percent(data, 'http_req_failed'),
    fallos_contrato_200_pct: percent(data, 'canal_contrato_invalido_200'),
  };

  // All text fields come from constants or validated input without commas.
  const csv = Object.keys(row).join(',') + '\r\n' + Object.values(row).join(',') + '\r\n';
  const file = 'resultados/' + runId;
  const summary = [
    '',
    'Corrida ' + runId + ' | ' + mode + ' | ' + users + ' VU(s)',
    'Destino: ' + baseUrl + path,
    'http_req_duration  avg=' + display(row.promedio_ms, ' ms') + '  p(95)=' + display(row.p95_ms, ' ms'),
    'http_req_failed    ' + display(row.fallos_http_pct, '%', 4),
    'http_reqs          ' + (row.solicitudes ?? 'sin datos') + ' | ' + display(row.solicitudes_por_s, ' req/s'),
    'canal_contrato_invalido_200  ' + display(row.fallos_contrato_200_pct, '% de respuestas HTTP 200', 4),
    '',
  ].join('\n');

  return {
    stdout: summary,
    [file + '.csv']: csv,
    [file + '.json']: JSON.stringify({ corrida: row, origen: baseUrl, ruta: path, resumen_k6: data }, null, 2) + '\n',
  };
}
