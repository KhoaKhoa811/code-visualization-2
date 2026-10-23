// Part 18d: the variables that refer to a node, as tags on it (cur, prev, head). A tag pulses on the
// step that moved its variable here (a declaration or assignment of it). Field paths (head.next)
// are not variables and are left to the node's own label.
import type { View } from '../trace/playback.ts';
import { namesOf } from '../trace/format.ts';

export function VarTags({ view, objectId }: { view: View; objectId: string }) {
  const state = view.beforeEnd ?? view.state;
  const names = [...new Set(namesOf(state, objectId).filter(n => !n.includes('.') && !n.includes('[')))].slice(0, 3);
  if (names.length === 0) return null;
  const e = view.event;
  const moved = e?.kind === 'DECLARE' ? e.data.binding.name
    : e?.kind === 'ASSIGN' ? state.bindings.find(b => b.bindingId === e.data.bindingId)?.name : undefined;
  return (
    <span className="var-tags">
      {names.map(n => <span key={n} className={`var-tag${n === moved ? ' moved' : ''}`} data-testid={`var-tag-${n}`}>{n}</span>)}
    </span>
  );
}