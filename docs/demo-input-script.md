# Setu review demo: end-to-end script with every input

Everything here was rehearsed on a fresh seed (all values are example data). Password for every demo account: `SetuDemo#2025!`.
Use two browser windows: **left = staff**, **right = student**. App: `http://localhost:5180`.

The story: a college adds a new course, a student enrolls, learns from YouTube lessons, submits work, takes a quiz, gets graded,
and the risk score recalculates. Nothing is pre-made except the people and the college.

---

## 0. Opening (landing page) — 1 min
Say: "Colleges keep records, learning and early warnings in separate tools. Setu puts the student records system (SIS), the learning
platform (LMS) and an explainable risk engine into one system. Four roles: super admin, college admin, faculty, student."

## 1. SUPER ADMIN — `super.admin@demo.setu.local` — 2 min
1. **Colleges** → *Provision a new college*:
   - Code: `NIT`
   - Name: `Nagpur Institute of Technology`
   - Timezone: `Asia/Kolkata`
   - Click **Create college**.
2. **Audit Log** → the college-creation entry is at the top. Say: "Every action is logged, append-only."
3. **Roles** → say: "Role changes need a passkey re-check, so this demo environment correctly refuses instead of bypassing."

## 2. COLLEGE ADMIN — `admin.sit@demo.setu.local` (Rohan Kulkarni) — 5 min
Sign out, sign in as the college admin. Build a new course from nothing:

1. **Departments** → *Create department*:
   - Academic organization: `School of Computing`
   - Department code: `DS`
   - Department name: `Data Science`
2. **Courses** → *Create course*:
   - Department: `Data Science`
   - Course code: `DS301`
   - Title: `Machine Learning Basics`
   - Credits: `4`
   - Course type: `Core`
   - Level: `Undergraduate`
   - Description: `Regression, classification and model evaluation with hands-on practice.`
