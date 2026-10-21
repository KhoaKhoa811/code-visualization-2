import { useEffect, useMemo, useState } from 'react';
import { REJECT_TEXT, useConsoleView, type ConsoleView } from './api/console.ts';
import { ApiError, ERROR_TEXT, type Snapshot } from './api/runs.ts';
import { useRun, type SubmittedRun } from './api/useRun.ts';
import { JavaEditor, type Marker } from './editor/JavaEditor.tsx';
import { EXAMPLES } from './examples.ts';
import { describe } from './trace/format.ts';
import { Playback, TraceError } from './trace/playback.ts';
import { Controls } from './views/Controls.tsx';
import { StatePanel } from './views/StatePanel.tsx';
import { LayoutContext } from './views/Draggable.tsx';
import { forNewRun, initialLayout } from './views/layout.ts';
import { TreeMappings } from './views/TreeMappings.tsx';
import { classFields, mappingProblem, type TreeMapping } from './trace/tree.ts';

const PHASE_TEXT: Record<Snapshot['phase'], string> = {
  queued: 'Queued', preparing: 'Preparing container', compiling: 'Compiling', executing: 'Running',
  stopping: 'Stopping', cleaning: 'Cleaning up', finished: 'Finished',
};

function errorText(error: unknown): string {
  if (error instanceof ApiError) return ERROR_TEXT[error.code] ?? `Request failed (${error.code}).`;
  return 'Unexpected error.';
}

export function App() {
  const [source, setSource] = useState(EXAMPLES['Bubble sort']);
  const { run, submit, snapshot, output, cancel } = useRun();
  const live = useConsoleView(run?.console ?? null);
  const snap = snapshot.data;
  const finished = snap?.phase === 'finished';
  const running = submit.isPending || (snap !== undefined && !finished && !snapshot.error);
  const trace = finished ? snap.trace : null;
  const stale = run !== null && source !== run.source;

  const playback = useMemo(() => {
    if (!trace) return null;
    try { return new Playback(trace); } catch (e) { return e instanceof TraceError ? e : new TraceError(String(e)); }
  }, [trace]);
  const [cursor, setCursor] = useState(0);
  const [playing, setPlaying] = useState(false);
  useEffect(() => { setCursor(0); setPlaying(false); }, [playback]);
  // Part 9a: layout is presentation only. Element offsets belong to one run's ids; panel order stays.
  const [layout, setLayout] = useState(initialLayout);
  useEffect(() => { setLayout(forNewRun); }, [playback]);
  const layoutApi = useMemo(() => ({ layout, update: setLayout }), [layout]);
  // Part 9b: confirmed tree mappings last for the page session and are checked against every run.
  const [treeMappings, setTreeMappings] = useState<TreeMapping[]>([]);
  const [notTrees, setNotTrees] = useState<string[]>([]);

  const usable = playback instanceof Playback && playback.available && !stale ? playback : null;
  const fields = useMemo(() => usable ? classFields(usable.trace) : new Map(), [usable]);
  const fittingTrees = useMemo(() => treeMappings.filter(m => mappingProblem(m, fields) === null), [treeMappings, fields]);
  const steps = usable?.steps ?? 0;
  const view = useMemo(() => usable ? usable.at(cursor) : null, [usable, cursor]);

  useEffect(() => {
    if (!playing || !usable) return;
    if (cursor >= usable.steps) { setPlaying(false); return; }
    const timer = setTimeout(() => setCursor(c => c + 1), 500);
    return () => clearTimeout(timer);
  }, [playing, cursor, usable]);

  const markers: Marker[] = useMemo(() => !trace || stale ? [] : trace.diagnostics
    .filter(d => d.source).map(d => ({ range: d.source!, message: `${d.code}: ${d.message}`, warning: d.phase === 'capture' })), [trace, stale]);

  const go = (next: number) => { setPlaying(false); setCursor(Math.max(0, Math.min(steps, next))); };

  return (
    <div className="app">
      <header className="toolbar">
        <h1>Java Visualizer</h1>
        <select aria-label="Example" value="" onChange={e => { if (e.target.value) setSource(EXAMPLES[e.target.value]); }}>
          <option value="">Load example…</option>
          {Object.keys(EXAMPLES).map(name => <option key={name} value={name}>{name}</option>)}
        </select>
        <button className="primary" onClick={() => submit.mutate(source)} disabled={running}>Run</button>
        <button onClick={() => cancel.mutate()} disabled={!running || run === null}>Cancel</button>
        <span className="status" data-testid="run-status">
          {submit.isPending ? 'Submitting…' : snap && !finished ? `${PHASE_TEXT[snap.phase]}…` : ''}
        </span>
      </header>
      <main className="layout">
        <section className="visual">
          <Controls cursor={view?.cursor ?? 0} steps={steps} playing={playing} onCursor={go} onPlaying={setPlaying} />
          <RunSummary snapshot={finished ? snap : undefined} playback={playback} stale={stale} />
          {view && (
            <div className={`step-detail${view.event?.kind === 'UNCAPTURED' ? ' uncaptured' : ''}`} data-testid="step-detail">
              {view.event ? <><b>Step {view.cursor}:</b> {describe(view.event, view.beforeEnd ?? view.state, usable?.trace.source.text)}</> : 'Initial state (before the first operation).'}
              {view.terminal && view.cursor > 0 && <span className="muted"> (end of recording)</span>}
            </div>
          )}
          {view && (
            <LayoutContext.Provider value={layoutApi}>
              <div className="layout-bar">
                <span className="muted">Drag panels by their title and diagram boxes anywhere; layout never changes the recording.</span>
                <button onClick={() => setLayout(initialLayout())}>Reset layout</button>
              </div>
              <TreeMappings fields={fields} mappings={treeMappings} dismissed={notTrees}
                onConfirm={m => setTreeMappings(list => [...list.filter(x => x.className !== m.className), m])}
                onRemove={c => setTreeMappings(list => list.filter(x => x.className !== c))}
                onDismiss={c => setNotTrees(list => [...list, c])} />
              <StatePanel view={view} trees={fittingTrees} />
            </LayoutContext.Provider>
          )}
          {!snap && !submit.isPending && <p className="muted hint">Write Java in the editor and press Run.</p>}
        </section>
        <section className="editor">
          <JavaEditor value={source} onChange={setSource} readOnly={running} highlight={view?.event?.source ?? null} markers={markers} />
        </section>
        <section className="console" aria-label="Console">
          <h2>Console</h2>
          {submit.error && <p className="error">{errorText(submit.error)}</p>}
          {snapshot.error && <p className="error">{errorText(snapshot.error)}</p>}
          {cancel.error && <p className="error">{errorText(cancel.error)}</p>}
          {!finished && live.transcript.length > 0 && (
            <pre data-testid="console-live">
              {live.transcript.map((piece, i) => <span key={i} className={piece.stream === 'stdout' ? undefined : piece.stream}>{piece.text}</span>)}
            </pre>
          )}
          {!finished && <ConsoleInput run={run} view={live} />}
          {output.data && (
            <pre data-testid="console-output">{output.data.stdout}<span className="stderr">{output.data.stderr}</span></pre>
          )}
          {finished && live.sent.length > 0 && (
            <p className="muted" data-testid="console-input">Input sent: {live.sent.map(line => JSON.stringify(line)).join(', ')}</p>
          )}
        </section>
      </main>
    </div>
  );
}

