// Server state for one run: submit, poll until finished, cancel, fetch output.
// Playback state stays separate (trace/playback.ts).
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { ApiError, cancelRun, getOutput, getRun, submitRun, type Snapshot } from './runs.ts';

export interface SubmittedRun { runId: string; source: string }

export function useRun() {
  const client = useQueryClient();
  const [run, setRun] = useState<SubmittedRun | null>(null);

  const submit = useMutation({
    mutationFn: (source: string) => submitRun(source).then(snapshot => ({ snapshot, source })),
    onSuccess: ({ snapshot, source }) => {
      client.setQueryData(['run', snapshot.runId], snapshot);
      setRun({ runId: snapshot.runId, source });
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
