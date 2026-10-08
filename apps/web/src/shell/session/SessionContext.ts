import { createContext } from 'react';

import type { SessionState } from './Session.types';

export const SessionContext = createContext<SessionState | null>(null);
