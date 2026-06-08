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
    logout: async () => {
      await mgr.signoutRedirect();
      current = null;
    },
  };
}
