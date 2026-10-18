// Monaco Java editor. Loads only the editor core and Java syntax colouring from
// the local package (no CDN). Highlights are UTF-16 source ranges from the trace.
import * as monaco from 'monaco-editor/editor';
import 'monaco-editor/languages/definitions/java/register';
import EditorWorker from 'monaco-editor/editor/editor.worker?worker';
import { useEffect, useRef } from 'react';
import type { SourceRange } from '../trace/types.ts';

self.MonacoEnvironment = { getWorker: () => new EditorWorker() };

export interface Marker { range: SourceRange | null; message: string; warning?: boolean }

interface Props {
  value: string;
  onChange: (value: string) => void;
  readOnly: boolean;
  highlight: SourceRange | null;
  markers: Marker[];
}

export function JavaEditor({ value, onChange, readOnly, highlight, markers }: Props) {
  const host = useRef<HTMLDivElement>(null);
  const editor = useRef<monaco.editor.IStandaloneCodeEditor | null>(null);
  const decorations = useRef<monaco.editor.IEditorDecorationsCollection | null>(null);
  const change = useRef(onChange);
  change.current = onChange;

  useEffect(() => {
    const instance = monaco.editor.create(host.current!, {
      value, language: 'java', theme: 'vs-dark', automaticLayout: true, minimap: { enabled: false },
      fontSize: 14, scrollBeyondLastLine: false, tabSize: 4, insertSpaces: true,
    });
    editor.current = instance;
    decorations.current = instance.createDecorationsCollection();
    const subscription = instance.onDidChangeModelContent(() => change.current(instance.getValue()));
    return () => { subscription.dispose(); instance.dispose(); };
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

  return <div className="editor-host" ref={host} data-testid="editor" />;
}
