interface Props {
  cursor: number;
  steps: number;
  playing: boolean;
  onCursor: (cursor: number) => void;
  onPlaying: (playing: boolean) => void;
}

export function Controls({ cursor, steps, playing, onCursor, onPlaying }: Props) {
  return (
    <div className="controls" aria-label="Playback">
      <button onClick={() => onCursor(0)} disabled={cursor === 0} title="Restart">⏮</button>
      <button onClick={() => onCursor(cursor - 1)} disabled={cursor === 0} title="Step back" aria-label="Step back">◀</button>
      <button onClick={() => onPlaying(!playing)} disabled={steps === 0} title={playing ? 'Pause' : 'Play'}>{playing ? '⏸' : '▶'}</button>
      <button onClick={() => onCursor(cursor + 1)} disabled={cursor === steps} title="Step forward" aria-label="Step forward">▶|</button>
      <button onClick={() => onCursor(steps)} disabled={cursor === steps} title="End">⏭</button>
      <input type="range" min={0} max={steps} value={cursor} onChange={e => onCursor(Number(e.target.value))} aria-label="Step" />
      <span className="step-count" data-testid="step-count">Step {cursor} / {steps}</span>
    </div>
  );
}
