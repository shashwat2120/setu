import { screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import PlacementStatisticsPage from '../pages/placement/PlacementStatisticsPage';
import { expectAccessible, mockApi, renderPage, signedInAs, TENANT } from './helpers';

const CS = 'aaaaaaaa-0000-4000-8000-000000000001';
const ARTS = 'aaaaaaaa-0000-4000-8000-000000000002';

const stats = (over: Record<string, unknown> = {}) => ({
  placements: {
    activeStudents: 12, placedStudents: 4, placements: 5, placedInCohorts: 3, placedPercentage: 25,
    packages: { counted: 4, averageLpa: 7.5, highestLpa: 12, lowestLpa: 4.5 },
    byProgram: [{ programId: CS, students: 8, placed: 2, placedPercentage: 25 }, { programId: ARTS, students: 4, placed: 1, placedPercentage: 25 }],
    topRecruiters: [{ employer: 'Acme', placements: 3, averageLpa: 6 }, { employer: 'Globex', placements: 1, averageLpa: null }],
  },
  applications: { submitted: 6, shortlisted: 3, selected: 5, rejected: 2, withdrawn: 1 },
  jobs: { total: 9, open: 5, closedByOfficer: 1, deadlinePassed: 2, full: 1 },
  ...over,
});

function api(data: unknown) {
  mockApi({
    '/career/placement-stats': data as never,
    '/catalog/programs': [
      { id: CS, tenantId: TENANT, departmentId: 'd', code: 'CS', name: 'Computer Science', status: 'ACTIVE' },
      { id: ARTS, tenantId: TENANT, departmentId: 'd', code: 'AR', name: 'Arts', status: 'ACTIVE' },
    ],
  });
}

describe('the placement statistics page', () => {
  it('shows the headline figures, each program by name, the recruiters and the funnel', async () => {
    signedInAs('PLACEMENT_OFFICER');
    api(stats());
    const { container } = renderPage(<PlacementStatisticsPage />);

    const placed = await screen.findByText('Students placed');
    expect(placed.previousElementSibling?.textContent).toBe('4');
    expect(screen.getAllByText('25%').length).toBeGreaterThan(0);
    expect(screen.getByText('₹7.5 LPA')).toBeTruthy();
    expect(screen.getByText('₹12 LPA')).toBeTruthy();
    expect(screen.getByText('Package figures count the 4 of 5 placements that state a package.')).toBeTruthy();
    const rows = screen.getAllByRole('row');
    expect(within(rows.find((r) => /Computer Science/.test(r.textContent ?? '')) as HTMLElement).getByText('8')).toBeTruthy();
    expect(screen.getByText('Acme')).toBeTruthy();
    expect(screen.getByText('Waiting for review')).toBeTruthy();
    expect(screen.getByText('All openings filled')).toBeTruthy();
    await expectAccessible(container);
  });

  it('says "No students" and "Not recorded" rather than showing a zero it cannot stand behind', async () => {
    signedInAs('PLACEMENT_OFFICER');
    api(stats({
      placements: {
        activeStudents: 0, placedStudents: 0, placements: 0, placedInCohorts: 0, placedPercentage: null,
        packages: { counted: 0, averageLpa: null, highestLpa: null, lowestLpa: null }, byProgram: [], topRecruiters: [],
      },
    }));
    renderPage(<PlacementStatisticsPage />);

    expect(await screen.findByText('No students')).toBeTruthy();
    expect(screen.getAllByText('Not recorded')).toHaveLength(2);
    expect(screen.getByText('No students in a program yet')).toBeTruthy();
    expect(screen.getByText('No placements yet')).toBeTruthy();
    expect(screen.queryByText(/Package figures count/)).toBeNull();
  });
});
