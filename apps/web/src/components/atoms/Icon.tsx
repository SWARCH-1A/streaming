import type { SVGProps } from 'react';

const paths = {
  broadcast: 'M2 9a10 10 0 0 1 20 0M5 10a7 7 0 0 1 14 0M8 11a4 4 0 0 1 8 0M12 10v11m-2-2h4',
  explore: 'm16 8-3 5-5 3 3-5 5-3M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20',
  search: 'M10.5 3a7.5 7.5 0 1 0 0 15 7.5 7.5 0 0 0 0-15M16 16l5 5',
  play: 'm8 4 12 8-12 8V4',
  pause: 'M7 4v16M17 4v16',
  chat: 'M3 3h18v14H8l-5 4V3M7 7h10M7 11h7',
  game: 'M8 7h8a4 4 0 0 1 4 3l2 7a3 3 0 0 1-5 2l-2-2H9l-2 2a3 3 0 0 1-5-2l2-7a4 4 0 0 1 4-3M7 10v5M4.5 12.5h5M16 11h.01M19 14h.01',
  music: 'M4 14V10a8 8 0 0 1 16 0v4M4 12H2v8h4v-8M20 12h2v8h-4v-8',
  art: 'M12 3a9 9 0 1 0 0 18h2a2 2 0 0 0 0-4 2 2 0 0 1 0-4h3a4 4 0 0 0 4-4c0-4-5-6-9-6M7 7h.01M12 6h.01M17 8h.01M6 12h.01',
  education: 'm2 9 10-5 10 5-10 5-10-5M6 11v6l6 3 6-3v-6M22 9v8',
  tech: 'M6 6h12v12H6V6M9 9h6v6H9V9M9 2v4M15 2v4M9 18v4M15 18v4M2 9h4M2 15h4M18 9h4M18 15h4',
  sport:
    'M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20m-4 6 4-2 4 2-1 5H9L8 8M9 13l-4 3M15 13l4 3M12 6V2M5 5l3 3M19 5l-3 3M9 13l-1 7M15 13l1 7',
  users:
    'M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2M9 3a4 4 0 1 0 0 8 4 4 0 0 0 0-8M17 4a4 4 0 0 1 0 8M22 21v-2a4 4 0 0 0-3-4',
  user: 'M12 3a4 4 0 1 0 0 8 4 4 0 0 0 0-8M4 21v-2a6 6 0 0 1 6-6h4a6 6 0 0 1 6 6v2H4',
  eye: 'M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12M12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6',
  volume: 'm3 9 5 0 5-5v16l-5-5H3V9M17 8a6 6 0 0 1 0 8M20 5a10 10 0 0 1 0 14',
  muted: 'm3 9 5 0 5-5v16l-5-5H3V9M17 9l5 6M22 9l-5 6',
  fullscreen: 'M3 8V3h5M16 3h5v5M21 16v5h-5M8 21H3v-5',
  screen: 'M3 3h18v14H3V3M8 21h8M12 17v4',
  chevron: 'm9 5 7 7-7 7',
  arrow: 'M4 12h16M14 6l6 6-6 6',
  close: 'm6 6 12 12M6 18 18 6',
  check: 'm5 12 4 4L19 6',
  menu: 'M3 6h18M3 12h18M3 18h18',
  grid: 'M3 3h7v7H3V3M14 3h7v7h-7V3M3 14h7v7H3v-7M14 14h7v7h-7v-7',
  settings:
    'M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8M12 2v3M12 19v3M2 12h3M19 12h3M5 5l2 2M17 17l2 2M5 19l2-2M17 7l2-2',
  key: 'M8 3a5 5 0 1 0 0 10 5 5 0 0 0 0-10M12 12l9 9M16 16l3-3M19 19l3-3',
  copy: 'M8 8h13v13H8V8M16 8V3H3v13h5',
  refresh: 'M3 10a9 9 0 0 1 16-5l2 3M21 2v6h-6M21 14a9 9 0 0 1-16 5l-2-3M3 22v-6h6',
  alert: 'm12 2 10 19H2L12 2M12 9v5M12 17h.01',
  info: 'M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20M12 11v6M12 7h.01',
  shield: 'm12 2 9 4v7c0 5-9 9-9 9s-9-4-9-9V6l9-4m-4 10 3 3 5-6',
  send: 'm2 3 20 9-20 9 4-9-4-9M6 12h16',
  link: 'm10 14 4-4M8 16l-2 2a4 4 0 0 1-6-6l5-5a4 4 0 0 1 6 0M16 8l2-2a4 4 0 0 1 6 6l-5 5a4 4 0 0 1-6 0',
  clock: 'M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20M12 6v6l4 3',
} as const;

export type IconName = keyof typeof paths;

interface IconProps extends SVGProps<SVGSVGElement> {
  name: IconName;
  size?: number;
}

export function Icon({ name, size = 20, ...props }: IconProps) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.7"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
      {...props}
    >
      <path d={paths[name]} />
    </svg>
  );
}
