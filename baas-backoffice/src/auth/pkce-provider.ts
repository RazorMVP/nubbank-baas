import { UserManager, type UserManagerSettings, type User } from 'oidc-client-ts';
import type { AuthProvider, AuthUser } from './types';

/** Interim authorities source (F5): the engine resolves authorities server-side
   and does NOT mandate them in the JWT. If the Keycloak realm is configured to
   map them in, read them here; otherwise [] (everything 403-gated) until the
   backend `/baas/v1/operators/me` endpoint lands. */
export function parseAuthorities(claims: Record<string, unknown>): string[] {
  const flat = claims['authorities'];
  if (Array.isArray(flat)) return flat.filter((x): x is string => typeof x === 'string');
  const realm = claims['realm_access'] as { roles?: unknown } | undefined;
  if (realm && Array.isArray(realm.roles)) {
    return realm.roles.filter((x): x is string => typeof x === 'string');
  }
  return [];
}

export interface PkceConfig {
  authority: string; // Keycloak realm issuer URL
  clientId: string;
  redirectUri: string;
}

export function createPkceAuthProvider(config: PkceConfig): AuthProvider {
  const settings: UserManagerSettings = {
    authority: config.authority,
    client_id: config.clientId,
    redirect_uri: config.redirectUri,
    response_type: 'code',
    scope: 'openid profile email',
    automaticSilentRenew: true,
  };
  const mgr = new UserManager(settings);
  let current: User | null = null;

  // Keep `current` authoritative across the lifecycle: oidc-client-ts renews
  // tokens in the background (automaticSilentRenew) and emits these events.
  // Without subscribing, `current` would go stale after a valid silent renew
  // and isAuthenticated()/getUser() would wrongly report logged-out.
  mgr.events.addUserLoaded((u) => {
    current = u;
  });
  mgr.events.addUserSignedOut(() => {
    current = null;
  });

  // Fire-and-forget warm-up: `current` is null until this resolves, so
  // consumers must treat auth as async on first paint (use a loading state),
  // not read isAuthenticated() synchronously on mount expecting a warm cache.
  void mgr.getUser().then((u) => {
    current = u;
  });

  const toUser = (u: User | null): AuthUser | null =>
    u
      ? {
          sub: String(u.profile.sub),
          name: String(u.profile.name ?? u.profile.preferred_username ?? ''),
          email: String(u.profile.email ?? ''),
        }
      : null;

  return {
    isAuthenticated: () => current !== null && !current.expired,
    getUser: () => toUser(current),
    getAuthorities: () =>
      current ? parseAuthorities(current.profile as Record<string, unknown>) : [],
    getToken: async () => {
      current = await mgr.getUser();
      return current && !current.expired ? current.access_token : null;
    },
    login: async () => {
      await mgr.signinRedirect();
    },
    completeRedirectLogin: async () => {
      // Finish the authorization-code exchange on the redirect_uri route.
      // addUserLoaded also fires and sets `current`; we set it here too so the
      // session is established synchronously by the time this resolves.
      current = await mgr.signinRedirectCallback();
    },
    logout: async () => {
      // Clear local state BEFORE the redirect — signoutRedirect navigates away,
      // so any code after the await typically never runs.
      current = null;
      await mgr.signoutRedirect();
    },
  };
}
