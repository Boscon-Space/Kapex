# HAB-1 payload computer — session record

**Read this folder first in every session, and update it before the session
ends.** Context is flushed at ~1M tokens; this folder is the memory.

| File | Holds | Update when |
|---|---|---|
| `01-plan-and-architecture.md` | what we are building and how it fits together | the plan changes |
| `02-decisions.md` | every decision, with date, why, and what it rules out | a decision is made or reversed |
| `03-findings.md` | verified facts about hardware, OS, tools; and what is *not* verified | something is measured or disproved |
| `04-worklog.md` | dated log of what was done, what broke, what was fixed | every session, as work happens |
| `05-milestones.md` | ordered milestones with pass conditions and status | a milestone changes state |
| `06-open-questions.md` | things waiting on Aditya, unresolved bugs, risks | something is asked, answered, or found |
| `07-kotlin-on-the-balloon.md` | the story for Kotlin developers: idea, integration, what existed vs what we built, results, how to reuse it | every session, with the milestones |
| `pi5-header.html` | 40-pin header drawn with physical numbers, GPIO names, probe jumper and I²C pins highlighted | the bench wiring changes |

Bench check at the start of a session: `tools/run_all_probes.sh` (expects the
hardware state described at the end of `04-worklog.md`).

Long-form research behind the plan: `../../docs/35-pi5-kotlin-payload-computer.md`.
Project-wide working agreement: `../../CLAUDE.md`.

Conventions: every number carries units and its source (measured / computed /
datasheet / assumed). "Verified" means it ran on the board. Nothing is marked
done until it has been seen to pass, and a check that has never failed is not
yet a check.
