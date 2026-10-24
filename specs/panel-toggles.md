# Panel toggles (Part 24)

Status: approved by the user on 2026-10-25 ("You will add a toggle option for every component so
if I don't want to see them, I can close them"). Frontend only.

## Rule

- Every panel (call stack, call tree, complexity, collections, nested trees, DP tables, trees,
  lists/stacks/queues, heaps, graphs, objects, arrays, strings) has a ▾ button before its title.
  Pressing it closes the panel: only its title row stays, with ▸, which opens it again.
- A closed panel draws nothing, so actions aimed at it are not drawn either.
- Closing is presentation only, kept in the layout (`Layout.closed`): it survives stepping and
  new runs; "Reset layout" opens every panel.
- The structures card already folds (`details`); the editor and console are not panels.

## Code and tests

- `frontend/src/views/layout.ts` (`closed`, `toggleClosed`), `views/Draggable.tsx` (`Panel`),
  `styles.css`.
- Unit: `layout.test.ts` (close, reopen, kept for a new run, reset).
- Browser: "every panel closes to its title and opens again (24)" on Tree sort.
