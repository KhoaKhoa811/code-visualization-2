// Run API client (backend/RUN_API.md). Snapshots follow contracts/run/v1.
import axios, { AxiosError } from 'axios';
import type { Trace } from '../trace/types.ts';

export type Phase = 'queued' | 'preparing' | 'compiling' | 'executing' | 'stopping' | 'cleaning' | 'finished';

export interface Snapshot {
  schemaVersion: '1.1.0';
  runId: string;
  sourceSha256: string;
  /** Part 10a: provided: hash of the given input; live: hash of the input delivered (once finished). */
  input: { mode: 'none' | 'live' | 'provided'; sha256: string | null };
  revision: number;
  phase: Phase;
  terminationConfirmed: boolean;
  cleanupVerified: boolean;
  slotHeld: boolean;
  trace: Trace | null;
  infrastructureFailures: string[];
}

export interface RunOutput { runId: string; stdout: string; stderr: string }

/** A fixed error code from the API, or NETWORK when the backend is unreachable. */
export class ApiError extends Error {
  readonly code: string;
  readonly status: number | null;
  constructor(code: string, status: number | null) { super(code); this.code = code; this.status = status; }
}

const http = axios.create({ baseURL: '/api', timeout: 30_000 });

async function call<T>(request: Promise<{ data: T }>): Promise<T> {
  try {
    return (await request).data;
  } catch (failure) {
    const error = failure as AxiosError<{ error?: string }>;
    const code = error.response?.data?.error;
    throw new ApiError(typeof code === 'string' ? code : 'NETWORK', error.response?.status ?? null);
  }
}

export async function sha256Hex(text: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text));
  return Array.from(new Uint8Array(digest), b => b.toString(16).padStart(2, '0')).join('');
}

/** {@code input}: test input given with the request (Part 10a); null keeps the live console (Part 8g). */
export async function submitRun(text: string, input: string | null = null): Promise<Snapshot> {
  const sha256 = await sha256Hex(text);
  const source = { file: 'Main.java', text, sha256 };
  const body = input === null
    ? { schemaVersion: '1.2.0', source, inputMode: 'live' }
    : { schemaVersion: '1.2.0', source, inputMode: 'provided', input: { text: input, sha256: await sha256Hex(input) } };
  return call(http.post<Snapshot>('/runs', body));
}

export const getRun = (runId: string) => call(http.get<Snapshot>(`/runs/${encodeURIComponent(runId)}`));
export const cancelRun = (runId: string) => call(http.post<Snapshot>(`/runs/${encodeURIComponent(runId)}/cancel`));
export const getOutput = (runId: string) => call(http.get<RunOutput>(`/runs/${encodeURIComponent(runId)}/output`));

export const ERROR_TEXT: Record<string, string> = {
  REQUEST_INVALID: 'The request was rejected as malformed.',
  SOURCE_INVALID: 'The source is not well-formed Unicode text.',
  SOURCE_LIMIT: 'The source is larger than 256 KiB.',
  SOURCE_HASH_MISMATCH: 'The source changed while it was being sent.',
  INPUT_LIMIT: 'The test input is larger than 64 KiB.',
  INPUT_INVALID: 'The test input is not well-formed Unicode text.',
  INPUT_HASH_MISMATCH: 'The test input changed while it was being sent.',
  REQUEST_TOO_LARGE: 'The request is larger than 2 MiB.',
  RUNNER_BUSY: 'The runner is busy (one run and two queued). Try again shortly.',
  RUNNER_UNAVAILABLE: 'The runner is unavailable. Check that Docker Desktop is running.',
  RUN_NOT_FOUND: 'This run is no longer available.',
  QUEUE_EXPIRED: 'The run waited in the queue for 30 seconds and was dropped.',
  CANCEL_NOT_ACCEPTED: 'The run had already stopped.',
  NETWORK: 'The backend could not be reached.',
};
