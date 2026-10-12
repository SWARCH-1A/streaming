import { Route, Routes } from 'react-router';

import { NotFoundPage } from '@/src/shell/routes/NotFoundPage';

import { ComponentsPage } from './ComponentsPage';
import { FoundationsPage } from './FoundationsPage';
import { PrototypePage } from './PrototypePage';

export default function DevelopmentPages() {
  return (
    <Routes>
      <Route path="design-system" element={<FoundationsPage />} />
      <Route path="design-system/components" element={<ComponentsPage />} />
      <Route path="prototype" element={<PrototypePage />} />
      <Route path="*" element={<NotFoundPage />} />
    </Routes>
  );
}
