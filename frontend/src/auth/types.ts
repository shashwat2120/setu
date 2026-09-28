export type RoleCode = 'SUPER_ADMIN' | 'COLLEGE_ADMIN' | 'FACULTY' | 'STUDENT' | 'PLACEMENT_OFFICER' | 'GUARDIAN';

export interface RoleGrant {
  roleCode: RoleCode;
  tenantId: string | null;
}

/** Mirrors CurrentUserController.MeResponse exactly — never invent fields the backend doesn't send. */
export interface CurrentUser {
  userId: string;
  email: string | null;
  personId: string | null;
  fullName: string | null;
  roles: RoleGrant[];
  studentId: string | null;
  facultyId: string | null;
  guardianId: string | null;
}

export function hasRole(user: CurrentUser | null, role: RoleCode): boolean {
  return user?.roles.some((r) => r.roleCode === role) ?? false;
}

/** The tenant a given role grant applies to — a user can in principle hold the same role in only one tenant in this dataset, but the shape supports more. */
export function tenantForRole(user: CurrentUser | null, role: RoleCode): string | null {
  return user?.roles.find((r) => r.roleCode === role)?.tenantId ?? null;
}