/** The input line of an interactive run: Enter sends the line plus LF, EOF closes input. */
function ConsoleInput({ run, view }: { run: SubmittedRun | null; view: ConsoleView }) {
  const [line, setLine] = useState('');
  const state = view.state;
  if (!run || !state?.interactive) return null;
  if (!state.controller) return <p className="muted">{REJECT_TEXT.NOT_CONTROLLER}</p>;
  const open = state.inputOpen && !view.closed;
  return (
    <div className="console-input">
      <form onSubmit={e => { e.preventDefault(); run.console.send(line); setLine(''); }}>
        <input aria-label="Console input" value={line} onChange={e => setLine(e.target.value)} disabled={!open}
          placeholder={open ? 'Type input and press Enter' : 'Input opens when the program runs'} autoComplete="off" spellCheck={false} />
        <button type="button" onClick={() => run.console.eof()} disabled={!open}>Send EOF</button>
      </form>
      <p className="muted">This program reads input. Losing this page while it runs cancels the run.</p>
      {view.rejected && <p className="error" data-testid="console-reject">{REJECT_TEXT[view.rejected] ?? view.rejected}</p>}
    </div>
  );
}

function RunSummary({ snapshot, playback, stale }: { snapshot?: Snapshot; playback: Playback | TraceError | null; stale: boolean }) {
  if (!snapshot) return null;
  const trace = snapshot.trace;
  return (
    <div className="summary" data-testid="run-summary">
      {trace ? (
        <>
          <div><span className={`pill ${trace.execution.status}`}>{trace.execution.status}</span> {trace.execution.reason}</div>
          <div><span className={`pill capture-${trace.capture.status}`}>capture {trace.capture.status}</span> {trace.capture.reason}</div>
          {trace.diagnostics.map((d, i) => <div key={i} className="diagnostic">{d.phase} · {d.code}: {d.message}</div>)}
        </>
      ) : <div className="error">No trace: the runner reported an infrastructure failure.</div>}
      {snapshot.infrastructureFailures.length > 0 && <div className="error">Infrastructure: {snapshot.infrastructureFailures.join(', ')}</div>}
      {playback instanceof TraceError && <div className="error">Trace rejected: {playback.message}</div>}
      {stale && <div className="warning">The source changed after this run. Run again to visualize the current text.</div>}
    </div>
  );
}
