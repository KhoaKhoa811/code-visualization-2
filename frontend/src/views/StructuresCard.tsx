// Part 19f: one card for the structures detected at the cursor (rules rank-2, specs/structure-
// detection.md). A Strong candidate that is the only Strong one of its subject is shown
// automatically; the user can hide it, or show a weaker candidate. Levels are fit to the recorded
// facts so far, never a judgment of the program. Choosing fields by hand stays available below.
import type { ReactNode } from 'react';
import { shortClass } from '../trace/format.ts';
import { RULES, type Detected } from '../trace/detect.ts';

export function StructuresCard({ detected, cursor, drawn, onHide, onShow, manual }: {
  detected: Detected[]; cursor: number; drawn: Set<string>;
  onHide: (key: string) => void; onShow: (key: string) => void;
  /** The confirm-by-hand editors (trees, heaps, graphs, lists, stacks, queues). */
  manual: ReactNode;
}) {
  const subjects = [...new Set(detected.map(d => d.subject))];
  return (
    <details className="rankings" data-testid="rankings" open>
      <summary>
        Structures <span className="muted">({RULES}, steps 1–{cursor}; fit to the recorded facts, not a judgment of the program)</span>
      </summary>
      {subjects.length === 0 && <p className="muted">No structure detected yet.</p>}
      {subjects.map(subject => (
        <div className="ranking-subject" key={subject}>
          <div className="ranking-name">{subject.startsWith('Main.') ? shortClass(subject) : subject}</div>
          {detected.filter(d => d.subject === subject).map(d => (
            <div className="ranking" key={d.key} data-testid={`ranking-${subject}-${d.view}`}>
              <span className={`rank-level ${d.level.toLowerCase()}`}>{d.level}</span>
              <span className="rank-view">{d.view}</span>
              {d.shape && (drawn.has(d.key)
                ? <button className="rank-action" data-testid={`hide-${d.key}`} onClick={() => onHide(d.key)}>{d.auto ? 'shown automatically · hide' : 'hide'}</button>
                : <button className="rank-action" data-testid={`show-${d.key}`} onClick={() => onShow(d.key)}>show</button>)}
              <span className="muted">{[...d.evidence, ...d.missing.map(m => `missing: ${m}`)].join('; ')}</span>
              {d.contradictions.length > 0 && <span className="warning"> against: {d.contradictions.join(', ')}</span>}
              {d.observation && <span className="rank-observation"> · {d.observation}</span>}
            </div>
          ))}
        </div>
      ))}
      <details className="manual-mappings" data-testid="manual-mappings">
        <summary>Choose fields yourself</summary>
        {manual}
      </details>
    </details>
  );
}
