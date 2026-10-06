import { Link } from 'react-router';

import { ActionLink } from '@/src/components/atoms/ActionLink';
import { Badge } from '@/src/components/atoms/Badge';
import { Icon } from '@/src/components/atoms/Icon';
import { Panel } from '@/src/components/molecules/Panel';
import { SectionHeading } from '@/src/components/molecules/SectionHeading';

import styles from './FoundationsPage.module.css';

const tokens = [
  { name: 'Canvas', variable: '--color-canvas', hex: '#0D0E12' },
  { name: 'Surface', variable: '--color-panel', hex: '#1A1B20' },
  { name: 'Primary', variable: '--color-primary', hex: '#8083FF' },
  { name: 'Live', variable: '--color-live', hex: '#D10030' },
  { name: 'Success', variable: '--color-success', hex: '#4EDEA3' },
  { name: 'Text', variable: '--color-text', hex: '#E3E2E7' },
];
const sections = [
  {
    title: 'Explorar y buscar',
    description: 'Contenido destacado, categorías, filtros y canales.',
    to: '/',
    icon: 'explore' as const,
  },
  {
    title: 'Canal y reproducción',
    description: 'Escenario de emisión, metadatos y chat local.',
    to: '/watch/esports-championship',
    icon: 'play' as const,
  },
  {
    title: 'Identidad y acceso',
    description: 'Registro y sesión de demostración.',
    to: '/register',
    icon: 'shield' as const,
  },
  {
    title: 'Mi perfil',
    description: 'Presentación pública y edición de datos de ejemplo.',
    to: '/profile',
    icon: 'user' as const,
  },
  {
    title: 'Estudio de emisión',
    description: 'Monitor, metadatos y estados de la señal.',
    to: '/studio',
    icon: 'screen' as const,
  },
  {
    title: 'Prototipo interactivo',
    description: 'Recorridos conectados para explorar la base.',
    to: '/prototype',
    icon: 'link' as const,
  },
];
export function FoundationsPage() {
  return (
    <div className="stack">
      <Panel className={styles.hero}>
        <Badge tone="primary">DARK CINEMA BROADCAST</Badge>
        <h1>
          STREAMING
          <br />
          <span>Design system</span>
        </h1>
        <p>
          Una experiencia inmersiva donde el contenido está en primer plano y la comunidad siempre
          está cerca.
        </p>
        <ActionLink to="/design-system/components">
          Explorar componentes
          <Icon name="arrow" />
        </ActionLink>
      </Panel>
      <section>
        <SectionHeading eyebrow="00 · Índice" title="Una base, todos los recorridos" />
        <div className={styles.index}>
          {sections.map((section) => (
            <Link key={section.to} to={section.to}>
              <Panel>
                <Icon name={section.icon} size={28} />
                <h3>{section.title}</h3>
                <p>{section.description}</p>
                <Icon name="arrow" />
              </Panel>
            </Link>
          ))}
        </div>
      </section>
      <section>
        <SectionHeading eyebrow="01 · Fundamentos" title="Color con una función" />
        <div className={styles.swatches}>
          {tokens.map((token) => (
            <Panel key={token.variable}>
              <div
                className={styles.swatch}
                style={{ backgroundColor: `var(${token.variable})` }}
              />
              <h3>{token.name}</h3>
              <code>{token.hex}</code>
              <p>{token.variable}</p>
            </Panel>
          ))}
        </div>
      </section>
      <Panel className={styles.type}>
        <SectionHeading title="Dos voces, un lenguaje" />
        <div>
          <h2>Plus Jakarta Sans</h2>
          <p>Display y titulares · 600 / 700 / 800</p>
          <strong>En directo, contigo.</strong>
        </div>
        <div>
          <h3>Inter</h3>
          <p>Cuerpo, interfaces y chat · 400 / 500 / 600</p>
          <p className={styles.bodySample}>
            Cada detalle cuenta cuando la conversación está en vivo.
          </p>
        </div>
      </Panel>
      <div className={styles.rules}>
        <Panel>
          <h3>Espacio & forma</h3>
          <p>
            Ritmo de 4 y 8 px. Radios de 8, 12 y 16 px. Grillas fluidas, player 16:9 y chat
            independiente.
          </p>
        </Panel>
        <Panel>
          <h3>Accesibilidad desde la base</h3>
          <p>
            Controles de 44 px, foco visible, etiquetas explícitas, estados con texto y respeto por
            movimiento reducido.
          </p>
        </Panel>
        <Panel>
          <h3>Una sola fuente visual</h3>
          <p>
            Tokens CSS compartidos, componentes con variantes y estilos locales. Las vistas componen
            las mismas primitivas.
          </p>
        </Panel>
      </div>
    </div>
  );
}
