/**
 * What an advisor answer was BASED ON, in words a student can check. The server reports which tools the model used to get the facts it stated (a tool is a
 * read of the student's own records, run under their own permissions); this turns each into a plain label, what it covers, and the page where the student can
 * see the same records for themselves. A tool the page does not know is shown as a generic record lookup, never as a raw function name.
 */
export interface Evidence {
  /** Stable key (the tool name), for React lists and tests. */
  key: string;
  label: string;
  /** What this source covers, so the answer is read for what it is (e.g. "across your enrolled sections"). */
  covers: string;
  /** Where the student can see the same records, or null when there is no page (policy documents). */
  to: string | null;
}

const KNOWN: Record<string, Omit<Evidence, 'key'>> = {
  getStudentAttendance: { label: 'Your attendance records', covers: 'sessions you were marked present, late or absent in, across your enrolled sections', to: '/app/student/attendance' },
  getAcademicProgress: { label: 'Your academic progress', covers: 'published grades and credits from your academic record', to: '/app/student/grades' },
  getAssessmentResults: { label: 'Your assessment results', covers: 'the latest published grade for each assessment component', to: '/app/student/grades' },
  getUpcomingDeadlines: { label: 'Your upcoming deadlines', covers: 'assignments and assessments still ahead, in your enrolled courses', to: '/app/student/courses' },
  getSkillGap: { label: 'Your skills compared with a job', covers: 'your skill profile against that job\'s requirements', to: '/app/student/career' },
  searchCollegePolicy: { label: 'College policy documents', covers: 'passages from your own college\'s policy documents', to: null },
  getTranscript: { label: 'Your academic transcript', covers: 'every course with its credits and grade point, your GPA to date and your degree progress', to: '/app/student/grades' },
  getFeeStatus: { label: 'Your fee charges', covers: 'each charge with its amount, due date and status, and what is still outstanding', to: '/app/student/finance' },
  recommendLearningResources: { label: 'Learning resources for your courses', covers: 'resources attached to the courses you are enrolled in', to: '/app/student/courses' },
};

/** One entry per distinct source, in the order the advisor used them. */
export function evidenceFor(sources: readonly string[] | undefined): Evidence[] {
  const seen = new Set<string>();
  const out: Evidence[] = [];
  for (const name of sources ?? []) {
    if (seen.has(name)) continue;
    seen.add(name);
    const known = KNOWN[name];
    out.push(known ? { key: name, ...known } : { key: name, label: 'Your records', covers: 'a lookup of your own records', to: null });
  }
  return out;
}
