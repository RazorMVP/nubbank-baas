/** Engine permission codes (V2 migration). No ROLE_ prefix — matches
   @PreAuthorize("hasAuthority('CODE')"). */
export const PERMISSIONS = {
  READ_CUSTOMER: 'READ_CUSTOMER',
  CREATE_CUSTOMER: 'CREATE_CUSTOMER',
  UPDATE_CUSTOMER: 'UPDATE_CUSTOMER',
  READ_ACCOUNT: 'READ_ACCOUNT',
  CREATE_ACCOUNT: 'CREATE_ACCOUNT',
  DEPOSIT: 'DEPOSIT',
  WITHDRAW: 'WITHDRAW',
  READ_LOAN: 'READ_LOAN',
  CREATE_LOAN: 'CREATE_LOAN',
  APPROVE_LOAN: 'APPROVE_LOAN',
  DISBURSE_LOAN: 'DISBURSE_LOAN',
  INITIATE_PAYMENT: 'INITIATE_PAYMENT',
  RUN_REPORT: 'RUN_REPORT',
} as const;

export type PermissionCode = (typeof PERMISSIONS)[keyof typeof PERMISSIONS];

export function hasPermission(
  authorities: readonly string[],
  required: string | undefined,
): boolean {
  if (!required) return true;
  return authorities.includes(required);
}
