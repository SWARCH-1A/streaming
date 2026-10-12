import type { ChatMessage } from '@/src/modules/chat/chat.types';

export const demoMessages: readonly ChatMessage[] = [
  {
    id: '1',
    author: 'Valeria Silva',
    text: '¡Gracias por acompañarnos! Se viene la partida decisiva 🎮',
    time: '18:42',
    role: 'broadcaster',
  },
  {
    id: '2',
    author: 'NicoLive',
    text: '¡Qué puntería en esa última jugada! 🔥',
    time: '18:43',
    role: 'viewer',
  },
  {
    id: '3',
    author: 'SolarisBeats',
    text: 'Saludos desde Bogotá 👋',
    time: '18:43',
    role: 'viewer',
  },
  {
    id: '4',
    author: 'CamilaStudio',
    text: 'La iluminación del escenario está increíble.',
    time: '18:44',
    role: 'viewer',
  },
  {
    id: '5',
    author: 'DevMartin',
    text: 'Vamos equipo, todavía queda una ronda.',
    time: '18:44',
    role: 'viewer',
  },
];
