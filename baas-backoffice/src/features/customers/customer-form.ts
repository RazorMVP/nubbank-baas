import { z } from 'zod';

export const customerFormSchema = z.object({
  firstName: z.string().min(1, 'First name is required'),
  lastName: z.string().min(1, 'Last name is required'),
  email: z.string().email('Email must be valid').optional().or(z.literal('')),
  phone: z.string().optional().or(z.literal('')),
  dateOfBirth: z.string().optional().or(z.literal('')),
  gender: z.string().optional().or(z.literal('')),
  externalReference: z.string().optional().or(z.literal('')),
  bvn: z.string().optional().or(z.literal('')),
  nin: z.string().optional().or(z.literal('')),
});

export type CustomerFormValues = z.infer<typeof customerFormSchema>;
