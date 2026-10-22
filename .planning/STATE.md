# GSD Project State

**Active Milestone:** v2.7.0 (Bugfix - do not bump version)
**Current Phase:** Phase 1 - SMS Parser Fix, Generic Extraction, and Template Learning
**Status:** Complete
**Last Updated:** 2026-10-22 12:35:15

## Active Decisions & Constraints
- **Union Bank Parser Fix**: Make `for` optional in `UnionRule.TXN`, match full header/prefix `Union Bank of India A/c *`, extract payee using `Fvg:` before falling back to channel.
- **Generic Payee & Disclaimer Handling**: Generic heuristics across all banks for `Fvg:` / `to` payee extraction and disclaimer relaxing (`Not you? Call...`, `If not you...`, `Never share...`).
- **Inbox Review Smart Pre-filling**: Pre-fill Amount, Payee, and Money in/out direction toggle using safe, conservative heuristics in `InboxScreen`. Do not be overly optimistic.
- **Template Learner Generalization**: Capture time `HH:mm[:ss]` as a slot, support dual-direction slots (`Debited|Credited`), and relax trailing disclaimers so learned patterns match future alerts.
- **Identity & Git**: Personal repo identity `Yashwant00CR7` / `10a19yashwant@gmail.com` on `github-personal`. No global git config changes.
- **Backpressure**: `gradle testDebugUnitTest` and `gradle assembleDebug`.

## Current Position
- Phase: 01-parser-and-learning
- Total Plans: 1
- Completed Plans: 1
- Next Action: Ship / User Review
