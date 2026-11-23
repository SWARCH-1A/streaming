import { useRef, useState } from 'react';
import { Link } from 'react-router';
import type { P2AuditPage } from '@contracts/p2/index';

import { Button } from '@/src/components/atoms/Button';
import { Icon } from '@/src/components/atoms/Icon';
import { Input } from '@/src/components/atoms/Input';
import { Select } from '@/src/components/atoms/Select';
import { FormField } from '@/src/components/molecules/FormField';
import { Notice } from '@/src/components/molecules/Notice';
import { request } from '@/src/shared/api/http';
import { useResource } from '@/src/shared/api/useResource';

import { isAdminRequired } from './administrationError';
import { AuditEventDetail } from './AuditEventDetail';
import styles from './AuditPage.module.css';
import {
  auditActions,
  type AuditEntry,
  type AuditFilters,
  auditPayload,
  auditProducers,
  auditQuery,
  auditReason,
  auditTime,
  defaultAuditFilters,
} from './auditQuery';

export function AuditPage() {
  const [filters, setFilters] = useState(defaultAuditFilters);
  const [query, setQuery] = useState<string | null>(null);
  const [paging, setPaging] = useState({ cursors: [''], index: 0 });
  const [revision, setRevision] = useState(0);
  const [validation, setValidation] = useState('');
  const [selected, setSelected] = useState<AuditEntry | null>(null);
  const initialQuery = useRef<string | null>(null);
  const lastDetailButton = useRef<HTMLButtonElement | null>(null);
  const resultsHeading = useRef<HTMLHeadingElement>(null);
  const cursor = paging.cursors[paging.index] ?? '';
  const events = useResource(`audit:${query ?? 'initial'}:${cursor}:${revision}`, (signal) => {
    // Resolve the default window only in the browser, after hydration. Keep the same
    // bounds across pages so moving time does not silently change the investigation.
    initialQuery.current ??= auditQuery(defaultAuditFilters());
    const params = new URLSearchParams(query ?? initialQuery.current);
    if (cursor) params.set('cursor', cursor);
    return request<P2AuditPage>(`/api/admin/audit-events?${params}`, { signal });
  });
  const denied = isAdminRequired(events.error);

  function change<K extends keyof AuditFilters>(key: K, value: AuditFilters[K]) {
    setFilters((previous) => ({ ...previous, [key]: value }));
  }

  function search(next = filters) {
    try {
      const nextQuery = auditQuery(next);
      setQuery(nextQuery);
      setPaging({ cursors: [''], index: 0 });
      setSelected(null);
      setValidation('');
      setRevision((value) => value + 1);
    } catch (error) {
      setValidation(error instanceof Error ? error.message : 'Revisa los filtros de búsqueda.');
    }
  }

  function page(index: number, nextCursor?: string) {
    setSelected(null);
    setPaging((previous) => ({
      cursors: nextCursor ? [...previous.cursors.slice(0, index), nextCursor] : previous.cursors,
      index,
    }));
    resultsHeading.current?.focus();
  }

  return (
    <section className={styles.page}>
      <nav className={styles.breadcrumb} aria-label="Ubicación">
        <Link to="/admin">Administración</Link>
        <span aria-hidden="true">/</span>
        <span>Auditoría</span>
      </nav>
      <header className={styles.hero}>
        <div>
          <p className={styles.eyebrow}>Registro de seguridad</p>
          <h1>Auditoría</h1>
          <p>Investiga quién realizó una acción, sobre qué recurso y con qué resultado.</p>
        </div>
        <span className={styles.readOnly}>
          <Icon name="eye" size={16} /> Solo lectura
        </span>
      </header>

      {denied ? (
        <Notice tone="warning">
          <strong>Tu cuenta no tiene permisos administrativos.</strong> Inicia sesión con una cuenta
          autorizada para consultar la auditoría.
        </Notice>
      ) : (
        <>
          <form
            className={styles.filters}
            onSubmit={(event) => {
              event.preventDefault();
              search();
            }}
          >
            <div className={styles.sectionHeader}>
              <h2>Consultar actividad</h2>
              <p>Eventos de seguridad de Core y VOD, y solicitudes bloqueadas por el WAF.</p>
            </div>
            <div className={styles.filterGrid}>
              <FormField id="audit-range" label="Período">
                <Select
                  id="audit-range"
                  value={filters.range}
                  onChange={(event) => change('range', event.target.value as AuditFilters['range'])}
                >
                  <option value="24h">Últimas 24 horas</option>
                  <option value="1h">Última hora</option>
                  <option value="7d">Últimos 7 días</option>
                  <option value="custom">Fechas personalizadas</option>
                </Select>
              </FormField>
              <FormField id="audit-producer" label="Servicio">
                <Select
                  id="audit-producer"
                  value={filters.producer}
                  onChange={(event) =>
                    change('producer', event.target.value as AuditFilters['producer'])
                  }
                >
                  <option value="">Todos los servicios disponibles</option>
                  <option value="core">Core</option>
                  <option value="vod">VOD</option>
                  <option value="waf">WAF (ataques bloqueados)</option>
                </Select>
              </FormField>
              <FormField id="audit-action" label="Acción">
                <Select
                  id="audit-action"
                  value={filters.action}
                  onChange={(event) =>
                    change('action', event.target.value as AuditFilters['action'])
                  }
                >
                  <option value="">Todas las acciones</option>
                  {Object.entries(auditActions).map(([value, label]) => (
                    <option key={value} value={value}>
                      {label}
                    </option>
                  ))}
                </Select>
              </FormField>
              <FormField id="audit-outcome" label="Resultado">
                <Select
                  id="audit-outcome"
                  value={filters.outcome}
                  onChange={(event) =>
                    change('outcome', event.target.value as AuditFilters['outcome'])
                  }
                >
                  <option value="">Todos los resultados</option>
                  <option value="SUCCESS">Permitido</option>
                  <option value="DENIED">Denegado</option>
                </Select>
              </FormField>
            </div>
            {filters.range === 'custom' ? (
              <div className={styles.dateGrid}>
                <FormField id="audit-from" label="Desde" hint="Fecha y hora local, inclusive.">
                  <Input
                    id="audit-from"
                    type="datetime-local"
                    required
                    value={filters.from}
                    aria-describedby="audit-from-hint"
                    onChange={(event) => change('from', event.target.value)}
                  />
                </FormField>
                <FormField
                  id="audit-to"
                  label="Hasta"
                  hint="Fecha y hora local, sin incluir este instante."
                >
                  <Input
                    id="audit-to"
                    type="datetime-local"
                    required
                    value={filters.to}
                    aria-describedby="audit-to-hint"
                    onChange={(event) => change('to', event.target.value)}
                  />
                </FormField>
              </div>
            ) : null}
            <details className={styles.advanced}>
              <summary>Buscar por usuario, recurso o solicitud</summary>
              <div className={styles.advancedGrid}>
                <FormField id="audit-actor" label="ID de usuario" hint="Coincidencia exacta.">
                  <Input
                    id="audit-actor"
                    value={filters.actorId}
                    maxLength={128}
                    autoComplete="off"
                    spellCheck={false}
                    aria-describedby="audit-actor-hint"
                    onChange={(event) => change('actorId', event.target.value)}
                  />
                </FormField>
                <FormField
                  id="audit-target"
                  label="ID de recurso"
                  hint="Cuenta, grabación o recurso administrativo."
                >
                  <Input
                    id="audit-target"
                    value={filters.targetId}
                    maxLength={128}
                    autoComplete="off"
                    spellCheck={false}
                    aria-describedby="audit-target-hint"
                    onChange={(event) => change('targetId', event.target.value)}
                  />
                </FormField>
                <FormField
                  id="audit-request"
                  label="ID de solicitud"
                  hint="requestId para relacionar eventos."
                >
                  <Input
                    id="audit-request"
                    value={filters.requestId}
                    maxLength={80}
                    autoComplete="off"
                    spellCheck={false}
                    aria-describedby="audit-request-hint"
                    onChange={(event) => change('requestId', event.target.value)}
                  />
                </FormField>
              </div>
            </details>
            {validation ? <Notice tone="error">{validation}</Notice> : null}
            <div className={styles.filterActions}>
              <Button type="submit" loading={events.loading}>
                <Icon name="search" size={17} /> Buscar eventos
              </Button>
              <Button
                variant="ghost"
                onClick={() => {
                  const defaults = defaultAuditFilters();
                  setFilters(defaults);
                  search(defaults);
                }}
              >
                Restablecer filtros
              </Button>
              <p>50 eventos por página · Fechas en tu hora local</p>
            </div>
          </form>

          <section
            className={styles.results}
            aria-labelledby="audit-results-heading"
            aria-busy={events.loading}
          >
            <header className={styles.resultsHeader}>
              <div>
                <h2 id="audit-results-heading" ref={resultsHeading} tabIndex={-1}>
                  Eventos registrados
                </h2>
                <p>Orden de recepción, del primero al último.</p>
              </div>
              {events.data ? (
                <span className={styles.pageCount}>{events.data.items.length} en esta página</span>
              ) : null}
            </header>
            {events.loading ? (
              <p role="status" className={styles.state}>
                Consultando eventos de seguridad…
              </p>
            ) : null}
            {events.error ? (
              <div className={styles.state}>
                <Notice tone="error">{events.error}</Notice>
                <Button
                  variant="secondary"
                  onClick={() => {
                    setSelected(null);
                    setRevision((value) => value + 1);
                  }}
                >
                  Reintentar consulta
                </Button>
              </div>
            ) : null}
            {events.data?.items.length === 0 ? (
              <div className={styles.state}>
                <Icon name="search" size={28} />
                <h3>No hay eventos con estos filtros</h3>
                <p>Prueba otro período o amplía los criterios de búsqueda.</p>
              </div>
            ) : null}
            {events.data && events.data.items.length > 0 ? (
              <>
                {/* eslint-disable jsx-a11y/no-noninteractive-tabindex -- A named region needs keyboard access to horizontal table scrolling. */}
                <div
                  className={styles.tableScroll}
                  role="region"
                  aria-label="Eventos de seguridad"
                  tabIndex={0}
                >
                  <table className={styles.table}>
                    <caption className={styles.srOnly}>
                      Eventos de seguridad: fecha de acción, servicio, acción, usuario, recurso y
                      resultado.
                    </caption>
                    <thead>
                      <tr>
                        <th scope="col">Fecha y servicio</th>
                        <th scope="col">Acción y recurso</th>
                        <th scope="col">Usuario</th>
                        <th scope="col">Resultado</th>
                        <th scope="col">
                          <span className={styles.srOnly}>Detalle</span>
                        </th>
                      </tr>
                    </thead>
                    <tbody>
                      {events.data.items.map((entry) => {
                        const payload = auditPayload(entry);
                        return (
                          <tr
                            key={entry.event.eventId}
                            className={
                              selected?.event.eventId === entry.event.eventId
                                ? styles.selected
                                : undefined
                            }
                          >
                            <td>
                              <time dateTime={entry.event.occurredAtUtc}>
                                {auditTime(entry.event.occurredAtUtc)}
                              </time>
                              <span className={styles.service}>
                                {auditProducers[entry.event.producer]}
                              </span>
                            </td>
                            <td>
                              <span className={styles.action}>{auditActions[payload.action]}</span>
                              {entry.event.producer === 'waf' ? (
                                <span className={styles.service}>{auditReason(entry)}</span>
                              ) : null}
                              <span className={styles.identifier}>{payload.targetId}</span>
                            </td>
                            <td>
                              <span className={styles.identifier}>
                                {payload.actorId ?? 'Identidad no verificada'}
                              </span>
                            </td>
                            <td>
                              <span
                                className={`${styles.outcome} ${payload.outcome === 'SUCCESS' ? styles.success : styles.denied}`}
                              >
                                {payload.outcome === 'SUCCESS' ? 'Permitido' : 'Denegado'}
                              </span>
                            </td>
                            <td>
                              <Button
                                variant="ghost"
                                size="sm"
                                aria-label={`Ver detalle del evento ${entry.position}`}
                                aria-controls="audit-event-detail"
                                aria-expanded={selected?.event.eventId === entry.event.eventId}
                                onClick={(event) => {
                                  lastDetailButton.current = event.currentTarget;
                                  setSelected(entry);
                                }}
                              >
                                Ver detalle
                                <Icon name="chevron" size={15} />
                              </Button>
                            </td>
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                </div>
                {/* eslint-enable jsx-a11y/no-noninteractive-tabindex */}
                <footer className={styles.pagination}>
                  <p>
                    Página {paging.index + 1}
                    {events.data.nextCursor ? '' : ' · Fin de los resultados'}
                  </p>
                  <div>
                    <Button
                      variant="secondary"
                      size="sm"
                      disabled={paging.index === 0}
                      onClick={() => page(paging.index - 1)}
                    >
                      Anterior
                    </Button>
                    <Button
                      variant="secondary"
                      size="sm"
                      disabled={!events.data.nextCursor}
                      onClick={() => {
                        if (events.data?.nextCursor) page(paging.index + 1, events.data.nextCursor);
                      }}
                    >
                      Siguiente
                      <Icon name="chevron" size={15} />
                    </Button>
                  </div>
                </footer>
              </>
            ) : null}
          </section>
          {selected && events.data ? (
            <AuditEventDetail
              entry={selected}
              onClose={() => {
                setSelected(null);
                lastDetailButton.current?.focus();
              }}
            />
          ) : null}
        </>
      )}
    </section>
  );
}
