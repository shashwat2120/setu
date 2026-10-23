/**
 * Questions worth asking next, offered under an advisor answer as buttons. They are chosen from the records the answer was based on (the same tool names the evidence line
 * uses), never written by the model, so a suggestion can only be a question the advisor is able to answer from the student's own data; asking it runs the ordinary
 * question path, with the same checks.
 */
const BY_SOURCE: Record<string, string[]> = {
  getStudentAttendance: ['Which of my courses has the lowest attendance?', 'What does the college policy say about attendance?'],
  getAcademicProgress: ['Show my results for each assessment', 'What should I focus on to improve?'],
  getAssessmentResults: ['Which assessment pulled my average down?', 'What is my overall academic progress?'],
  getUpcomingDeadlines: ['Which deadline should I start with?', 'Recommend learning resources for my courses'],
  getSkillGap: ['Which skills should I work on first?', 'Recommend learning resources for my courses'],
  searchCollegePolicy: ['How is my own attendance?', 'What is my current academic progress?'],
  getTranscript: ['What is my GPA to date?', 'How far am I from completing my degree?'],
  getFeeStatus: ['What is my outstanding balance?', 'When is my next fee due?'],
  recommendLearningResources: ['What assignments or assessments are due soon?', 'How is my progress in my courses?'],
};

const normalise = (q: string) => q.trim().toLowerCase().replace(/[?.!\s]+$/g, '');

/** Up to {@code max} follow-up questions for an answer based on {@code sources}, leaving out any the student has already asked. */
export function followUpsFor(sources: readonly string[] | undefined, alreadyAsked: readonly string[], max = 3): string[] {
  const asked = new Set(alreadyAsked.map(normalise));
  const out: string[] = [];
  for (const source of sources ?? []) {
    for (const q of BY_SOURCE[source] ?? []) {
      if (out.length >= max) return out;
      if (!asked.has(normalise(q)) && !out.includes(q)) out.push(q);
    }
  }
  return out;
}
