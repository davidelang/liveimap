# liveimap

Cloned from orchestration-example. Multi-agent orchestration host: launchers,
policies, Landlock helper, group-refresh shell, setup/update/fix tooling.

## Layout

- `/home/dlang/git/liveimap/` — branch `orchestration` (managing tree)
- `/home/dlang/git/liveimap/master/` — worktree on branch `master`
- `/home/dlang/git/liveimap/sandbox/` — plans, research, PRs, failure logs

## Quick start

```bash
source ./env
./setup_agent.sh my-feature
./run-grok-planner
```

Grok launchers confine the process with a custom sandbox profile (`extends=read-only`). Cursor launchers still wrap with `agent-landlock`.
