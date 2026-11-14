import { Check, Copy } from 'lucide-react';
import { useState } from 'react';

/** A terminal-styled command with a copy button, e.g. `git checkout 5c8f1d4`. */
export function CopyCommand({ command, className = '' }: { command: string; className?: string }) {
  const [copied, setCopied] = useState(false);
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(command);
      setCopied(true);
      setTimeout(() => setCopied(false), 1200);
    } catch {
      /* clipboard unavailable (e.g. insecure context) */
    }
  };
  return (
    <button
      type="button"
      onClick={copy}
      title="Copy to clipboard"
      className={`group/cmd flex w-full items-center gap-2 rounded-md border border-slate-700/80 bg-obsidian-900/90 px-2 py-1 text-left font-mono text-[11px] text-emerald-300 transition hover:border-emerald-500/50 ${className}`}
    >
      <span className="select-none text-slate-600">$</span>
      <span className="flex-1 truncate">{command}</span>
      {copied ? (
        <Check className="h-3.5 w-3.5 text-emerald-400" />
      ) : (
        <Copy className="h-3.5 w-3.5 text-slate-600 group-hover/cmd:text-emerald-400" />
      )}
    </button>
  );
}
