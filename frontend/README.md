# frontend: React + TypeScript + Vite

The Setu web app: a public landing page plus a role-scoped dashboard for each of the six roles (super admin, college admin,
faculty, student, guardian, placement officer). React 19, TypeScript, Vite 8, React Router, GSAP for scroll motion, and
`@stomp/stompjs` for live attendance.

## Run it

```bash
npm install
npm run dev        # http://localhost:5180
```

The dev server proxies API calls to the backend on `http://127.0.0.1:8089` (override with `BACKEND_URL`). Start the backend first:
see the root [README](../README.md#quick-start-local).

| Script | What it does |
|---|---|
| `npm run dev` | Vite dev server with HMR |
| `npm run build` | `tsc -b` then a production build |
| `npm run lint` | Oxlint |
| `npm test` | Vitest in jsdom: component tests and the automated accessibility checks (axe-core, WCAG 2.2 AA) in `src/test`; no backend needed |

Before calling a change done, run `npx tsc -b && npx oxlint && npx vite build`, then open the page in a browser and check the console
and network tab for errors.

## Adding a backend route? Update the proxy

`vite.config.ts` has a `BACKEND_PREFIXES` list (`/auth`, `/learning`, `/staff-attendance`, `/ai`, ...). A request whose path does
not start with one of those is served by Vite itself, so a new controller prefix **must** be added there or the app will receive
the HTML shell instead of JSON.

## Layout

```
src/
├── api/          apiFetch (token refresh, errors), useApiQuery, types.ts mirroring backend responses
├── auth/         AuthContext, role routing, step-up prompts
├── components/
│   ├── ui/       shared tables, badges, empty/error states, page headers, confirm buttons
│   ├── learning/ LessonContent: embedded YouTube player and attached documents for a lesson
│   └── staff/    faculty attendance widgets (clock card, week strip, requests)
├── lib/          small pure helpers: lessonContent (YouTube ids), passwordRules, staffTime, staffCalendar
├── pages/        one folder per role: admin/, faculty/, student/, guardian/, placement/, superadmin/
└── styles/       tokens.css (design tokens) and app.css
```

## Design system

Setu uses one visual language, the "ledger": deep ink navy, warm paper, a brass accent, Fraunces for display, Public Sans for body
text and IBM Plex Mono for data. Use the tokens in `src/styles/tokens.css` (`--accent`, `--verified`, `--flag`, `--ink`, ...) and the
shared `Reveal` and `Figure` components. Do not introduce a second look for new screens.

Small conventions that are easy to get wrong:

- Secondary buttons are `btn btn-ghost`; a bare `btn` renders grey.
- Content shown to people is real: no lorem ipsum, and any example data is labelled as such.

## Things worth knowing

- **Links from the backend are rendered only if they are `https://`.** Lesson and resource references can also be internal
  `ref://` or `s3://` strings, which are shown as plain text. A YouTube link on a lesson is embedded through
  `youtube-nocookie.com` after the video id is validated (`lib/lessonContent.ts`).
- **Forgot password is an emailed 6-digit code.** The forgot-password page hands the email address to the reset page through
  router state (never the URL), and the reset page keeps only digits in the code box so a pasted "482 916" works. A wrong code and
  an unknown email get the same message.
- **The reset-password page shows the rules but the server decides.** `lib/passwordRules.ts` mirrors the backend password policy
  so people see problems early; very common passwords are rejected only by the server.
- **The advisor streams.** The student advisor page uses `POST /ai/chat/stream` (server-sent events), so the answer appears as it
  is produced.
