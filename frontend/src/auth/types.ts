export type RoleCode = 'SUPER_ADMIN' | 'COLLEGE_ADMIN' | 'FACULTY' | 'STUDENT' | 'PLACEMENT_OFFICER' | 'GUARDIAN';

export interface RoleGrant {
  roleCode: RoleCode;
  tenantId: string | null;
}

/** Mirrors CurrentUserController.MeResponse (`GET /identity/me`) exactly: the account and its roles. */
export interface IdentityMe {
  userId: string;
  email: string | null;
  personId: string | null;
  fullName: string | null;
  roles: RoleGrant[];
}

/** Mirrors DirectoryMeController.ProfilesResponse (`GET /directory/me/profiles?tenantId=`): the caller's own profile ids in one college. */
export interface DirectoryProfiles {
  studentId: string | null;
  facultyId: string | null;
  guardianId: string | null;
}

/** The signed-in person as the app uses it: the identity service's account and roles plus the academic service's profile ids. */
export interface CurrentUser extends IdentityMe, DirectoryProfiles {}

export function hasRole(user: CurrentUser | null, role: RoleCode): boolean {
  return user?.roles.some((r) => r.roleCode === role) ?? false;
}

/** The tenant a given role grant applies to — a user can in principle hold the same role in only one tenant in this dataset, but the shape supports more. */
export function tenantForRole(user: CurrentUser | null, role: RoleCode): string | null {
  return user?.roles.find((r) => r.roleCode === role)?.tenantId ?? null;
}
