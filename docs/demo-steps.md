# Setu demo: steps only

Password for every account: `SetuDemo#2025!`  ·  App: `http://localhost:5180`  ·  Left window = staff, right window = student.

## 1. SUPER ADMIN: `super.admin@demo.setu.local`
1. **Colleges** → Code `NIT`, Name `Nagpur Institute of Technology`, Timezone `Asia/Kolkata` → **Create college**
2. **Audit Log** (new entry on top) → **Roles**

## 2. COLLEGE ADMIN: `admin.sit@demo.setu.local`
1. **Departments** → Organization `School of Computing`, Code `DS`, Name `Data Science` → **Create department**
2. **Courses** → Department `Data Science`, Code `DS301`, Title `Machine Learning Basics`, Credits `4`, Type `Core`, Level `Undergraduate`, Description `Regression, classification and model evaluation with hands-on practice.` → **Create course**
3. **Sections** → Course `DS301`, Period `Semester 1 (SEM1)`, Section code `DS301-A`, Capacity `30`, registration dates empty → **Create section**
4. Row `DS301-A` → **Scheduling**
   - Meeting time: `Monday`, `10:00`, `11:00`, Room `BLK-A A101`, from `2026-10-01` → **Add meeting time**
   - Assign faculty: `Ananya Rao`, `PRIMARY`, `100`, `2026-10-01` → **Assign**
5. Row `DS301-A` → **Open registration** (status OPEN)

## 3. FACULTY: `faculty.rao@demo.setu.local` (pick `DS301-A` on every page)
1. **Courses** → module `Week 1: What is Machine Learning?` → **Add module** → **Lessons**
   - `A gentle introduction to machine learning`, `VIDEO`, `https://www.youtube.com/watch?v=Gv9_4yMHFhI` → **Add lesson**
   - `Fitting a line to data: linear regression`, `VIDEO`, `https://www.youtube.com/watch?v=PaFPbb66DxQ` → **Add lesson**
2. **Assignments** → **Create assignment**: `Linear Regression Problem Set`, `20`, due `2026-11-20 11:59 PM` → **Create** → **Publish**
3. **Assessments** → `Machine Learning Quiz 1`, `QUIZ`, `20`, starts `2026-10-13 09:00 AM`, ends `2026-12-31 06:00 PM` → **Create draft**
   - **Components** → `MAIN`, `20`, `100` → **Add component**
   - **Questions** (Add question for each):

     | Prompt | Type | Pts | Answer key |
     |---|---|---|---|
     | `Which type of machine learning learns from labelled examples to map inputs to outputs?  A) Unsupervised learning   B) Supervised learning   C) Reinforcement learning   D) Clustering` | `MCQ_SINGLE` | `8` | `{"correctOption":"B"}` |
     | `Linear regression is used to predict a continuous numeric value.` | `TRUE_FALSE` | `6` | `{"correctOption":"True"}` |
     | `In machine learning, the data used to check a trained model on unseen examples is called the ____ set (one word).` | `SHORT_ANSWER` | `6` | `{"correctAnswer":"test"}` |

   - **Schedule** → **Open** (OK on each prompt)
4. **Attendance** → `DS301-A` → **Open attendance session** (note Session ID and token)

## 4. STUDENT: `student.learning.risk@demo.setu.local`
1. **My Courses** → `DS301-A` → **Enroll**
2. Open **DS301** → first lesson → **Start** → **Mark complete**
3. **Attendance** → paste Session ID and token → **Check in**. Faculty: **Refresh records** → **Close session**
4. DS301 page → **Assignments** `ref://arjun-singh/linear-regression-solution.pdf` → **Submit**
5. **Assessments** → **Start attempt**: `B` → Save, `True` → Save, `test` → Save → **Submit attempt**

## 5. FACULTY: grade
1. **Assessments** → **Attempts** → **Evaluate** (score 20)
2. **Assignments** → **Submissions** → Marks `17`, Feedback `Good derivation of the cost function. Show the gradient step in more detail next time.` → **Grade**
3. **Grades** → `DS301-A`, `Machine Learning Quiz 1`, `MAIN` → Marks `20`, Grade `A`, Point `4.0` → **Publish**

## 6. COLLEGE ADMIN: risk
1. **Student Success** → Arjun Singh (MEDIUM 30) → **Recalculate** (becomes LOW)
2. **Details** on Meera Joshi (HIGH 90)

## 7. STUDENT: AI
1. `student.intervention@demo.setu.local` → **Success** → **AI Advisor**: `Why is my risk band high and what should I do first?`
2. Optional: `student.strong@demo.setu.local` → **Finance**

**Fixes:** backend down → restart backend tab on a fresh database · browser prompt → click OK · quiz won't start → Schedule, then Open.
