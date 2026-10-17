using System;
using System.IO;
using System.Threading.Tasks;

namespace Codeviz.Probes
{
    // Windows PowerShell helper. Raw bytes only; no UI queues or text decoding.
    public sealed class OutputBudget
    {
        private readonly object gate = new object();
        private readonly byte[] stdout;
        private readonly byte[] stderr;
        private int outCount;
        private int errCount;
        private bool exceeded;

        public OutputBudget(int quota)
        {
            if (quota <= 0 || quota > 1048576) throw new ArgumentOutOfRangeException("quota");
            stdout = new byte[quota];
            stderr = new byte[quota];
        }

        public int Count { get { lock (gate) { return outCount + errCount; } } }
        public bool Exceeded { get { lock (gate) { return exceeded; } } }

        public async Task DrainAsync(Stream stream, bool errorStream)
        {
            // Return control before draining even a continuously readable producer.
            await Task.Yield();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = await stream.ReadAsync(buffer, 0, buffer.Length).ConfigureAwait(false)) != 0)
            {
                lock (gate)
                {
                    int accepted = Math.Min(read, stdout.Length - outCount - errCount);
                    if (errorStream)
                    {
                        Buffer.BlockCopy(buffer, 0, stderr, errCount, accepted);
                        errCount += accepted;
                    }
                    else
                    {
                        Buffer.BlockCopy(buffer, 0, stdout, outCount, accepted);
                        outCount += accepted;
                    }
                    if (accepted != read) exceeded = true;
                }
                // Excess is discarded in fixed chunks until the supervisor stops the worker.
            }
        }

        // Call after readers finish. Copies are bounded by the shared accepted quota.
        public byte[] Snapshot(bool errorStream)
        {
            lock (gate)
            {
                int count = errorStream ? errCount : outCount;
                byte[] copy = new byte[count];
                Buffer.BlockCopy(errorStream ? stderr : stdout, 0, copy, 0, count);
                return copy;
            }
        }
    }
}
