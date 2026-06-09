export interface AuthUser {
  sub: string;
  name: string;
  email: string;
}

/** The single auth seam. Prod = PKCE; dev/CI/e2e = configured token. */
export interface AuthProvider {
  isAuthenticated(): boolean;
  getUser(): AuthUser | null;
  getAuthorities(): string[];
  getToken(): Promise<string | null>;
  login(): Promise<void>;
  /**
   * Complete a pending redirect login. Call this on the OAuth2 redirect_uri
   * route (`/auth/callback`): PKCE exchanges the `?code=&state=` for tokens;
   * the dev provider is a no-op. Without it the PKCE flow can start but never
   * finish, so prod login would never establish a session.
   */
  completeRedirectLogin(): Promise<void>;
  logout(): Promise<void>;
}
