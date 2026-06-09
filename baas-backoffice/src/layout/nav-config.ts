import {
  LayoutDashboard, Users, Wallet, PiggyBank, Landmark, ArrowLeftRight,
  Banknote, Receipt, BookOpenCheck, FileBarChart, ShieldCheck, Building2,
  KeyRound, ScrollText, type LucideIcon,
} from 'lucide-react';
import { hasPermission } from '@/lib/rbac';

export interface NavItem {
  label: string;
  to: string;
  icon: LucideIcon;
  /** undefined → always visible (e.g. Dashboard). */
  requiredPermission?: string;
}
export interface NavGroup {
  title: string;
  items: NavItem[];
}

export const NAV_GROUPS: NavGroup[] = [
  {
    title: 'Overview',
    items: [{ label: 'Dashboard', to: '/', icon: LayoutDashboard }],
  },
  {
    title: 'Banking',
    items: [
      { label: 'Customers', to: '/customers', icon: Users, requiredPermission: 'READ_CUSTOMER' },
      { label: 'Accounts', to: '/accounts', icon: Wallet, requiredPermission: 'READ_ACCOUNT' },
      { label: 'Deposits', to: '/deposits', icon: PiggyBank, requiredPermission: 'READ_ACCOUNT' },
      { label: 'Loans', to: '/loans', icon: Landmark, requiredPermission: 'READ_LOAN' },
      { label: 'Payments', to: '/payments', icon: ArrowLeftRight, requiredPermission: 'INITIATE_PAYMENT' },
      { label: 'Teller / Cash', to: '/teller', icon: Banknote, requiredPermission: 'DEPOSIT' },
      { label: 'Charges', to: '/charges', icon: Receipt, requiredPermission: 'READ_ACCOUNT' },
    ],
  },
  {
    title: 'Finance',
    items: [
      { label: 'Accounting', to: '/accounting', icon: BookOpenCheck, requiredPermission: 'RUN_REPORT' },
      { label: 'Reports', to: '/reports', icon: FileBarChart, requiredPermission: 'RUN_REPORT' },
      { label: 'Compliance', to: '/compliance', icon: ShieldCheck, requiredPermission: 'RUN_REPORT' },
    ],
  },
  {
    title: 'Admin',
    items: [
      { label: 'Offices / Staff', to: '/offices', icon: Building2, requiredPermission: 'UPDATE_CUSTOMER' },
      { label: 'Roles', to: '/roles', icon: KeyRound, requiredPermission: 'UPDATE_CUSTOMER' },
      { label: 'Audit', to: '/audit', icon: ScrollText, requiredPermission: 'RUN_REPORT' },
    ],
  },
];

export function visibleNav(authorities: readonly string[]): NavGroup[] {
  return NAV_GROUPS.map((g) => ({
    ...g,
    items: g.items.filter((i) => hasPermission(authorities, i.requiredPermission)),
  })).filter((g) => g.items.length > 0);
}
