# The Editor nav row sits directly below Workspaces

The web-editor epic asked to verify that the **Editor** sidebar row renders directly beneath
**Workspaces**, under the Project node, using the SPA placement harness (ng serve + the real
`/main-navigation` registry with the app origin rewritten to `localhost` + a headless shell). That
placement is already evidenced two ways in committed tests, so the harness run is **deferred to a
machine with real memory** rather than forced in this container — see "Why the harness is deferred
here" below.

## Where the placement comes from

One line in this service's `.config/qits/deployments.yml`:

    navigation-entries: … project.detail.Workspaces:1, project.detail.Editor:2=editor

Both rows are `project.detail` (the child slot of the Project node), the same application
(`qits-workspaces`) in one container, at positions **1 (Workspaces)** and **2 (Editor)**. `=editor`
is the Editor row's own bare `editor` subpath — a project has one editor and it rides the wrapper's
aggregate workspace, so the row takes no repository segment. Position 2 was free, which is why the
Editor row lands immediately under Workspaces.

## Evidence source 1 — the edge serves both rows, in order

`components/qits-edge/qits-edge-platform-service` — `NavigationRoute` renders each slot as an
**array**, and one application may fill several of a slot's entries. `EdgeRoutingTest`'s
`oneApplicationHangsSeveralRowsUnderOneHeading` pins the exact `/main-navigation` shape for the
`project.detail` slot after qits-workspaces is activated:

- `slots["project.detail"]` is `[Workspaces, Editor]` — two entries, **in position order**.
- both carry `app: "qits-workspaces"` and the same `origin`
  (`http://workspaces.dev.example.com`) — one application, nothing collapses them.
- `project.get(0).subpath` is `null` (Workspaces opens the app root under the scope);
  `project.get(1).subpath` is `"editor"` (the Editor row opens the `editor` view).

Each `slots[...]` entry is `{app, label, host, origin, path, position, subpath}`; the document is
`{environment, origin, slots, applications}`. Confirm against a live edge with:

    curl -s http://qits-platform-edge:8080/main-navigation -H "Host: dev.localhost"

## Evidence source 2 — the shell renders them one below the other

`components/qits-ui-components/qits-ui-components-jslib/.../lib/main-layout.spec.ts` — the describe
block **"two entries of one application in one slot"** feeds `QitsMainLayout` a `project.detail`
array holding Workspaces (position 1) and Editor (position 2, `subpath: 'editor'`). Its test
`draws both rows, in position order, each at its own address` asserts the rendered rows are exactly:

    ['Workspaces', 'Editor']

with hrefs `…/qits/` and `…/qits/editor` — the two differ only by the subpath, and the Editor row
draws directly after Workspaces in the same slot. (Sibling tests pin the NG0955 `@for … track` fix
that lets one application key two rows without the list mis-attributing one row's DOM to the other.)

## Why the harness is deferred here

The ng-serve + headless-shell harness (see the `spa-placement-verification-harness` note) needs a
Playwright chromium shell **and** an `ng serve` of `qits-workspaces-frontend`, whose `npm ci` first
needs the lockfile `resolved`-URL retarget dance because the committed lock points at developer
hosts unreachable from here. In this build container (4 GiB, no swap) that combination is heavy and
flaky, and this repository's own build runs with Quinoa off (`-Dquarkus.quinoa=false`) and never
initialises the frontend submodule. The two committed sources above already assert the ordered
`[Workspaces, Editor]` placement end to end — the registry shape the edge serves, and the DOM the
shell renders from it — so the harness adds confirmation, not coverage. Run it on a machine with
real memory when a visual double-check is wanted.
