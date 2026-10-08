import { ActionLink } from '@/src/components/atoms/ActionLink';
import { EmptyState } from '@/src/components/molecules/EmptyState';

export function NotFoundPage() {
  return (
    <div className="stack">
      <h1>Página no encontrada</h1>
      <EmptyState
        title="Este enlace no está disponible"
        description="Vuelve a explorar para encontrar canales y transmisiones."
        action={<ActionLink to="/">Volver a explorar</ActionLink>}
      />
    </div>
  );
}
