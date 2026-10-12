import { Navigate, Route, Routes } from 'react-router-dom';
import App from './App';
import LoginPage from './pages/LoginPage';
import ForgotPasswordPage from './pages/ForgotPasswordPage';
import ResetPasswordPage from './pages/ResetPasswordPage';
import SecurityPage from './pages/SecurityPage';
import AppShell from './layouts/AppShell';
import { ProtectedRoute } from './auth/ProtectedRoute';
import { useAuth } from './auth/AuthContext';
import { ROLE_HOME } from './layouts/navConfig';
import StudentDashboard from './pages/student/StudentDashboard';
import StudentCoursesPage from './pages/student/StudentCoursesPage';
import StudentCourseDetailPage from './pages/student/StudentCourseDetailPage';
import StudentAttendancePage from './pages/student/StudentAttendancePage';
import StudentGradesPage from './pages/student/StudentGradesPage';
import StudentRiskSuccessPage from './pages/student/StudentSuccessPage';
import StudentCareerPage from './pages/student/StudentCareerPage';
import StudentFinancePage from './pages/student/StudentFinancePage';
import StudentAdvisorPage from './pages/student/StudentAdvisorPage';
import AdminDashboard from './pages/admin/AdminDashboard';
import FacultyDashboard from './pages/faculty/FacultyDashboard';
import PlacementDashboard from './pages/placement/PlacementDashboard';
import PlacementJobsPage from './pages/placement/PlacementJobsPage';
import PlacementSkillsPage from './pages/placement/PlacementSkillsPage';
import PlacementPlacementsPage from './pages/placement/PlacementPlacementsPage';
import GuardianDashboard from './pages/guardian/GuardianDashboard';
import GuardianStudentPage from './pages/guardian/GuardianStudentPage';
import SuperAdminDashboard from './pages/superadmin/SuperAdminDashboard';
import SuperAdminCollegesPage from './pages/superadmin/SuperAdminCollegesPage';
import SuperAdminAuditLogPage from './pages/superadmin/SuperAdminAuditLogPage';
import SuperAdminRolesPage from './pages/superadmin/SuperAdminRolesPage';
import StudentsPage from './pages/admin/StudentsPage';
import StudentDetailPage from './pages/admin/StudentDetailPage';
import FacultyPage from './pages/admin/FacultyPage';
import FacultyDetailPage from './pages/admin/FacultyDetailPage';
import CoursesPage from './pages/admin/CoursesPage';
import ProgramsPage from './pages/admin/ProgramsPage';
import SectionsPage from './pages/admin/SectionsPage';
import InstitutionPage from './pages/admin/InstitutionPage';
import DepartmentsPage from './pages/admin/DepartmentsPage';
import EnrollmentPage from './pages/admin/EnrollmentPage';
import AttendancePage from './pages/admin/AttendancePage';
import StaffAttendancePage from './pages/admin/StaffAttendancePage';
import AssessmentsPage from './pages/admin/AssessmentsPage';
import GradesPage from './pages/admin/GradesPage';
import FinancePage from './pages/admin/FinancePage';
import CareerPage from './pages/admin/CareerPage';
import StudentSuccessPage from './pages/admin/StudentSuccessPage';
import AuditLogPage from './pages/admin/AuditLogPage';
import FacultySectionsPage from './pages/faculty/FacultySectionsPage';
import FacultyStudentsPage from './pages/faculty/FacultyStudentsPage';
import FacultyAttendancePage from './pages/faculty/FacultyAttendancePage';
import FacultyMyAttendancePage from './pages/faculty/FacultyMyAttendancePage';
import FacultyCoursesPage from './pages/faculty/FacultyCoursesPage';
import FacultyAssignmentsPage from './pages/faculty/FacultyAssignmentsPage';
import FacultyAssessmentsPage from './pages/faculty/FacultyAssessmentsPage';
import FacultyGradesPage from './pages/faculty/FacultyGradesPage';
import FacultyStudentSuccessPage from './pages/faculty/FacultyStudentSuccessPage';

/** Lands an authenticated user on the dashboard matching their first role — never a client-side role choice. */
function AppIndexRedirect() {
  const { user } = useAuth();
  const firstRole = user?.roles[0]?.roleCode;
  return <Navigate to={firstRole ? ROLE_HOME[firstRole] : '/login'} replace />;
}

