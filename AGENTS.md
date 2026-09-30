# Repository rules for coding agents

## Design documents stay local (mandatory)

Never stage, commit, push, or include design, requirements, planning, technical-plan, TODO, UI prototype, review, or acceptance notes in a pull request. This includes `.ai-workflow/` and matching files in `docs/`. Keep working notes locally. Before every commit or push, inspect both the staged file list and the full branch diff against the target branch; remove any matching files from the branch history, not just the latest commit. `.gitignore` does not protect files already tracked.

Operational runbooks and credential-free configuration templates may be committed when needed. A design document is an exception only if the user explicitly names that file and authorizes publishing it.
