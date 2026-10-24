interface Props {
  cursor: number;
  steps: number;
  playing: boolean;
  onCursor: (cursor: number) => void;
  onPlaying: (playing: boolean) => void;
  // Part 11a: play speed and whether steps are animated.
  speed: number;
  onSpeed: (speed: number) => void;
  motion: boolean;
  onMotion: (motion: boolean) => void;
  /** Part 22: the step's arrows and actions on the picture. */
  arrows: boolean;
  onArrows: (arrows: boolean) => void;
}

export const SPEEDS = [0.5, 1, 2, 4];

// One drawn icon set, so the five buttons look alike in every font (text glyphs like ▶| did not).
const ICON_PATHS = {
  restart: 'M4 4h2v16H4zM14 4v16L6 12zM22 4v16l-8-8z', // |◀◀ to the first step
  back: 'M6 4h2v16H6zM19 4v16L8 12z', // |◀ one step back
  play: 'M7 4v16l13-8z',
  pause: 'M6 4h4v16H6zM14 4h4v16h-4z',
  forward: 'M16 4h2v16h-2zM5 4v16l11-8z', // ▶| one step forward
  end: 'M18 4h2v16h-2zM2 4v16l8-8zM10 4v16l8-8z', // ▶▶| to the last step
};

function Icon({ name }: { name: keyof typeof ICON_PATHS }) {
  return <svg viewBox="0 0 24 24" width="16" height="16" aria-hidden="true" focusable="false"><path d={ICON_PATHS[name]} fill="currentColor" /></svg>;
}

export function Controls({ cursor, steps, playing, onCursor, onPlaying, speed, onSpeed, motion, onMotion, arrows, onArrows }: Props) {
  return (
    <div className="controls" aria-label="Playback">
      <button onClick={() => onCursor(0)} disabled={cursor === 0} title="Restart" aria-label="Restart"><Icon name="restart" /></button>
      <button onClick={() => onCursor(cursor - 1)} disabled={cursor === 0} title="Step back" aria-label="Step back"><Icon name="back" /></button>
      <button onClick={() => onPlaying(!playing)} disabled={steps === 0} title={playing ? 'Pause' : 'Play'} aria-label={playing ? 'Pause' : 'Play'}><Icon name={playing ? 'pause' : 'play'} /></button>
      <button onClick={() => onCursor(cursor + 1)} disabled={cursor === steps} title="Step forward" aria-label="Step forward"><Icon name="forward" /></button>
      <button onClick={() => onCursor(steps)} disabled={cursor === steps} title="End" aria-label="End"><Icon name="end" /></button>
      <input type="range" min={0} max={steps} value={cursor} onChange={e => onCursor(Number(e.target.value))} aria-label="Step" />
      <span className="step-count" data-testid="step-count">Step {cursor} / {steps}</span>
      <select aria-label="Speed" value={speed} onChange={e => onSpeed(Number(e.target.value))} title="Play speed">
        {SPEEDS.map(s => <option key={s} value={s}>{s}×</option>)}
      </select>
      <label className="motion-toggle" title="Animate each step: values travel to where they are stored">
        <input type="checkbox" checked={motion} onChange={e => onMotion(e.target.checked)} /> Motion
      </label>
      <label className="motion-toggle" title="Show what each step does on the picture: where values came from, swaps, links, comparisons, jumps">
        <input type="checkbox" checked={arrows} onChange={e => onArrows(e.target.checked)} /> Arrows
      </label>
    </div>
  );
}