export default function AppRoutes() {
  return (
    <Routes>
      <Route path="/" element={<App />} />
      <Route path="/login" element={<LoginPage />} />
      <Route path="/forgot-password" element={<ForgotPasswordPage />} />
      <Route path="/reset-password" element={<ResetPasswordPage />} />

      <Route element={<ProtectedRoute />}>
        <Route path="/app" element={<AppShell />}>
          <Route index element={<AppIndexRedirect />} />
          <Route path="security" element={<SecurityPage />} />
          <Route element={<ProtectedRoute allow={['SUPER_ADMIN']} />}>
            <Route path="super-admin" element={<SuperAdminDashboard />} />
            <Route path="super-admin/colleges" element={<SuperAdminCollegesPage />} />
            <Route path="super-admin/audit-log" element={<SuperAdminAuditLogPage />} />
            <Route path="super-admin/roles" element={<SuperAdminRolesPage />} />
          </Route>
          <Route element={<ProtectedRoute allow={['COLLEGE_ADMIN']} />}>
            <Route path="admin" element={<AdminDashboard />} />
            <Route path="admin/students" element={<StudentsPage />} />
            <Route path="admin/students/:studentId" element={<StudentDetailPage />} />
            <Route path="admin/faculty" element={<FacultyPage />} />
            <Route path="admin/faculty/:facultyId" element={<FacultyDetailPage />} />
            <Route path="admin/courses" element={<CoursesPage />} />
            <Route path="admin/programs" element={<ProgramsPage />} />
            <Route path="admin/sections" element={<SectionsPage />} />
            <Route path="admin/institution" element={<InstitutionPage />} />
            <Route path="admin/departments" element={<DepartmentsPage />} />
            <Route path="admin/enrollment" element={<EnrollmentPage />} />
            <Route path="admin/attendance" element={<AttendancePage />} />
            <Route path="admin/staff-attendance" element={<StaffAttendancePage />} />
            <Route path="admin/assessments" element={<AssessmentsPage />} />
            <Route path="admin/grades" element={<GradesPage />} />
            <Route path="admin/finance" element={<FinancePage />} />
            <Route path="admin/career" element={<CareerPage />} />
            <Route path="admin/student-success" element={<StudentSuccessPage />} />
            <Route path="admin/audit-log" element={<AuditLogPage />} />
          </Route>
          <Route element={<ProtectedRoute allow={['FACULTY']} />}>
            <Route path="faculty" element={<FacultyDashboard />} />
            <Route path="faculty/sections" element={<FacultySectionsPage />} />
            <Route path="faculty/students" element={<FacultyStudentsPage />} />
            <Route path="faculty/attendance" element={<FacultyAttendancePage />} />
            <Route path="faculty/my-attendance" element={<FacultyMyAttendancePage />} />
            <Route path="faculty/courses" element={<FacultyCoursesPage />} />
            <Route path="faculty/assignments" element={<FacultyAssignmentsPage />} />
            <Route path="faculty/assessments" element={<FacultyAssessmentsPage />} />
            <Route path="faculty/grades" element={<FacultyGradesPage />} />
            <Route path="faculty/student-success" element={<FacultyStudentSuccessPage />} />
          </Route>
          <Route element={<ProtectedRoute allow={['STUDENT']} />}>
            <Route path="student" element={<StudentDashboard />} />
            <Route path="student/courses" element={<StudentCoursesPage />} />
            <Route path="student/courses/:enrollmentId" element={<StudentCourseDetailPage />} />
            <Route path="student/attendance" element={<StudentAttendancePage />} />
            <Route path="student/grades" element={<StudentGradesPage />} />
            <Route path="student/success" element={<StudentRiskSuccessPage />} />
            <Route path="student/career" element={<StudentCareerPage />} />
            <Route path="student/finance" element={<StudentFinancePage />} />
            <Route path="student/advisor" element={<StudentAdvisorPage />} />
          </Route>
          <Route element={<ProtectedRoute allow={['PLACEMENT_OFFICER']} />}>
            <Route path="placement" element={<PlacementDashboard />} />
            <Route path="placement/jobs" element={<PlacementJobsPage />} />
            <Route path="placement/skills" element={<PlacementSkillsPage />} />
            <Route path="placement/placements" element={<PlacementPlacementsPage />} />
          </Route>
          <Route element={<ProtectedRoute allow={['GUARDIAN']} />}>
            <Route path="guardian" element={<GuardianDashboard />} />
            <Route path="guardian/students/:studentId" element={<GuardianStudentPage />} />
          </Route>
        </Route>
      </Route>

      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
