// Suggested-view rankings at the cursor (Part 10c, rules rank-1 in specs/visualization-rules.md).
// It only orders suggestions: views are drawn after confirmation, and a level never switches,
// confirms or certifies a view. Invariant observations are shown apart from the level.
import { shortClass } from '../trace/format.ts';
import { RULES, type Assessment } from '../trace/rankings.ts';

export function SuggestedViews({ assessments, cursor }: { assessments: Assessment[]; cursor: number }) {
  if (assessments.length === 0) return null;
  const subjects = [...new Set(assessments.map(a => a.subject))];
  return (
    <details className="rankings" data-testid="rankings" open>
      <summary>
        Suggested views <span className="muted">({RULES}, steps 1–{cursor}; fit to the recorded facts, not a judgment of the program)</span>
      </summary>
      {subjects.map(subject => (
        <div className="ranking-subject" key={subject}>
          <div className="ranking-name">{subject.startsWith('Main.') ? shortClass(subject) : subject}</div>
          {assessments.filter(a => a.subject === subject).map(a => (
            <div className="ranking" key={a.view} data-testid={`ranking-${subject}-${a.view}`}>
              <span className={`rank-level ${a.level.toLowerCase()}`}>{a.level}</span>
              <span className="rank-view">{a.view}</span>
              <span className="muted">{[...a.evidence, ...a.missing.map(m => `missing: ${m}`)].join('; ')}</span>
              {a.contradictions.length > 0 && <span className="warning"> against: {a.contradictions.join(', ')}</span>}
              {a.observation && <span className="rank-observation"> · {a.observation}</span>}
            </div>
          ))}
        </div>
      ))}
    </details>
  );
}
