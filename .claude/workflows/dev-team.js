export const meta = {
  name: 'dev-team',
  description: 'architect → (backend + frontend coders in parallel) → reviewer (fix loop) → final-reviewer',
  whenToUse: 'A full-stack change you want designed, built by parallel backend+frontend coders, reviewed with fix loops, and signed off',
  phases: [
    { title: 'Architect', detail: 'design + publish the API/DB contract' },
    { title: 'Code', detail: 'backend + frontend coders build in parallel' },
    { title: 'Review', detail: 'review both diffs for bugs, security, migration safety' },
    { title: 'Fix', detail: 'coders address blockers' },
    { title: 'Final review', detail: 'go / no-go sign-off' },
  ],
}

const ARCHITECT = `You are the ARCHITECT on a full-stack app (React 19 + TS + Tailwind in multiship-react/, Spring Boot 4 + PostgreSQL in backend/). Read the relevant code first, then produce a DESIGN (no code) whose centrepiece is the CONTRACT both coders build against in parallel: exact endpoints (method+path, request/response JSON, status codes), DTO/TS types written out so FE and BE agree, and any DB migration (columns/types/intent). Then: backend files to touch, frontend files to touch, an ordered step plan, risks (tenant scoping, migration safety), and out-of-scope. Smallest change; match existing patterns.`
const BACKEND_CODER = `You are the BACKEND CODER — you own backend/ ONLY, never touch multiship-react/. Implement the backend part of the plan to the published contract (don't wait for the frontend). controller→service→repository, DTOs with @Valid, @Transactional at the service layer, constructor injection; schema changes go in a NEW Flyway migration (never edit an applied one; next free version; label_batch is LIST-partitioned by order_source, import rows in import_batch_row, prefer ADD COLUMN IF NOT EXISTS, no DROP/TRUNCATE/un-WHERE'd writes). Build with the JDK 26 wrapper, run relevant tests. Never print secrets. Report every file changed + build/test results.`
const FRONTEND_CODER = `You are the FRONTEND CODER — you own multiship-react/ ONLY, never touch backend/. Implement the frontend part of the plan to the published contract (don't wait for the backend — build the agreed request/response shape + TS types). Smallest diff, match existing component/hook/fetch/styling patterns, strict TypeScript (no any). Run npx tsc --noEmit and eslint in multiship-react/. Report every file changed + build/lint results.`
const REVIEWER = `You are the REVIEWER. Review BOTH the backend and frontend diffs against the plan — read the actual changed files. Priority: correctness bugs; contract agreement (do FE types and BE DTOs/endpoints match each other and the plan?); security (auth + tenant/client scoping — list/read paths often miss the scoping their write paths enforce; injection; secrets); migration & DB safety (new migration only, no version collision, partition-aware, ADD COLUMN safe, no destructive SQL, matches the entity); edge cases/tests; over-engineering. For each issue give file:line + failing scenario + severity, and say whether it's a FRONTEND or BACKEND fix.`
const FINAL = `You are the FINAL REVIEWER, the last gate. Confirm each remaining reviewer issue is actually resolved in the current code (read it), sanity-check both layers against the goal and that they build, then give a GO or NO-GO verdict with a one-line summary. Blockers and real risks only.`

const MAX_ROUNDS = 3

const REVIEW_SCHEMA = {
  type: 'object',
  properties: {
    hasBlockers: { type: 'boolean', description: 'true ONLY for must-fix correctness/security/migration issues' },
    backendIssues: { type: 'array', items: { type: 'string' }, description: 'blocker/should-fix issues in backend/' },
    frontendIssues: { type: 'array', items: { type: 'string' }, description: 'blocker/should-fix issues in multiship-react/' },
    summary: { type: 'string' },
  },
  required: ['hasBlockers', 'summary'],
}

const task = typeof args === 'string' ? args : (args && args.task)
if (!task) {
  throw new Error('Pass the task via args, e.g. Workflow({ name: "dev-team", args: "add a CSV export to the Clients page" })')
}

phase('Architect')
const plan = await agent(
  `${ARCHITECT}\n\nTASK:\n${task}`,
  { label: 'architect: plan + contract', phase: 'Architect' },
)

phase('Code')
let [backend, frontend] = await parallel([
  () => agent(`${BACKEND_CODER}\n\nTASK:\n${task}\n\nPLAN + CONTRACT:\n${plan}`,
    { label: 'backend coder', phase: 'Code' }),
  () => agent(`${FRONTEND_CODER}\n\nTASK:\n${task}\n\nPLAN + CONTRACT:\n${plan}`,
    { label: 'frontend coder', phase: 'Code' }),
])

let review = null
const rounds = []
for (let round = 1; round <= MAX_ROUNDS; round++) {
  phase('Review')
  review = await agent(
    `${REVIEWER}\n\nSet hasBlockers true ONLY for must-fix issues; nits aren't blockers.\n\n` +
    `TASK:\n${task}\n\nPLAN:\n${plan}\n\nBACKEND REPORT:\n${backend}\n\nFRONTEND REPORT:\n${frontend}`,
    { label: `reviewer: round ${round}`, phase: 'Review', schema: REVIEW_SCHEMA },
  )
  rounds.push({ round, review })

  if (!review || !review.hasBlockers) break
  if (round === MAX_ROUNDS) {
    log(`Still has blockers after ${MAX_ROUNDS} round(s) — handing to final review anyway.`)
    break
  }

  phase('Fix')
  const be = (review.backendIssues || []).join('\n')
  const fe = (review.frontendIssues || []).join('\n')
  const [b2, f2] = await parallel([
    () => be
      ? agent(`${BACKEND_CODER}\n\nThe reviewer found backend blockers. Fix exactly these, rebuild, report.\n\nTASK:\n${task}\n\nPLAN:\n${plan}\n\nBACKEND BLOCKERS:\n${be}`,
        { label: `backend fix r${round}`, phase: 'Fix' })
      : Promise.resolve(backend),
    () => fe
      ? agent(`${FRONTEND_CODER}\n\nThe reviewer found frontend blockers. Fix exactly these, re-run tsc/eslint, report.\n\nTASK:\n${task}\n\nPLAN:\n${plan}\n\nFRONTEND BLOCKERS:\n${fe}`,
        { label: `frontend fix r${round}`, phase: 'Fix' })
      : Promise.resolve(frontend),
  ])
  backend = b2
  frontend = f2
}

phase('Final review')
const verdict = await agent(
  `${FINAL}\n\nTASK:\n${task}\n\nPLAN:\n${plan}\n\nLAST REVIEW:\n${review ? review.summary : '(no review produced)'}`,
  { label: 'final: sign-off', phase: 'Final review' },
)

return { plan, backend, frontend, rounds, verdict }
