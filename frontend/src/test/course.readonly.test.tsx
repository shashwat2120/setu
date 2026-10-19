import { screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { render } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import StudentCourseDetailPage from '../pages/student/StudentCourseDetailPage';
import { expectAccessible, mockApi, signedInAs, TENANT } from './helpers';

const ENROLLMENT = '77777777-7777-4777-8777-777777777777';
const SECTION = '66666666-6666-4666-8666-666666666666';
const COURSE = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';

function courseApi(status: 'ENROLLED' | 'COMPLETED') {
  mockApi({
    [`/enrollment/enrollments/${ENROLLMENT}`]: { id: ENROLLMENT, tenantId: TENANT, sectionId: SECTION, studentId: 's', status, requestedAt: null, enrolledAt: null, droppedAt: null, finalStatus: status === 'COMPLETED' ? 'B' : null },
    '/enrollment/course-sections/': { id: SECTION, tenantId: TENANT, courseId: COURSE, periodId: 'p', classId: null, sectionCode: 'CS101-A', capacity: 30, roomId: null, status: 'OPEN', registrationOpenAt: null, registrationCloseAt: null },
    '/catalog/courses/': { id: COURSE, tenantId: TENANT, departmentId: 'd', code: 'CS101', title: 'Introduction to Programming', credits: 4, courseType: 'CORE', level: null, description: null, status: 'ACTIVE' },
    '/learning/modules': [{ id: 'm1', tenantId: TENANT, sectionId: SECTION, title: 'Week 1', sequence: 1, status: 'ACTIVE' }],
    '/learning/lessons': [{ id: 'l1', moduleId: 'm1', title: 'Variables and types', type: 'VIDEO', contentRef: null, sequence: 1, durationMinutes: 10 }],
    '/learning/progress': { id: 'p1', lessonId: 'l1', studentId: 's', state: 'NOT_STARTED', percent: 0, startedAt: null, completedAt: null, lastPosition: null },
    '/learning/assignments': [{ id: 'a1', tenantId: TENANT, sectionId: SECTION, title: 'A1 - Problem Set', maxMarks: 100, dueAt: '2026-11-08T00:00:00Z', latePolicy: null, status: 'PUBLISHED' }],
    '/learning/submissions': [],
    '/assessment/assessments': [{ id: 'x1', tenantId: TENANT, sectionId: SECTION, type: 'EXAM', title: 'Final Exam', totalMarks: 100, durationMinutes: 90, startsAt: '2026-10-01T00:00:00Z', endsAt: '2026-12-01T00:00:00Z', status: 'OPEN' }],
    '/assessment/attempts': [],
  });
}

function renderCourse() {
  signedInAs('STUDENT');
  return render(
    <MemoryRouter initialEntries={[`/app/student/courses/${ENROLLMENT}`]}>
      <Routes>
        <Route path="/app/student/courses/:enrollmentId" element={<StudentCourseDetailPage />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('a course the student has finished', () => {
  it('stays readable, says it is complete with the final grade, and offers nothing that would only fail', async () => {
    courseApi('COMPLETED');
    renderCourse();

    expect((await screen.findByRole('status')).textContent).toContain('This course is complete (final grade B)');
    expect(await screen.findByText(/Variables and types/)).toBeTruthy();
    expect(await screen.findByText('A1 - Problem Set')).toBeTruthy();
    expect(await screen.findByText('Final Exam')).toBeTruthy();

    expect(screen.queryByRole('button', { name: 'Start' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Mark complete' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Submit' })).toBeNull();
    expect(screen.queryByLabelText('Submission reference')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Start attempt' })).toBeNull();
  });

  it('has no WCAG 2.2 AA violations an automated engine can find', async () => {
    courseApi('COMPLETED');
    const { container } = renderCourse();
    await screen.findByText('A1 - Problem Set');
    await expectAccessible(container);
  });
});

describe('a course the student is taking', () => {
  it('offers the actions: start a lesson, submit an assignment, start an attempt', async () => {
    courseApi('ENROLLED');
    renderCourse();

    expect(await screen.findByRole('button', { name: 'Start' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Mark complete' })).toBeTruthy();
    expect(await screen.findByLabelText('Submission reference')).toBeTruthy();
    expect(await screen.findByRole('button', { name: 'Start attempt' })).toBeTruthy();
    expect(screen.queryByText(/This course is complete/)).toBeNull();
  });

  it('has no WCAG 2.2 AA violations an automated engine can find', async () => {
    courseApi('ENROLLED');
    const { container } = renderCourse();
    await screen.findByRole('button', { name: 'Start attempt' });
    await expectAccessible(container);
  });
});
