import { index, layout, route, type RouteConfig } from '@react-router/dev/routes';

// Same URLs as the P1 SPA; every route is now rendered on the server first and hydrated in the browser.
export default [
  layout('routes/layout.tsx', [
    index('routes/home.tsx'),
    route('search', 'routes/search.tsx'),
    route('videos', 'routes/vods.tsx'),
    route('vod/:vodId', 'routes/vod.tsx'),
    route('watch/:streamId', 'routes/watch.tsx'),
    route('channels/:handle', 'routes/channel.tsx'),
    route('login', 'routes/login.tsx'),
    route('register', 'routes/register.tsx'),
    route('forgot-password', 'routes/forgot-password.tsx'),
    route('reset-password', 'routes/reset-password.tsx'),
    // Public on purpose: an invitation link must explain "sign in first" instead of redirecting and losing its fragment.
    route('watch-parties/join', 'routes/watch-parties-join.tsx'),
    layout('routes/require-session.tsx', [
      layout('routes/creator.tsx', [
        route('profile', 'routes/profile.tsx'),
        route('studio', 'routes/studio.tsx'),
        route('studio/channel', 'routes/studio-channel.tsx'),
        route('studio/vods', 'routes/studio-vods.tsx'),
      ]),
      route('notifications', 'routes/notifications.tsx'),
      route('watch-parties', 'routes/watch-parties.tsx'),
      route('watch-parties/:partyId', 'routes/watch-party.tsx'),
      route('admin', 'routes/admin.tsx'),
    ]),
    route('*', 'routes/not-found.tsx'),
  ]),
] satisfies RouteConfig;
