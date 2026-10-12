import arena from '@/public/images/explore-0.jpg';
import valeria from '@/public/images/explore-1.jpg';
import cockpit from '@/public/images/explore-2.jpg';
import pilot from '@/public/images/explore-3.jpg';
import artwork from '@/public/images/explore-4.jpg';
import artist from '@/public/images/explore-5.jpg';
import concert from '@/public/images/explore-6.jpg';
import dj from '@/public/images/explore-7.jpg';
import workstation from '@/public/images/explore-8.jpg';
import developer from '@/public/images/explore-9.jpg';
import chess from '@/public/images/explore-10.jpg';
import player from '@/public/images/explore-11.jpg';
import city from '@/public/images/explore-12.jpg';
import gamer from '@/public/images/explore-13.jpg';
import podcast from '@/public/images/explore-14.jpg';
import hosts from '@/public/images/explore-15.jpg';
import observatory from '@/public/images/explore-16.jpg';
import scientist from '@/public/images/explore-17.jpg';
import offlineAvatar from '@/public/images/explore-20.jpg';

import type { StreamCardData } from '@/src/components/organisms/StreamCard.types';
import type { DemoChannel } from '@/src/modules/discovery/discovery.types';
import { demoCatalog } from '@/src/modules/taxonomy/entry';

const category = (id: string) => {
  const value = demoCatalog.categories.find((item) => item.id === id);
  if (!value) throw new Error(`Categoría de fixture desconocida: ${id}`);
  return value;
};
const tags = (ids: string[]) =>
  ids.map((id) => {
    const value = demoCatalog.tags.find((item) => item.id === id);
    if (!value) throw new Error(`Etiqueta de fixture desconocida: ${id}`);
    return value;
  });

export const demoStreams: readonly StreamCardData[] = [
  {
    id: 'esports-championship',
    title: 'Gran Final del Torneo Regional de Esports — Cuartos de Final',
    image: arena,
    channel: { handle: 'valeria_tv', name: 'Valeria Silva', avatar: valeria },
    category: category('gaming'),
    tags: tags(['spanish', 'competitive']),
    viewers: 28450,
    startedAt: '2026-10-02T18:00:00Z',
    availability: 'PLAYABLE',
  },
  {
    id: 'night-landing',
    title: 'Aterrizaje nocturno con tormenta en Tokio',
    image: cockpit,
    channel: { handle: 'capitanaerox', name: 'CapitanAerox', avatar: pilot },
    category: category('gaming'),
    tags: tags(['spanish', 'casual']),
    viewers: 14200,
    startedAt: '2026-10-02T18:10:00Z',
    availability: 'PLAYABLE',
  },
  {
    id: 'digital-art',
    title: 'Diseñando el villano de un RPG en Blender',
    image: artwork,
    channel: { handle: 'camilastudio', name: 'CamilaStudio', avatar: artist },
    category: category('art'),
    tags: tags(['beginners']),
    viewers: 7800,
    startedAt: '2026-10-02T18:20:00Z',
    availability: 'PLAYABLE',
  },
  {
    id: 'berlin-live',
    title: 'Sesión electrónica en directo · Berlin vibes',
    image: concert,
    channel: { handle: 'klangmaster', name: 'KlangMaster_Live', avatar: dj },
    category: category('music'),
    tags: tags(['english']),
    viewers: 19600,
    startedAt: '2026-10-02T18:30:00Z',
    availability: 'PLAYABLE',
  },
  {
    id: 'rust-engine',
    title: 'Programación: creando un motor de juegos en Rust',
    image: workstation,
    channel: { handle: 'devmartin', name: 'DevMartin_Tech', avatar: developer },
    category: category('science'),
    tags: tags(['spanish', 'programming', 'educational']),
    viewers: 5400,
    startedAt: '2026-10-02T18:40:00Z',
    availability: 'PLAYABLE',
  },
  {
    id: 'rapid-chess',
    title: 'Partidas rápidas 3+0 contra grandes maestros',
    image: chess,
    channel: { handle: 'ajedreztactico', name: 'AjedrezTáctico', avatar: player },
    category: category('sports'),
    tags: tags(['competitive']),
    viewers: 11100,
    startedAt: '2026-10-02T18:50:00Z',
    availability: 'PLAYABLE',
  },
  {
    id: 'cyber-city',
    title: 'Explorando la nueva expansión secreta',
    image: city,
    channel: { handle: 'nexuszero', name: 'NexusZero', avatar: gamer },
    category: category('gaming'),
    tags: tags(['spanish', 'casual']),
    viewers: 34200,
    startedAt: '2026-10-02T19:00:00Z',
    availability: 'PLAYABLE',
  },
  {
    id: 'night-conversation',
    title: 'Debate nocturno: ¿hacia dónde va la IA?',
    image: podcast,
    channel: { handle: 'lamesaredonda', name: 'LaMesaRedonda', avatar: hosts },
    category: category('conversation'),
    tags: tags(['spanish', 'irl']),
    viewers: 8300,
    startedAt: '2026-10-02T19:10:00Z',
    availability: 'PLAYABLE',
  },
  {
    id: 'space-live',
    title: 'Transmisión en vivo del telescopio espacial',
    image: observatory,
    channel: { handle: 'cosmosendirecto', name: 'CosmosEnDirecto', avatar: scientist },
    category: category('science'),
    tags: tags(['educational']),
    viewers: 12900,
    startedAt: '2026-10-02T19:20:00Z',
    availability: 'PLAYABLE',
  },
];

export const demoChannels: readonly DemoChannel[] = [
  ...demoStreams.map((stream) => ({
    ...stream.channel,
    status: 'LIVE' as const,
    description: 'Un espacio para compartir lo que nos apasiona. Bienvenido a la comunidad.',
    stream,
  })),
  {
    handle: 'elena_algoritmos',
    name: 'Elena Algoritmos',
    avatar: offlineAvatar,
    status: 'OFFLINE',
    description: 'Programación, ciencia de datos y aprendizaje en comunidad.',
    stream: null,
  },
];
