import { useEffect, useMemo, useRef, useState } from 'react';
import { REJECT_TEXT, useConsoleView, type ConsoleView } from './api/console.ts';
import { ApiError, ERROR_TEXT, type Snapshot } from './api/runs.ts';
import { useRun, type SubmittedRun } from './api/useRun.ts';
import { JavaEditor, type Badge, type Marker } from './editor/JavaEditor.tsx';
import { rows as complexityRows, shown as shownBound } from './trace/complexity.ts';
import { EXAMPLES } from './examples.ts';
import { describe } from './trace/format.ts';
import { Playback, TraceError, type State } from './trace/playback.ts';
import { Controls } from './views/Controls.tsx';
import { clearMotion, leavingFrames, motionFor, playMotion } from './views/motion.ts';
import { pointersAt } from './trace/pointers.ts';
import { callsAt } from './trace/calls.ts';
import { StatePanel } from './views/StatePanel.tsx';
import { LayoutContext } from './views/Draggable.tsx';
import { forNewRun, initialLayout } from './views/layout.ts';
import { TreeMappings } from './views/TreeMappings.tsx';
import { classFields, mappingProblem, type TreeMapping } from './trace/tree.ts';
import { HeapMappings } from './views/HeapMappings.tsx';
import { heapProblem, type HeapMapping } from './trace/heap.ts';
import { GraphMappings } from './views/GraphMappings.tsx';
import { graphProblem, type GraphMapping } from './trace/graph.ts';
import { StructMappings } from './views/StructMappings.tsx';
import { structProblem, type Confirmed, type StructMapping } from './trace/structures.ts';
import { assess } from './trace/rankings.ts';
import { SuggestedViews } from './views/SuggestedViews.tsx';

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
  // Part 10a: test input given with the run instead of the live console. The playback stays tied to
  // the input that run was given; changing the text afterwards only warns.
  const [useTestInput, setUseTestInput] = useState(false);
  const [testInput, setTestInput] = useState('');
  const inputChanged = run !== null && run.input !== null && (!useTestInput || testInput !== run.input);

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
  // Part 9c: arrays confirmed as heaps, also for the session and checked against each run.
  const [heapMappings, setHeapMappings] = useState<HeapMapping[]>([]);
  const fittingHeaps = useMemo(() => usable ? heapMappings.filter(m => heapProblem(m, usable.trace) === null) : [], [heapMappings, usable]);
  // Part 14a: matrices confirmed as graphs, for the session and checked against each run.
  const [graphMappings, setGraphMappings] = useState<GraphMapping[]>([]);
  const fittingGraphs = useMemo(() => usable ? graphMappings.filter(m => graphProblem(m, usable.trace) === null) : [], [graphMappings, usable]);
  // Part 10b: list, stack and queue views of user classes; each confirmation is a new revision tied to its source.
  const [structMappings, setStructMappings] = useState<Confirmed<StructMapping>[]>([]);
  const [notStructs, setNotStructs] = useState<string[]>([]);
  const fittingStructs = useMemo(() => structMappings.filter(m => structProblem(m, fields) === null), [structMappings, fields]);
  const steps = usable?.steps ?? 0;
  const view = useMemo(() => usable ? usable.at(cursor) : null, [usable, cursor]);
  // Part 10c: rankings from the facts up to the cursor only; confirmed mappings give the field roles.
  const assessments = useMemo(() => usable && view ? assess(usable, view.cursor, { trees: treeMappings, structs: structMappings }) : [],
    [usable, view, treeMappings, structMappings]);

  // Part 11a: play speed, and motion (on unless the system asks for reduced motion; remembered per browser).
  const [speed, setSpeed] = useState(1);
  const [motion, setMotion] = useState(() => {
    try {
      const saved = localStorage.getItem('codeviz.motion');
      if (saved !== null) return saved === 'on';
    } catch { /* storage unavailable: use the system preference */ }
    return !window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;
  });
  const changeMotion = (on: boolean) => {
    setMotion(on);
    if (!on) clearMotion();
    try { localStorage.setItem('codeviz.motion', on ? 'on' : 'off'); } catch { /* not remembered */ }
  };
  const stepTime = 900 / speed;
  // Index arrows slide only while motion is on.
  useEffect(() => { document.body.classList.toggle('motion-off', !motion); }, [motion]);
  // Part 11b: index arrows from the recorded accesses up to the cursor.
  const pointers = useMemo(() => usable && view ? pointersAt(usable, view.cursor, view.beforeEnd ?? view.state) : new Map(), [usable, view]);
  // Part 13: the calls made up to the cursor and how they relate.
  const calls = useMemo(() => usable && view ? callsAt(usable, view) : undefined, [usable, view]);

  useEffect(() => {
    if (!playing || !usable) return;
    if (cursor >= usable.steps) { setPlaying(false); return; }
    const timer = setTimeout(() => setCursor(c => c + 1), stepTime);
    return () => clearTimeout(timer);
  }, [playing, cursor, usable, stepTime]);

  // Part 12d: one step forward that ended frames shows them leaving the call stack. Derived while
  // rendering (from the view shown before), so the ghost is in the same commit as the new state.
  const [shown, setShown] = useState<{ view: typeof view; playback: unknown; leaving?: { frames: State['frames']; state: State } }>({ view: null, playback: null });
  if (shown.view !== view || shown.playback !== usable) {
    const stepped = motion && view && shown.view && shown.playback === usable && view.cursor - shown.view.cursor === 1;
    const was = shown.view && (shown.view.beforeEnd ?? shown.view.state);
    const frames = stepped && was ? leavingFrames(was, view.beforeEnd ?? view.state) : [];
    setShown({ view, playback: usable, leaving: frames.length && was ? { frames, state: was } : undefined });
  }

  // One step forward or back animates that step's own event; any other jump (restart, the slider,
  // a new run) only shows the new state.
  const previous = useRef<{ playback: unknown; view: typeof view }>({ playback: null, view: null });
  useEffect(() => {
    const before = previous.current;
    previous.current = { playback: usable, view };
    if (!motion || !view || !before.view || before.playback !== usable) return;
    const delta = view.cursor - before.view.cursor;
    const stateOf = (v: NonNullable<typeof view>) => v.beforeEnd ?? v.state;
    const motions = delta === 1 ? motionFor(view.event, stateOf(before.view), stateOf(view), true)
      : delta === -1 ? motionFor(before.view.event, stateOf(view), stateOf(before.view), false) : [];
    if (motions.length === 0) { clearMotion(); return; }
    // Two frames: React has committed, the editor has drawn the step's highlight.
    let second = 0;
    const first = requestAnimationFrame(() => { second = requestAnimationFrame(() => playMotion(motions, Math.min(650, stepTime * 0.7))); });
    return () => { cancelAnimationFrame(first); cancelAnimationFrame(second); };
  }, [view, usable, motion, stepTime]);

  const markers: Marker[] = useMemo(() => !trace || stale ? [] : trace.diagnostics
    .filter(d => d.source).map(d => ({ range: d.source!, message: `${d.code}: ${d.message}`, warning: d.phase === 'capture' })), [trace, stale]);
  // Part 15: estimates from the code's shape, shown while the source is the one that ran.
  const estimates = useMemo(() => !trace || stale ? [] : trace.complexity ?? [], [trace, stale]);
  const badges: Badge[] = useMemo(() => estimates.map(e => ({ at: e.source.start, text: `time ${shownBound(e)}`,
    title: [`**${shownBound(e)}** (estimated from the code's shape)`, ...e.reasons.map(r => `- ${r}`)].join('\n') })), [estimates]);
  const complexity = useMemo(() => complexityRows(usable, estimates), [usable, estimates]);

  const go = (next: number) => { setPlaying(false); setCursor(Math.max(0, Math.min(steps, next))); };

  return (
    <div className="app">
      <header className="toolbar">
        <h1>Java Visualizer</h1>
        {/* Shows the chosen example until the code is edited away from it. */}
        <select aria-label="Example" value={Object.keys(EXAMPLES).find(name => EXAMPLES[name] === source) ?? ''}
          onChange={e => { if (e.target.value) setSource(EXAMPLES[e.target.value]); }}>
          <option value="">Load example…</option>
          {Object.keys(EXAMPLES).map(name => <option key={name} value={name}>{name}</option>)}
        </select>
        <button className="primary" onClick={() => submit.mutate({ source, input: useTestInput ? testInput : null })} disabled={running}>Run</button>
        <button onClick={() => cancel.mutate()} disabled={!running || run === null}>Cancel</button>
        <span className="status" data-testid="run-status">
          {submit.isPending ? 'Submitting…' : snap && !finished ? `${PHASE_TEXT[snap.phase]}…` : ''}
        </span>
      </header>
      <main className="layout">
        <section className="visual">
          <Controls cursor={view?.cursor ?? 0} steps={steps} playing={playing} onCursor={go} onPlaying={setPlaying}
            speed={speed} onSpeed={setSpeed} motion={motion} onMotion={changeMotion} />
          <RunSummary snapshot={finished ? snap : undefined} playback={playback} stale={stale} given={run?.input ?? null} inputChanged={inputChanged} />
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
              <SuggestedViews assessments={assessments} cursor={view.cursor} />
              <TreeMappings fields={fields} mappings={treeMappings} dismissed={notTrees}
                onConfirm={m => setTreeMappings(list => [...list.filter(x => x.className !== m.className), m])}
                onRemove={c => setTreeMappings(list => list.filter(x => x.className !== c))}
                onDismiss={c => setNotTrees(list => [...list, c])} />
              {usable && <HeapMappings trace={usable.trace} mappings={heapMappings}
                onConfirm={(m, replacing) => setHeapMappings(list => [...list.filter(x => x.array !== m.array && x.array !== replacing), m])}
                onRemove={a => setHeapMappings(list => list.filter(x => x.array !== a))} />}
              {usable && <GraphMappings trace={usable.trace} state={view.beforeEnd ?? view.state} mappings={graphMappings}
                onConfirm={(m, replacing) => setGraphMappings(list => [...list.filter(x => x.array !== m.array && x.array !== replacing), m])}
                onRemove={a => setGraphMappings(list => list.filter(x => x.array !== a))} />}
              {usable && <StructMappings fields={fields} sourceSha256={usable.trace.source.sha256} mappings={structMappings} dismissed={notStructs}
                onConfirm={m => setStructMappings(list => {
                  const previous = list.find(x => x.className === m.className);
                  return [...list.filter(x => x.className !== m.className),
                    { ...m, revision: (previous?.revision ?? 0) + 1, confirmedFor: usable.trace.source.sha256 }];
                })}
                onRemove={c => setStructMappings(list => list.filter(x => x.className !== c))}
                onDismiss={c => setNotStructs(list => [...list, c])} />}
              <StatePanel view={view} trees={fittingTrees} heaps={fittingHeaps} graphs={fittingGraphs} structs={fittingStructs} pointers={pointers} leaving={shown.leaving} calls={calls} complexity={complexity} />
            </LayoutContext.Provider>
          )}
          {!snap && !submit.isPending && <p className="muted hint">Write Java in the editor and press Run.</p>}
        </section>
        <section className="editor">
          <JavaEditor value={source} onChange={setSource} readOnly={running} highlight={view?.event?.source ?? null} markers={markers} badges={badges} />
        </section>
        <section className="console" aria-label="Console">
          <h2>Console</h2>
          {submit.error && <p className="error">{errorText(submit.error)}</p>}
          {snapshot.error && <p className="error">{errorText(snapshot.error)}</p>}
          {cancel.error && <p className="error">{errorText(cancel.error)}</p>}
          <div className="test-input">
            <label><input type="checkbox" checked={useTestInput} disabled={running} onChange={e => setUseTestInput(e.target.checked)} /> Use test input</label>
            {useTestInput && (
              <>
                <textarea aria-label="Test input" value={testInput} disabled={running} rows={3} spellCheck={false}
                  placeholder="Standard input for the next run, exactly as typed (end of input after it)" onChange={e => setTestInput(e.target.value)} />
                <p className="muted">The program reads this text as standard input; no live input is asked for.</p>
              </>
            )}
          </div>
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
      {open && state.waiting === true
        ? <p className="waiting" data-testid="console-waiting">Waiting for input (the run stops after 30 s of waiting).</p>
        : <p className="muted">This program reads input. Losing this page while it runs cancels the run.</p>}
      {view.rejected && <p className="error" data-testid="console-reject">{REJECT_TEXT[view.rejected] ?? view.rejected}</p>}
    </div>
  );
}

/** {@code given}: the test input this run was given (Part 10a), shown with the hash the backend recorded. */
function RunSummary({ snapshot, playback, stale, given, inputChanged }: {
  snapshot?: Snapshot; playback: Playback | TraceError | null; stale: boolean; given: string | null; inputChanged: boolean;
}) {
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
      {snapshot.input.mode === 'provided' && (
        <div className="muted" data-testid="run-input">
          Input: test input, {new TextEncoder().encode(given ?? '').length} bytes (SHA-256 {snapshot.input.sha256?.slice(0, 12)}…)
        </div>
      )}
      {snapshot.input.mode === 'live' && snapshot.input.sha256 && given === null && (
        <div className="muted" data-testid="run-input">Input: live console (SHA-256 of the delivered input {snapshot.input.sha256.slice(0, 12)}…)</div>
      )}
      {inputChanged && <div className="warning" data-testid="input-changed">The test input changed after this run. This playback used the input it was given; run again to use the current input.</div>}
    </div>
  );
}
