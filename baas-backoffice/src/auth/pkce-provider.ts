import { UserManager, type UserManagerSettings, type User } from 'oidc-client-ts';
import type { AuthProvider, AuthUser } from './types';

/** Token-claim fallback: only used when /baas/v1/operators/me can't be reached. The engine
   resolves authorities server-side and does NOT mandate them in the JWT, so this is normally
   empty — the authoritative source is {@link fetchOperatorAuthorities}. */
export function parseAuthorities(claims: Record<string, unknown>): string[] {
  const flat = claims['authorities'];
  if (Array.isArray(flat)) return flat.filter((x): x is string => typeof x === 'string');
  const realm = claims['realm_access'] as { roles?: unknown } | undefined;
  if (realm && Array.isArray(realm.roles)) {
    return realm.roles.filter((x): x is string => typeof x === 'string');
  }
  return [];
}

/** Authoritative authorities source (DEF-1C-28): the operator's permission codes are resolved
   server-side and returned by GET /baas/v1/operators/me — they are NOT in the Keycloak token.
   Returns [] (caller falls back to token claims) on any non-OK response or transport error. */
export async function fetchOperatorAuthorities(
  apiBaseUrl: string,
  token: string,
  fetchFn: typeof fetch = fetch,
): Promise<string[]> {
  try {
    const res = await fetchFn(`${apiBaseUrl}/baas/v1/operators/me`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    if (!res.ok) return [];
    const body = (await res.json()) as { data?: { authorities?: unknown } };
    const authorities = body?.data?.authorities;
    return Array.isArray(authorities)
      ? authorities.filter((x): x is string => typeof x === 'string')
      : [];
  } catch {
    return [];
  }
}

export interface PkceConfig {
  authority: string; // Keycloak realm issuer URL
  clientId: string;
  redirectUri: string;
  apiBaseUrl: string; // engine base URL, for the /operators/me authorities lookup
  fetchFn?: typeof fetch; // injectable for tests
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
  let ready = false;
  // Cached server-resolved authorities (DEF-1C-28). getAuthorities() is synchronous (guards/nav
  // read it on render), so we refresh this cache on every session change rather than fetch inline.
  let authorities: string[] = [];

  async function refreshAuthorities() {
    const token = current && !current.expired ? current.access_token : null;
    if (!token) {
      authorities = [];
      return;
    }
    const fromMe = await fetchOperatorAuthorities(config.apiBaseUrl, token, config.fetchFn ?? fetch);
    // Prefer /me; fall back to token claims only if /me is unreachable (returns []).
    authorities = fromMe.length
      ? fromMe
      : parseAuthorities(current!.profile as Record<string, unknown>);
  }

  // Keep `current` authoritative across the lifecycle: oidc-client-ts renews
  // tokens in the background (automaticSilentRenew) and emits these events.
  // Without subscribing, `current` would go stale after a valid silent renew
  // and isAuthenticated()/getUser() would wrongly report logged-out.
  mgr.events.addUserLoaded((u) => {
    current = u;
    void refreshAuthorities();
  });
  mgr.events.addUserSignedOut(() => {
    current = null;
    authorities = [];
  });

  // Fire-and-forget warm-up: `current` is null until this resolves, so
  // consumers must treat auth as async on first paint (use a loading state),
  // not read isAuthenticated() synchronously on mount expecting a warm cache.
  void mgr
    .getUser()
    .then(async (u) => {
      current = u;
      await refreshAuthorities();
    })
    .finally(() => {
      ready = true;
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
    isReady: () => ready,
    getUser: () => toUser(current),
    getAuthorities: () => authorities,
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
      await refreshAuthorities();
    },
    logout: async () => {
      // Clear local state BEFORE the redirect — signoutRedirect navigates away,
      // so any code after the await typically never runs.
      current = null;
      authorities = [];
      await mgr.signoutRedirect();
    },
  };
}
