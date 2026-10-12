import { useApiQuery } from '../../api/useApiQuery';
import type { StudentResponse } from '../../api/types';

/** A student's name from their id. Shows a short id while loading, or if the student cannot be read. */
export function StudentName({ studentId }: { studentId: string }) {
  const student = useApiQuery<StudentResponse>(`/directory/students/${studentId}`);
  return <>{student.data?.fullName ?? student.data?.email ?? `${studentId.slice(0, 8)}…`}</>;
}