3. **Sections** → *Create section*:
   - Course: `DS301 · Machine Learning Basics`
   - Academic period: `Semester 1 (SEM1)`
   - Section code: `DS301-A`
   - Capacity: `30`
   - Leave *Registration opens / closes* **empty** (it uses the period's window).
     Optional talking point: type `2027-01-31` as the close date and show the rule
     "registration window must fall within the academic period's own registration window".
4. In the sections list the new row is **DRAFT**. Click **Scheduling** on `DS301-A`:
   - *Add meeting time*: Day `Monday`, Start `10:00`, End `11:00`, Room `BLK-A A101`, Effective from `2026-10-01` → **Add meeting time**.
   - *Assign faculty*: Faculty `Ananya Rao`, Role `PRIMARY`, Allocation % `100`, Starts `2026-10-01` → **Assign**.
5. Click **Open registration** on `DS301-A` (status becomes **OPEN**).
   Say: "Capacity, schedule, room and faculty are all checked before students can enroll."
6. (Optional, shows the email) **Students** → *Add student*: Full name `Riya Deshmukh`, Email `<an inbox you can open>`,
   Phone `+91 90000 00000`, Date of birth `2004-05-14`, Admission No. `ADM-2026-101`, Roll No. `101` → **Admit student**.
   The student receives a 6-digit code to set their first password. (Check the inbox on screen.)

## 3. FACULTY — `faculty.rao@demo.setu.local` (Ananya Rao) — 5 min
Sign in on the left window. Pick the section **DS301 — Machine Learning Basics (DS301-A)** on each page.

1. **Courses** → *New module title*: `Week 1: What is Machine Learning?` → **Add module** → **Lessons**:
   - Lesson 1: title `A gentle introduction to machine learning`, type `VIDEO`,
     link `https://www.youtube.com/watch?v=Gv9_4yMHFhI` → **Add lesson**
   - Lesson 2: title `Fitting a line to data: linear regression`, type `VIDEO`,
     link `https://www.youtube.com/watch?v=PaFPbb66DxQ` → **Add lesson**
   - Optional lesson 3: title `But what is a neural network?`, type `VIDEO`,
     link `https://www.youtube.com/watch?v=aircAruvnKk`
   (All three links were checked against YouTube.)
2. **Assignments** → **Create assignment**: title `Linear Regression Problem Set`, Max marks `20`, due date `2026-11-20 11:59 PM`
   → **Create assignment**, then **Publish**.
3. **Assessments** → *Create assessment*: Title `Machine Learning Quiz 1`, Type `QUIZ`, Total marks `20`,
   Starts `2026-10-13 09:00 AM` (any time in the past), Ends `2026-12-31 06:00 PM` → **Create draft**.
   - **Components** → Code `MAIN`, Max marks `20`, Weight % `100` → **Add component**.
   - **Questions** (Add question), the answer key is JSON and is never shown to students:

     | Prompt | Type | Points | Answer key (JSON) |
     |---|---|---|---|
     | `Which type of machine learning learns from labelled examples to map inputs to outputs?  A) Unsupervised learning   B) Supervised learning   C) Reinforcement learning   D) Clustering` | `MCQ_SINGLE` | `8` | `{"correctOption":"B"}` |
     | `Linear regression is used to predict a continuous numeric value.` | `TRUE_FALSE` | `6` | `{"correctOption":"True"}` |
     | `In machine learning, the data used to check a trained model on unseen examples is called the ____ set (one word).` | `SHORT_ANSWER` | `6` | `{"correctAnswer":"test"}` |

   - Click **Schedule**, then **Open** (accept the confirmation each time). Status goes DRAFT → SCHEDULED → OPEN.
4. **Attendance** → select `DS301-A` → **Open attendance session**. A session ID and a rotating token appear.
   Keep this window; the student scans from the right window in step 4.3.

## 4. STUDENT — `student.learning.risk@demo.setu.local` (Arjun Singh) — 5 min
Sign in on the right window.

1. **My Courses** → *Register for a section* → `DS301-A` → **Enroll**. Say: "Setu checks prerequisites, schedule conflicts,
   capacity, credit limits and holds." Result: "Enrollment confirmed."
2. Open **DS301 — Machine Learning Basics**. Expand the first lesson: the YouTube video plays inside the page.
   Click **Start**, then **Mark complete** (progress shows COMPLETED).
3. **Attendance** → *Check in to a session*: paste the **Session ID** and **Check-in token** from the faculty window → **Check in**
   → "Check-in recorded." (The token changes every minute; use **Rotate token** on the faculty side if it expires.)
   Back on the faculty window press **Refresh records** → the student shows PRESENT. Then **Close session**
   ("students who did not scan are marked absent automatically").
4. Back in the DS301 course page:
   - **Assignments** → type `ref://arjun-singh/linear-regression-solution.pdf` → **Submit**.
   - **Assessments** → **Start attempt** on `Machine Learning Quiz 1`:
     answer 1: `B` → Save answer; answer 2: choose `True` → Save answer; answer 3: `test` → Save answer → **Submit attempt**.

## 5. FACULTY again (left window) — 2 min
1. **Assessments** → **Attempts** on the quiz → **Evaluate**. Result: "Score 20: 3 question(s) graded automatically."
2. **Assignments** → **Submissions** → for Arjun Singh: Marks `17`, Feedback
   `Good derivation of the cost function. Show the gradient step in more detail next time.` → **Grade**.
3. **Grades** → section `DS301-A`, assessment `Machine Learning Quiz 1`, component `MAIN` → Marks `20`, Grade `A`, Point `4.0`
   → **Publish**. Say: "Grades are versioned; a correction adds a new version and never overwrites."

## 6. COLLEGE ADMIN again → risk recalculation — 2 min
With the broker up, publishing the grade in step 5 has already queued a recalculation; the page may show the new band without a click. If the
Kafka client is still starting (it can take minutes on this machine), **Recalculate** does the same thing by hand.
1. **Student Success**: Arjun Singh is **MEDIUM 30** (before).
2. Click **Recalculate** on his row → "Risk recalculated for Arjun Singh: LOW (the band changed)." The score drops to about **17.5**.
   Say: "Risk is a versioned rule over attendance, grades, assignments and learning progress. Same inputs, same score. AI never decides it."
3. Click **Details** on **Meera Joshi** (HIGH 90): five ranked factors, an accepted urgent advisor meeting, an intervention in progress.

## 7. STUDENT: AI explanation — 2 min
Sign in as `student.intervention@demo.setu.local` (Meera Joshi, HIGH):
1. **Success**: band, factors, plain note "not a certainty about your future performance".
2. **AI Advisor** → type: `Why is my risk band high and what should I do first?`
   The answer cites her real data (0% attendance, F grade, upcoming assignment) and its source tool.
3. Optional: `student.strong@demo.setu.local` → **Finance**: 50,000 INR pending, **Pay**.

## 8. Close — 1 min
Show the architecture and workflow pictures. Say the honest limits yourself:
- The live attendance count is not pushed automatically in this local setup, so press **Refresh records**.
- Passkey step-up needs a real authenticator; Kafka events are published but not consumed yet; Prometheus and Grafana are planned.
- Guardian and placement dashboards are built but hidden in this review build.

---
### If something goes wrong
- Backend not answering: restart the `backend` terminal tab (needs a fresh database: `docker compose down -v && docker compose up -d` in `infra/docker`).
- A native confirmation does nothing: click **OK** in the browser prompt.
- Quiz can't be started: it must be **OPEN** (Schedule, then Open).
