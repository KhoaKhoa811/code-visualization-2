// Part 15b: every method's estimated growth side by side, slowest growth last, with what this run
// measured. The estimate comes from the code's shape (best effort, not a proof); the counts are
// facts of the whole recorded run.
import { shown, type Row } from '../trace/complexity.ts';
import { Panel } from './Draggable.tsx';

export function ComplexityPanel({ rows }: { rows: Row[] }) {
  if (rows.length === 0) return null;
  return (
    <Panel id="complexity" label="Complexity" title="Complexity" testId="complexity">
      <p className="muted">Estimated from the code's shape, in one size n (best effort, not a proof; ≈ marks a guess).
        Calls and steps were measured on this whole run.</p>
      <table className="complexity-table">
        <thead><tr><th>method</th><th>estimate</th><th>calls</th><th>steps</th></tr></thead>
        <tbody>
          {rows.map(({ estimate, measured }, i) => (
            <tr key={estimate.methodId} data-testid="complexity-row" data-method={estimate.name} data-rank={i}>
              <td className="name" title={estimate.methodId}>{estimate.name}</td>
              <td>
                <details>
                  <summary className={`complexity-bound${estimate.approximate ? ' approximate' : ''}`} data-testid="complexity-bound">{shown(estimate)}</summary>
                  <ul className="complexity-reasons">{estimate.reasons.map((r, k) => <li key={k}>{r}</li>)}
                    {estimate.reasons.length === 0 && <li>no loops, calls or recursion that grow with n</li>}</ul>
                </details>
              </td>
              <td className="num" data-testid="complexity-calls">{measured?.calls ?? '–'}</td>
              <td className="num" data-testid="complexity-steps" title={measured ? `${measured.own} in the method itself` : 'not called in this run'}>{measured?.steps ?? '–'}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </Panel>
  );
}
