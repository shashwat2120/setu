# Setu demo: guardian and placement officer (steps only)

Password for every account: `SetuDemo#2025!`  ·  App: `http://localhost:5180`  ·  All values are example data.
Run this after the main four-role script. Everything below was rehearsed on a fresh seed.

## A. GUARDIAN: scoped, revocable access
1. `guardian.kapoor@demo.setu.local` → **Dashboard**: Ishaan Kapoor is ACTIVE with seven data categories; a second student's access is EXPIRED and leads nowhere.
2. **View student** (Ishaan Kapoor): read-only risk band, finance, schedule, attendance and grades.
3. Safety check: paste a student link the guardian has no grant for (for example Meera Joshi): "No active access".
4. Sign in as `admin.sit@demo.setu.local` → **Students** → Ishaan Kapoor → **Guardian access**:
   - Click **Revoke** (status REVOKED). The guardian's page now says "No active access" immediately.
   - **Grant access**: Linked guardian `Primary`, leave *Expires* empty, keep the ticked boxes (view attendance, view grades, view schedule) → **Grant access**.
   - The guardian sees only those categories: no finance, no risk score.
   - Say: "Grades and attendance are shown course by course, so they come with view schedule."
5. Optional: **Create and link guardian** on the same page: name `Meena Kapoor`, email `<inbox you can open>`, phone `+91 90000 00000`, date of birth `1975-03-02`, relationship `Mother`.

## B. PLACEMENT OFFICER: jobs, skills, applications, outcomes
1. `placement.officer@demo.setu.local` → **Dashboard** (2 open jobs, 3 skills, 1 placement).
2. **Skills** → *New skill*: Code `PYTHON`, Name `Python for Data Science`, Category `Software Engineering` → **Add skill**.
3. **Jobs** → *Post a job*: Title `Machine Learning Engineer Trainee`, Employer `Nimbus AI Labs`, Description `Build and evaluate regression and classification models on real campus and industry datasets. Training provided.` → **Post job**.
4. On that job → **Required skills**: Skill `Python for Data Science`, Level `INTERMEDIATE` → **Add**.
5. (Optional, to show the graduating-batch rule: as the placement officer post a job with **Graduating batch: Batch of 2027**, then as `student.strong` (Ishaan, the final-year student, batch of 2027) Apply is offered, and as `student.average` (batch of 2028) it is refused with "yours is 2028".)
   `student.strong@demo.setu.local` → **Career**: the job says "Missing 1 required skill(s)". Skills → `Python for Data Science`, `INTERMEDIATE` → **Add skill** → it now says "You meet the skill requirements" → **Apply** (status SUBMITTED).
6. Back as the placement officer → **Jobs** → the job → **Applications** → **Shortlist** → **Select**.
7. **Placements** → *Record a placement*: search `Ishaan`, pick the student, Job `Machine Learning Engineer Trainee`, Employer `Nimbus AI Labs`, Role `Machine Learning Engineer Trainee`, CTC `900000`, Placed on `2026-12-15` → **Record placement**.
8. The student's **Career** page shows the new placement record.

**Say:** "Matching is deterministic: a student meets a role only when their level is at or above the required level, so it is explainable."
