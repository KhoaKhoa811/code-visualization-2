# Three-column layout (Part 23)

Status: approved by the user on 2026-10-25 ("Screen will split into 3 part. center will be the
visualization like array, tree, heap,.... right part is the same with code editor and terminal.
Left is the rest like variable, call stack, call tree, object, complexity,.... The target is to
make the action like arrow clearer. of course, the center is a bit bigger than other 2."), with a
bug report: "object visualization should show all the objecft". Frontend only.

## Columns

| Column | Width | Contents |
| --- | --- | --- |
| Side (left) | 1fr | run summary, layout bar, detected structures card (and manual mappings), variables / call stack, call tree, objects, complexity |
| Center | 1.4fr | playback controls and step text (sticky at the top), collections, nested trees, DP tables, trees, lists/stacks/queues, heaps, graphs, arrays, strings |
| Right | 1.15fr | editor over console, as before |

Each of the side and center columns scrolls on its own. Panels can still be reordered by dragging
within their column.

## Actions across columns

The Part 22 action overlay is drawn once over the whole layout, so an arrow can join a variable
or call-tree node at the side to a cell in the center. It is redrawn when any column scrolls or
the window resizes, and an end scrolled out of its column or panel draws nothing.

## Objects bug

The graph view (Part 14) styled `.graph`, the same class as the Objects panel, with
`display: block`, so the object columns stacked into one and their arrows ran off the panel's
edge. The graph view's rule is now `svg.graph`. Objects are laid out in depth layers from top to
bottom, wrapping within a layer, so every object fits the narrow side column; a reference to a
deeper object enters it at its top.

## Code and tests

- `frontend/src/App.tsx` (columns, overlay), `views/StatePanel.tsx` (`part: 'side' | 'center'`),
  `views/ActionOverlay.tsx` (scroll, clipping), `views/ObjectGraph.tsx` (layers), `styles.css`.
- Browser: "three columns ... (23)" on Tree sort: panels in their columns, center widest, all five
  objects inside the Objects panel with no horizontal scrolling.
