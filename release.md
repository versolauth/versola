# Release

Read this before any release work. Add a line here whenever you learn something a release must do
or check (see "Added after incidents"). The deploy itself is in [`deploy.md`](deploy.md) section 3.

Claude does not publish the release, push tags or run `versola ...` commands (`.claude/settings.json`
denies or asks for them). The operator does; Claude asks for the output.

A published tag cannot be reused, so everything under "Before the release" must hold before Publish.
If one item fails, the release does not go out.

## Before the release

- [ ] Version: the tag has no leading `v` (`0.7.1`); a `-` in it makes a pre-release.
- [ ] CI on `main` is green for the release commit (the release run itself skips tests).
- [ ] No released migration was edited: `git diff --name-status <old>..<new> -- '*/postgres/migrations'`
      shows only `A` (manual until #616 makes CI fail on it).
- [ ] New migrations work with the previous version running (additive, see
      `.claude/rules/database-migrations.md`).
- [ ] A secret was added or changed: `SecretSchema.Revision` in `scripts/gen-env.scala` is raised.
- [ ] `release.json` lists compatibility for the new version (to be added once #823 lands).
- [ ] No critical or high scanner finding is open past its SLA in `SECURITY.md`.
- [ ] Release notes have a **Breaking / operator action** section, even if it says "none": rolling
      from which versions, which need a fresh database, new secrets, flags or ports, known gaps.
- [ ] Docs PRs in `versola-website` are ready, `about/changelog` updated.

## After the release

- [ ] Images exist under the tag: `versola-tools`, `versola-auth`, `versola-central`, `versola-edge`.
- [ ] Chart `appVersion` in `k8s/*/Chart.yaml` bumped (separate PR, only after the images exist).
- [ ] Stand check by the operator: fresh install, upgrade from the previous release, restart.
- [ ] Production upgrade is a separate step, never part of the release.

## Rollback

Migrations cannot be undone; the `pg_dump` taken right before `versola migrate` is the rollback.
Steps are in `deploy.md` section 3, "Rollback".

## Added after incidents

One line each: date, what happened, what to check now.

## Report

At the end say which items were checked, which were skipped and why, and the commands you ran.
Do not mark an item you did not verify.
