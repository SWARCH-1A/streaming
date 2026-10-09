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
      { title: 'Elegir un directo', to: '/' },
    ],
  },
  {
    name: 'Nueva cuenta',
    description: 'Crea una cuenta y prueba tu presentación pública.',
    steps: [
      { title: 'Crear cuenta', to: '/register' },
      { title: 'Iniciar sesión', to: '/login' },
      { title: 'Editar perfil', to: '/profile' },
    ],
  },
  {
    name: 'Creador',
    description: 'Configura metadatos, conecta tu encoder y termina la emisión.',
    steps: [
      { title: 'Abrir estudio', to: '/studio' },
      { title: 'Ver mi perfil', to: '/profile' },
      { title: 'Editar mi canal', to: '/studio/channel' },
    ],
  },
  {
    name: 'Resiliencia',
    description: 'Consulta los criterios de resiliencia y las variantes visuales.',
    steps: [
      { title: 'Explorar directos', to: '/' },
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
        <p className="muted">
          Las rutas funcionales usan servicios reales. La biblioteca de componentes conserva
          ejemplos visuales.
        </p>
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
