// Live console of one run over WebSocket (specs/live-console.md): streamed output,
// ordered input lines and EOF. Opened once per submitted run from an event handler,
// never from an effect, so the first connection (the controller) is the only one.
import { useSyncExternalStore } from 'react';
import type { Phase } from './runs.ts';

export interface ConsoleState { phase: Phase; interactive: boolean; controller: boolean; inputOpen: boolean }
export interface ConsoleText { stream: 'stdout' | 'stderr' | 'stdin'; text: string }

export interface ConsoleView {
  state: ConsoleState | null;
  transcript: ConsoleText[];
  /** Lines this console sent, in order. */
  sent: string[];
  /** The last rejection code, cleared by the next accepted line. */
  rejected: string | null;
  ended: boolean;
  closed: boolean;
}

export const REJECT_TEXT: Record<string, string> = {
  INPUT_NOT_INTERACTIVE: 'This program does not read input.',
  INPUT_CLOSED: 'Input is closed.',
  INPUT_LINE_LIMIT: 'A line may be at most 4 KiB.',
  INPUT_TOTAL_LIMIT: 'A run accepts at most 64 KiB of input.',
  INPUT_PENDING_LIMIT: 'The program has not read earlier input yet. Try again shortly.',
  INPUT_SEQUENCE: 'Input arrived out of order.',
  INPUT_INVALID: 'The input was not accepted.',
  NOT_CONTROLLER: 'Another window controls this run’s input.',
};

export class LiveConsole {
  private socket: WebSocket;
  private view: ConsoleView = { state: null, transcript: [], sent: [], rejected: null, ended: false, closed: false };
  private readonly listeners = new Set<() => void>();
  private seq = 0;

  constructor(runId: string) {
    const scheme = location.protocol === 'https:' ? 'wss' : 'ws';
    this.socket = new WebSocket(`${scheme}://${location.host}/api/runs/${encodeURIComponent(runId)}/console`);
    this.socket.onmessage = event => this.receive(String(event.data));
    this.socket.onclose = () => this.update({ closed: true });
  }

  subscribe = (listener: () => void) => { this.listeners.add(listener); return () => { this.listeners.delete(listener); }; };
  current = () => this.view;

  /** Sends one line (without its LF) and echoes it into the transcript. */
  send(text: string): void {
    if (this.socket.readyState !== WebSocket.OPEN) return;
    this.seq += 1;
    this.socket.send(JSON.stringify({ type: 'input', seq: this.seq, text }));
    this.update({ transcript: append(this.view.transcript, 'stdin', `${text}\n`), sent: [...this.view.sent, text] });
  }

  eof(): void {
    if (this.socket.readyState === WebSocket.OPEN) this.socket.send(JSON.stringify({ type: 'eof' }));
  }

  close(): void { this.socket.close(1000); }

  private receive(data: string): void {
    let message: { type?: unknown; [key: string]: unknown };
    try { message = JSON.parse(data); } catch { return; }
    switch (message.type) {
      case 'state': this.update({ state: message as unknown as ConsoleState }); return;
      case 'output':
        this.update({ transcript: append(this.view.transcript, message.stream === 'stderr' ? 'stderr' : 'stdout', String(message.text)) });
        return;
      case 'ack': this.update({ rejected: null }); return;
      case 'reject': this.update({ rejected: String(message.code) }); return;
      case 'end': this.update({ ended: true }); return;
    }
  }

  private update(change: Partial<ConsoleView>): void {
    this.view = { ...this.view, ...change };
    for (const listener of this.listeners) listener();
  }
}

/** Joins text onto the last piece when it has the same stream. */
function append(transcript: ConsoleText[], stream: ConsoleText['stream'], text: string): ConsoleText[] {
  const last = transcript.at(-1);
  if (last?.stream === stream) return [...transcript.slice(0, -1), { stream, text: last.text + text }];
  return [...transcript, { stream, text }];
}

const EMPTY: ConsoleView = { state: null, transcript: [], sent: [], rejected: null, ended: false, closed: true };
const NONE = () => () => {};

export function useConsoleView(live: LiveConsole | null): ConsoleView {
  return useSyncExternalStore(live ? live.subscribe : NONE, live ? live.current : () => EMPTY);
}
