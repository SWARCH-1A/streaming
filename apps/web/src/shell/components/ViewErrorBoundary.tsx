import type { ReactNode } from 'react';
import { Component } from 'react';

import { Button } from '@/src/components/atoms/Button';
import { EmptyState } from '@/src/components/molecules/EmptyState';

interface ErrorBoundaryProps {
  children: ReactNode;
  name: string;
}
export class ViewErrorBoundary extends Component<ErrorBoundaryProps, { failed: boolean }> {
  state = { failed: false };
  static getDerivedStateFromError() {
    return { failed: true };
  }
  render() {
    return this.state.failed ? (
      <EmptyState
        title={`${this.props.name} no está disponible`}
        description="Vuelve a intentar cargar esta vista."
        icon="alert"
        action={<Button onClick={() => this.setState({ failed: false })}>Reintentar</Button>}
      />
    ) : (
      this.props.children
    );
  }
}
