import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { closedReasonText, openingsFilled } from '../lib/jobTerms';
import PlacementJobsPage from '../pages/placement/PlacementJobsPage';
import StudentCareerPage from '../pages/student/StudentCareerPage';
import { expectAccessible, mockApi, renderPage, signedInAs, TENANT } from './helpers';

const STUDENT = '44444444-4444-4444-8444-444444444444';
const job = (over: Record<string, unknown> = {}) => ({
  id: 'job-1', tenantId: TENANT, postedBy: 'p', title: 'Analyst', employer: 'Example Co', description: 'd', status: 'OPEN', version: 1,
  minCgpa: null, minAttendancePct: null, applicationDeadline: null, allowedProgramIds: null, graduatingYear: null, packageLpa: null, location: null,
  openings: null, jobType: null, selectedCount: 0, closedReason: null, ...over,
});

describe('how a closed job is worded', () => {
  it('says why, and how many openings are filled', () => {
    expect(closedReasonText('DEADLINE_PASSED')).toBe('Closed: the application deadline has passed.');
    expect(closedReasonText('FULL')).toBe('Closed: every opening has been filled.');
    expect(closedReasonText('CLOSED')).toBe('Closed by the placement officer.');
    expect(closedReasonText(null)).toBeNull();
    expect(closedReasonText(undefined)).toBeNull();
    expect(openingsFilled({ openings: 4, selectedCount: 2 })).toBe('2 of 4 openings filled');
    expect(openingsFilled({ openings: 1, selectedCount: 0 })).toBe('0 of 1 opening filled');
    expect(openingsFilled({ openings: null, selectedCount: 3 })).toBeNull();
  });
});

describe('the student sees a closed job as closed', () => {
  const careerApi = (j: Record<string, unknown>) => mockApi({
    '/career/student-skills': [], '/career/skills': [], '/career/jobs': [j], '/career/placement-records': [], '/career/applications': [],
    '/career/applications/eligibility': { eligible: true, missingOrInsufficientSkillIds: [], unmetRules: [] },
  });

  it.each([['DEADLINE_PASSED', 'Closed: the application deadline has passed.'], ['FULL', 'Closed: every opening has been filled.']])(
    'a job closed by %s offers no Apply and says so',
    async (reason, text) => {
      signedInAs('STUDENT', { studentId: STUDENT });
      careerApi(job({ closedReason: reason }));
      const { container } = renderPage(<StudentCareerPage />);

      expect(await screen.findByText(text)).toBeTruthy();
      expect(screen.queryByRole('button', { name: 'Apply' })).toBeNull();
      expect(screen.queryByText('You meet the requirements for this role.')).toBeNull();
      await expectAccessible(container);
    },
  );

  it('a job still taking applications offers Apply as before', async () => {
    signedInAs('STUDENT', { studentId: STUDENT });
    careerApi(job());
    renderPage(<StudentCareerPage />);

    expect(await screen.findByRole('button', { name: 'Apply' })).toBeTruthy();
  });
});

describe('the officer sees what is filled and what is closed', () => {
  it('shows each job\'s openings and why a job that is still marked open has closed', async () => {
    signedInAs('PLACEMENT_OFFICER');
    mockApi({
      '/career/jobs': [job({ openings: 2, selectedCount: 2, closedReason: 'FULL' }), job({ id: 'job-2', title: 'Trainee', openings: 5, selectedCount: 1 })],
      '/career/skills': [], '/catalog/programs': [], '/directory/students': { content: [], page: 0, size: 500, totalElements: 0, totalPages: 0 },
      '/career/applications': [], '/career/job-skills': [],
    });
    renderPage(<PlacementJobsPage />);

    expect(await screen.findByText('2 of 2 openings filled')).toBeTruthy();
    expect(screen.getByText('1 of 5 openings filled')).toBeTruthy();
    expect(screen.getByText('Closed: every opening has been filled.')).toBeTruthy();
    expect(screen.getAllByText(/^Closed:/)).toHaveLength(1);
  });
});
