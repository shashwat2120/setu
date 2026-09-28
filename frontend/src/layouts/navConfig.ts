import type { RoleCode } from '../auth/types';

export interface NavItem {
  to: string;
  label: string;
}

/** Navigation is role-driven for UX convenience only — hiding an item is never the authorization boundary. */
export const NAV_BY_ROLE: Record<RoleCode, NavItem[]> = {
  SUPER_ADMIN: [
    { to: '/app/super-admin', label: 'Dashboard' },
    { to: '/app/super-admin/colleges', label: 'Colleges' },
    { to: '/app/super-admin/audit-log', label: 'Audit Log' },
    { to: '/app/super-admin/roles', label: 'Roles' },
  ],
  COLLEGE_ADMIN: [
    { to: '/app/admin', label: 'Dashboard' },
    { to: '/app/admin/institution', label: 'Institution' },
    { to: '/app/admin/departments', label: 'Departments' },
    { to: '/app/admin/students', label: 'Students' },
    { to: '/app/admin/faculty', label: 'Faculty' },
    { to: '/app/admin/programs', label: 'Programs' },
    { to: '/app/admin/courses', label: 'Courses' },
    { to: '/app/admin/sections', label: 'Sections' },
    { to: '/app/admin/enrollment', label: 'Enrollment' },
    { to: '/app/admin/attendance', label: 'Attendance' },
    { to: '/app/admin/assessments', label: 'Assessments' },
    { to: '/app/admin/grades', label: 'Grades' },
    { to: '/app/admin/finance', label: 'Finance' },
    { to: '/app/admin/career', label: 'Career' },
    { to: '/app/admin/student-success', label: 'Student Success' },
  ],
  FACULTY: [
    { to: '/app/faculty', label: 'Dashboard' },
    { to: '/app/faculty/sections', label: 'My Sections' },
    { to: '/app/faculty/students', label: 'Students' },
    { to: '/app/faculty/attendance', label: 'Attendance' },
    { to: '/app/faculty/courses', label: 'Courses' },
    { to: '/app/faculty/assignments', label: 'Assignments' },
    { to: '/app/faculty/assessments', label: 'Assessments' },
    { to: '/app/faculty/grades', label: 'Grades' },
    { to: '/app/faculty/student-success', label: 'Student Success' },
  ],
  STUDENT: [
    { to: '/app/student', label: 'Dashboard' },
    { to: '/app/student/courses', label: 'My Courses' },
    { to: '/app/student/attendance', label: 'Attendance' },
    { to: '/app/student/grades', label: 'Grades' },
    { to: '/app/student/success', label: 'Student Success' },
    { to: '/app/student/career', label: 'Career' },
    { to: '/app/student/finance', label: 'Finance' },
    { to: '/app/student/advisor', label: 'AI Advisor' },
  ],
  PLACEMENT_OFFICER: [
    { to: '/app/placement', label: 'Dashboard' },
    { to: '/app/placement/jobs', label: 'Jobs' },
    { to: '/app/placement/skills', label: 'Skills' },
    { to: '/app/placement/placements', label: 'Placements' },
  ],
  GUARDIAN: [
    { to: '/app/guardian', label: 'Dashboard' },
  ],
};

export const ROLE_HOME: Record<RoleCode, string> = {
  SUPER_ADMIN: '/app/super-admin',
  COLLEGE_ADMIN: '/app/admin',
  FACULTY: '/app/faculty',
  STUDENT: '/app/student',
  PLACEMENT_OFFICER: '/app/placement',
  GUARDIAN: '/app/guardian',
};
