export function humanizeStatus(status: string): string {
  return status.replaceAll('_', ' ');
}

export function formatDateTime(iso: string): string {
  return new Date(iso).toLocaleString('en-GB', { dateStyle: 'short', timeStyle: 'short' });
}
