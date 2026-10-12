import { useState } from 'react';

import { Avatar } from '@/src/components/atoms/Avatar';
import { Badge } from '@/src/components/atoms/Badge';
import { Button } from '@/src/components/atoms/Button';
import { Icon } from '@/src/components/atoms/Icon';
import { Input } from '@/src/components/atoms/Input';
import { Select } from '@/src/components/atoms/Select';
import { EmptyState } from '@/src/components/molecules/EmptyState';
import { FormField } from '@/src/components/molecules/FormField';
import { Notice } from '@/src/components/molecules/Notice';
import { Panel } from '@/src/components/molecules/Panel';
import { SectionHeading } from '@/src/components/molecules/SectionHeading';
import { StreamCard } from '@/src/components/organisms/StreamCard';
import { demoStreams } from '@/src/modules/discovery/entry';

import { CatalogStates } from './CatalogStates';
import styles from './ComponentsPage.module.css';

export function ComponentsPage() {
  const [action, setAction] = useState('');
  const [email, setEmail] = useState('');
  const first = demoStreams[0];
  return (
    <div className="stack">
      <div>
        <p className="eyebrow">02 · Sistema de diseño</p>
        <h1>Biblioteca de componentes</h1>
        <p className="muted">Primitivas compartidas y variantes que dan forma a cada vista.</p>
      </div>
      <Panel>
        <SectionHeading title="Botones y acciones" />
        <div className="row">
          <Button onClick={() => setAction('Acción primaria seleccionada')}>
            <Icon name="play" />
            Primario
          </Button>
          <Button variant="secondary" onClick={() => setAction('Acción secundaria seleccionada')}>
            Secundario
          </Button>
          <Button variant="ghost" onClick={() => setAction('Acción discreta seleccionada')}>
            Ghost
          </Button>
          <Button variant="danger" onClick={() => setAction('Acción de emisión seleccionada')}>
            <Icon name="broadcast" />
            Transmitir
          </Button>
          <Button disabled>Deshabilitado</Button>
          <Button loading>Cargando</Button>
          <Button
            variant="secondary"
            size="icon"
            aria-label="Ejemplo de configuración"
            onClick={() => setAction('Configuración seleccionada')}
          >
            <Icon name="settings" />
          </Button>
        </div>
        <p className={styles.status} role="status">
          {action || 'Selecciona una variante para probarla.'}
        </p>
        <div className="row">
          <Button size="sm" onClick={() => setAction('Tamaño pequeño')}>
            Pequeño
          </Button>
          <Button onClick={() => setAction('Tamaño mediano')}>Mediano</Button>
          <Button size="lg" onClick={() => setAction('Tamaño grande')}>
            Grande
          </Button>
        </div>
      </Panel>
      <Panel>
        <SectionHeading title="Formularios y validación" />
        <div className={styles.fields}>
          <FormField
            id="sample-email"
            label="Correo electrónico"
            hint="Ejemplo de un campo con etiqueta."
          >
            <Input
              id="sample-email"
              placeholder="tu@ejemplo.com"
              type="email"
              value={email}
              onChange={(event) => setEmail(event.target.value)}
              aria-describedby="sample-email-hint"
            />
          </FormField>
          <FormField id="sample-error" label="Campo con error" error="Elige un valor válido.">
            <Input
              id="sample-error"
              defaultValue="Valor incorrecto"
              aria-invalid
              aria-describedby="sample-error-error"
            />
          </FormField>
          <FormField id="sample-select" label="Categoría">
            <Select id="sample-select">
              <option>Videojuegos</option>
              <option>Música</option>
              <option>Arte</option>
            </Select>
          </FormField>
          <FormField id="sample-disabled" label="Campo deshabilitado">
            <Input id="sample-disabled" disabled placeholder="No disponible" />
          </FormField>
        </div>
      </Panel>
      <Panel>
        <SectionHeading title="Badges, categorías e identidad" />
        <div className="row">
          <Badge tone="live" dot>
            EN VIVO
          </Badge>
          <Badge tone="warning" dot>
            RECONECTANDO
          </Badge>
          <Badge dot>OFFLINE</Badge>
          <Badge tone="success" dot>
            Señal estable
          </Badge>
          <Badge tone="primary">Videojuegos</Badge>
          <Badge>Español</Badge>
          <Avatar name="Valeria Silva" online />
          <Avatar name="Alex Mercer" size="sm" />
          <Avatar name="Nexus Zero" size="lg" />
        </div>
      </Panel>
      <section>
        <SectionHeading title="Tarjetas de transmisión" />
        <div className={styles.cards}>
          {first ? <StreamCard stream={first} /> : null}
          <EmptyState
            title="Sin transmisiones activas"
            description="Prueba otra categoría para encontrar un directo."
            icon="broadcast"
          />
        </div>
      </section>
      <CatalogStates />
      <Panel>
        <SectionHeading title="Avisos y feedback" />
        <div className="stack">
          <Notice>Reproducción de ejemplo, sin servicio multimedia conectado.</Notice>
          <Notice tone="success">Cambios guardados correctamente.</Notice>
          <Notice tone="warning">La señal se está reconectando.</Notice>
          <Notice tone="error">Chat no disponible. Puedes seguir usando el reproductor.</Notice>
        </div>
      </Panel>
    </div>
  );
}
