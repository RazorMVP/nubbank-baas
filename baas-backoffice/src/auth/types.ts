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
  logout(): Promise<void>;
}
