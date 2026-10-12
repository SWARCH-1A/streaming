import type { Catalog } from '@/src/modules/taxonomy/catalog.types';

export const demoCatalog = {
  categories: [
    { id: 'conversation', name: 'Conversación', icon: 'chat' },
    { id: 'gaming', name: 'Videojuegos', icon: 'game' },
    { id: 'music', name: 'Música', icon: 'music' },
    { id: 'art', name: 'Arte', icon: 'art' },
    { id: 'education', name: 'Educación', icon: 'education' },
    { id: 'science', name: 'Ciencia y tecnología', icon: 'tech' },
    { id: 'sports', name: 'Deportes', icon: 'sport' },
  ],
  tags: [
    { id: 'spanish', name: 'Español' },
    { id: 'english', name: 'Inglés' },
    { id: 'educational', name: 'Educativo' },
    { id: 'competitive', name: 'Competitivo' },
    { id: 'casual', name: 'Casual' },
    { id: 'beginners', name: 'Principiantes' },
    { id: 'programming', name: 'Programación' },
    { id: 'irl', name: 'IRL' },
  ],
} as const satisfies Catalog;
