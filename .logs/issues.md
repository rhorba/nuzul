# Issue Log
<!-- Tracks bugs, errors, blockers with status -->
<!-- Format: ### [YYYY-MM-DD HH:MM] BUG/BLOCKER/ERROR — Title -->


### [2026-11-17] BUG — CTS push-gate blocked every Bash command
- Cause: push-gate.sh never inspected the command; hooks.json `if: Bash(git push*)` let compound commands through.
- Fix: rhorba/CTS branch fix/push-gate-command-filter (e7c1f8f, local, not pushed) + 2 tests; owner copied fixed script into installed plugin.
- Status: resolved locally; PR to CTS pending owner OK.
