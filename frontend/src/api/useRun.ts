// Server state for one run: submit, poll until finished, cancel, fetch output,
// and its live console.
// Playback state stays separate (trace/playback.ts).
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useRef, useState } from 'react';
import { LiveConsole } from './console.ts';
import { ApiError, cancelRun, getOutput, getRun, submitRun, type Snapshot } from './runs.ts';

/** {@code input}: the test input the run was given (Part 10a), or null for the live console. */
export interface SubmittedRun { runId: string; source: string; input: string | null; console: LiveConsole }

export function useRun() {
  const client = useQueryClient();
  const [run, setRun] = useState<SubmittedRun | null>(null);
  const live = useRef<LiveConsole | null>(null);

  const submit = useMutation({
    mutationFn: ({ source, input }: { source: string; input: string | null }) =>
      submitRun(source, input).then(snapshot => ({ snapshot, source, input })),
    onSuccess: ({ snapshot, source, input }) => {
      client.setQueryData(['run', snapshot.runId], snapshot);
      // The live console opens here, once per accepted run (Part 8g); never in an
      // effect or state updater, which StrictMode runs twice.
      live.current?.close();
      live.current = new LiveConsole(snapshot.runId);
      setRun({ runId: snapshot.runId, source, input, console: live.current });
    },
  });

  const snapshot = useQuery({
    queryKey: ['run', run?.runId],
    enabled: run !== null,
    queryFn: async () => {
      const next = await getRun(run!.runId);
      const previous = client.getQueryData<Snapshot>(['run', run!.runId]);
      // Ignore snapshots older than one already shown (run-v1 revision rule).
      return previous && previous.revision > next.revision ? previous : next;
    },
    refetchInterval: query => {
      const data = query.state.data as Snapshot | undefined;
      if (query.state.error instanceof ApiError) return false;
      return data?.phase === 'finished' ? false : 250;
    },
    retry: false,
  });

  const finished = snapshot.data?.phase === 'finished';
  const output = useQuery({
    queryKey: ['output', run?.runId],
    enabled: run !== null && finished,
    queryFn: () => getOutput(run!.runId),
    retry: false,
    staleTime: Infinity,
  });

  const cancel = useMutation({ mutationFn: () => cancelRun(run!.runId) });

  return { run, submit, snapshot, output, cancel };
}
