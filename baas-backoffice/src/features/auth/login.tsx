import { useNavigate } from 'react-router-dom';
import { useAuth } from '@/auth/context';
import { Button } from '@/components/ui/button';

const STATS = [
  ['12', 'fintechs live'],
  ['15', 'banks live'],
  ['₦4.2bn', 'processed · 30d'],
  ['99.98%', 'uptime'],
];

export function Login() {
  const auth = useAuth();
  const navigate = useNavigate();

  async function onSignIn() {
    await auth.login();
    if (auth.isAuthenticated()) navigate('/', { replace: true });
  }

  return (
    <div className="grid min-h-screen grid-cols-1 lg:grid-cols-2">
      {/* Left: brand panel (slate ink) */}
      <div className="relative hidden flex-col justify-between overflow-hidden bg-surface-ink p-12 text-white lg:flex">
        <div className="flex items-center gap-2">
          <div className="grid h-9 w-9 place-items-center rounded-[var(--radius-control)] bg-brand-primary font-bold">
            N
          </div>
          <span className="text-lg font-semibold">NubBank</span>
        </div>
        <div>
          <h2 className="max-w-sm text-3xl font-semibold leading-tight">
            Banking infrastructure for builders.
          </h2>
          <p className="mt-3 max-w-sm text-white/60">
            Issue accounts, move money, and run your bank — on NubBank's rails.
          </p>
        </div>
        <div className="flex flex-wrap gap-x-8 gap-y-2 text-sm">
          {STATS.map(([n, l]) => (
            <span key={l} className="whitespace-nowrap">
              <span className="font-semibold text-brand-accent">{n}</span>{' '}
              <span className="text-white/60">{l}</span>
            </span>
          ))}
        </div>
      </div>

      {/* Right: sign-in */}
      <div className="flex items-center justify-center bg-surface p-12">
        <div className="w-full max-w-sm">
          <h1 className="text-2xl font-semibold">Sign in</h1>
          <p className="mt-1 text-sm text-muted">Use your NubBank operator account.</p>
          <Button className="mt-8 w-full" onClick={onSignIn}>
            Sign in
          </Button>
        </div>
      </div>
    </div>
  );
}
