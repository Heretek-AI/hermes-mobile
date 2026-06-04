# hermes-agent-patches

Drop `*.patch` files here and HermesInstaller will `git apply` them after
each `git pull` of `NousResearch/hermes-agent`. Patches should be
unified-diff format (`git format-patch` or `git diff > foo.patch`) and
have a one-line summary as the first line of the commit message.

Patches are applied in lexical filename order. Keep them idempotent:
`git apply --check` should pass on a clean tree, and a second apply
should be a no-op. If a patch fails to apply (upstream drift),
`HermesInstaller.runHermesUpdate` will surface the error in the
`onInstallProgress` stream and the user can manually intervene.

Every patch should be PR'd upstream; this directory should be empty
most of the time. Mobile-specific workarounds for upstream issues
(like `faster-whisper` having no Android wheels — see
[[H.1 hermes-mobile-plan]]) live here.
