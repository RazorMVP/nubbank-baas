import { useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useAuth } from '@/auth/context';

export function AuthCallback() {
  const auth = useAuth();
  const navigate = useNavigate();
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let cancelled = false;
    auth
      .completeRedirectLogin()
      .then(() => {
        if (!cancelled) navigate('/', { replace: true });
      })
      .catch(() => {
        if (!cancelled) setFailed(true);
      });
    return () => {
      cancelled = true;
    };
  }, [auth, navigate]);

  if (failed) {
    return (
      <div className="grid min-h-screen place-items-center text-center">
        <div>
          <h2 className="text-lg font-semibold">Sign-in failed</h2>
          <p className="mt-1 text-sm text-muted">
            We couldn't complete sign-in.{' '}
            <Link to="/login" className="text-brand-primary underline">
              Try again
            </Link>
          </p>
        </div>
      </div>
    );
  }

  return (
    <div className="grid min-h-screen place-items-center text-muted">Signing you in…</div>
  );
}
