import { ActionLink } from '@/src/components/atoms/ActionLink';
import { Icon } from '@/src/components/atoms/Icon';
import { Panel } from '@/src/components/molecules/Panel';

import styles from './PrototypePage.module.css';

const flows = [
  {
    name: 'Visitante',
    description: 'Descubre directos, encuentra una comunidad y entra al canal.',
    steps: [
      { title: 'Explorar', to: '/' },
      { title: 'Buscar canales', to: '/search?q=valeria' },
      { title: 'Ver un directo', to: '/watch/esports-championship' },
    ],
  },
  {
    name: 'Nueva cuenta',
    description: 'Valida el registro de ejemplo y prueba tu presentación pública.',
    steps: [
      { title: 'Crear cuenta', to: '/register' },
      { title: 'Iniciar sesión', to: '/login' },
      { title: 'Editar perfil', to: '/profile' },
    ],
  },
  {
    name: 'Creador',
    description: 'Configura metadatos, explora la señal y termina la demostración.',
    steps: [
      { title: 'Abrir estudio', to: '/studio' },
      { title: 'Ver mi perfil', to: '/profile' },
      { title: 'Abrir canal', to: '/channels/valeria_tv' },
    ],
  },
  {
    name: 'Resiliencia',
    description: 'En el directo, prueba reconexión y fallo del chat por separado.',
    steps: [
      { title: 'Probar estados', to: '/watch/esports-championship' },
      { title: 'Ver variantes', to: '/design-system/components' },
    ],
  },
];
export function PrototypePage() {
  return (
    <div className="stack">
      <div>
        <p className="eyebrow">08 · Prototipo interactivo</p>
        <h1>Recorridos conectados</h1>
        <p className="muted">Explora cada flujo con datos de demostración.</p>
      </div>
      {flows.map((flow) => (
        <Panel key={flow.name} className={styles.flow}>
          <div>
            <h2>{flow.name}</h2>
            <p className="muted">{flow.description}</p>
          </div>
          <ol>
            {flow.steps.map((step, index) => (
              <li key={step.to}>
                <span>{index + 1}</span>
                <ActionLink to={step.to} variant="secondary">
                  {step.title}
                  <Icon name="arrow" size={16} />
                </ActionLink>
              </li>
            ))}
          </ol>
        </Panel>
      ))}
    </div>
  );
}
