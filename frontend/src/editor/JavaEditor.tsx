// Monaco Java editor. Loads only the editor core and Java syntax colouring from
// the local package (no CDN). Highlights are UTF-16 source ranges from the trace.
import * as monaco from 'monaco-editor/editor';
import 'monaco-editor/languages/definitions/java/register';
import EditorWorker from 'monaco-editor/editor/editor.worker?worker';
import { useEffect, useRef, useState } from 'react';
import type { SourceRange } from '../trace/types.ts';

self.MonacoEnvironment = { getWorker: () => new EditorWorker() };

export interface Marker { range: SourceRange | null; message: string; warning?: boolean }

/** Part 15a: a note after a source position (a method's complexity estimate), with a hover text. */
export interface Badge { at: number; text: string; title: string }

interface Props {
  value: string;
  onChange: (value: string) => void;
  readOnly: boolean;
  highlight: SourceRange | null;
  markers: Marker[];
  badges?: Badge[];
  /** Part 22: a jump into this step's line from the line before (a loop going round, a branch skipped). */
  jump?: { from: number; to: number; kind: 'back' | 'skip' } | null;
}

export function JavaEditor({ value, onChange, readOnly, highlight, markers, badges = [], jump = null }: Props) {
  const host = useRef<HTMLDivElement>(null);
  const editor = useRef<monaco.editor.IStandaloneCodeEditor | null>(null);
  const decorations = useRef<monaco.editor.IEditorDecorationsCollection | null>(null);
  const notes = useRef<monaco.editor.IEditorDecorationsCollection | null>(null);
  const [scroll, setScroll] = useState(0); // redraws the jump arrow when the code scrolls
  const change = useRef(onChange);
  change.current = onChange;

  useEffect(() => {
    const instance = monaco.editor.create(host.current!, {
      value, language: 'java', theme: 'vs-dark', automaticLayout: true, minimap: { enabled: false },
      fontSize: 14, scrollBeyondLastLine: false, tabSize: 4, insertSpaces: true,
      lineDecorationsWidth: 26, // Part 22: room for the jump arrow beside the line numbers
    });
    editor.current = instance;
    decorations.current = instance.createDecorationsCollection();
    notes.current = instance.createDecorationsCollection();
    const subscription = instance.onDidChangeModelContent(() => change.current(instance.getValue()));
    const scrolled = instance.onDidScrollChange(() => setScroll(s => s + 1));
    return () => { subscription.dispose(); scrolled.dispose(); instance.dispose(); };
    // The editor owns its text after creation; changes flow out through onChange.
  }, []);

  useEffect(() => { editor.current?.updateOptions({ readOnly }); }, [readOnly]);

  // Replace text only when it changes from outside (for example, loading an example).
  useEffect(() => {
    const instance = editor.current;
    if (instance && instance.getValue() !== value) instance.setValue(value);
  }, [value]);

  useEffect(() => {
    const instance = editor.current;
    const model = instance?.getModel();
    if (!instance || !model || !decorations.current) return;
    if (!highlight) { decorations.current.clear(); return; }
    const range = monaco.Range.fromPositions(model.getPositionAt(highlight.start), model.getPositionAt(highlight.end));
    decorations.current.set([{ range, options: { className: 'step-highlight', inlineClassName: 'step-highlight-text' } }]);
    instance.revealRangeInCenterIfOutsideViewport(range);
    // Horizontally, show code from the line start when the highlight fits from there; otherwise
    // from just before the highlight. A long earlier step must not leave the view scrolled right.
    const oneLine = range.startLineNumber === range.endLineNumber;
    const startX = instance.getOffsetForColumn(range.startLineNumber, range.startColumn);
    const endX = oneLine ? instance.getOffsetForColumn(range.endLineNumber, range.endColumn) : startX;
    const width = instance.getLayoutInfo().contentWidth;
    instance.setScrollLeft(endX <= width - 24 ? 0 : Math.max(0, startX - 48));
  }, [highlight]);

  useEffect(() => {
    const model = editor.current?.getModel();
    if (!model) return;
    monaco.editor.setModelMarkers(model, 'trace', markers.map(m => {
      const start = model.getPositionAt(m.range?.start ?? 0);
      const end = model.getPositionAt(m.range?.end ?? model.getValueLength());
      return { severity: m.warning ? monaco.MarkerSeverity.Warning : monaco.MarkerSeverity.Error, message: m.message,
        startLineNumber: start.lineNumber, startColumn: start.column, endLineNumber: end.lineNumber, endColumn: end.column };
    }));
  }, [markers]);

  // Part 15a: estimates shown after each method's name line; hovering gives the reasons.
  useEffect(() => {
    const model = editor.current?.getModel();
    if (!model || !notes.current) return;
    notes.current.set(badges.filter(b => b.at <= model.getValueLength()).map(b => {
      const line = model.getPositionAt(b.at).lineNumber;
      const end = model.getLineMaxColumn(line);
      return { range: new monaco.Range(line, end, line, end), options: { showIfCollapsed: true, // an empty range at the line end
        after: { content: `  ${b.text}`, inlineClassName: 'complexity-badge' }, hoverMessage: { value: b.title } } };
    }));
  }, [badges]);

  // Part 22: the jump arrow, in the margin between the line numbers and the code.
  let arrow: { d: string; label: { x: number; y: number }; kind: 'back' | 'skip' } | null = null;
  const instance = editor.current;
  if (jump && instance) {
    void scroll;
    const half = instance.getOption(monaco.editor.EditorOption.lineHeight) / 2;
    const y = (line: number) => instance.getTopForLineNumber(line) - instance.getScrollTop() + half;
    const x = instance.getLayoutInfo().decorationsLeft + 20;
    const y1 = y(jump.from), y2 = y(jump.to), bow = Math.min(18, 6 + Math.abs(y2 - y1) / 8);
    arrow = { d: `M ${x} ${y1} C ${x - bow} ${y1}, ${x - bow} ${y2}, ${x} ${y2}`, label: { x: x - bow - 2, y: (y1 + y2) / 2 }, kind: jump.kind };
  }
  return (
    <div className="editor-wrap">
      <div className="editor-host" ref={host} data-testid="editor" />
      {arrow && (
        <svg className={`code-jump ${arrow.kind}`} aria-hidden="true" data-testid="code-jump" data-kind={arrow.kind}>
          <defs><marker id="jump-head" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse"><path d="M 0 0 L 10 5 L 0 10 z" /></marker></defs>
          <path d={arrow.d} markerEnd="url(#jump-head)" />
          <text x={arrow.label.x} y={arrow.label.y} className="code-jump-label">{arrow.kind === 'back' ? '↺' : '⤼'}</text>
        </svg>
      )}
    </div>
  );
}
