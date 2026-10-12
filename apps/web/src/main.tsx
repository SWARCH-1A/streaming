import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';

import { App } from '@/src/shell/App';

import '@fontsource-variable/inter';
import '@fontsource-variable/plus-jakarta-sans';
import '@/src/styles/global.css';

const root = document.getElementById('root');
if (!root) throw new Error('No se encontró el contenedor root');
createRoot(root).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
