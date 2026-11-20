import { useEffect, useState } from 'react';
import {
  Activity,
  Bot,
  CheckCircle2,
  FileText,
  Folder,
  Play,
  RefreshCw,
  ShieldCheck,
  Terminal,
  Wrench,
  AlertCircle
} from 'lucide-react';

type Tool = { name: string; description?: string };

const presets = [
  'ls -la',
  'pwd',
  'mkdir my-folder',
  'touch note.txt',
  'cat README.md',
  'rm note.txt',
  'git status',
  'git log -1 --oneline',
  'java -version',
  'date',
  'whoami',
  'uname'
];

async function api<T>(url: string, init?: RequestInit): Promise<T> {
  const r = await fetch(url, {
    ...init,
    headers: { 'Content-Type': 'application/json', ...(init?.headers || {}) }
  });
  const d = await r.json().catch(() => ({}));
  if (!r.ok) throw Error(d.message || d.error || `HTTP ${r.status}`);
  return d as T;
}

export default function App() {
  const [tools, setTools] = useState<Tool[]>([]);
  const [online, setOnline] = useState(false);
  const [error, setError] = useState('');
  const [path, setPath] = useState('.');
  const [file, setFile] = useState('');
  const [fileContent, setFileContent] = useState('');
  const [folderName, setFolderName] = useState('');
  const [command, setCommand] = useState('ls -la');
  const [pending, setPending] = useState<{ id: string; command: string } | null>(null);
  const [chat, setChat] = useState('');
  const [answer, setAnswer] = useState('');
  const [output, setOutput] = useState<{ name: string; text: string }[]>([]);
  const [busy, setBusy] = useState(false);

  const add = (name: string, text: string) => setOutput(v => [{ name, text }, ...v].slice(0, 30));

  const refresh = async () => {
    try {
      setTools(await api<Tool[]>('/api/tools'));
      setOnline(true);
      setError('');
    } catch (e) {
      setOnline(false);
      setError((e as Error).message);
    }
  };

  useEffect(() => {
    refresh();
  }, []);

  const call = async (name: string, args: Record<string, string>) => {
    setBusy(true);
    try {
      const r = await api<{ result: string }>(`/api/tools/${name}`, {
        method: 'POST',
        body: JSON.stringify(args)
      });
      add(name, r.result);
      if (name === 'create_folder' || name === 'delete_file' || name === 'write_file') {
        call('list_files', { path });
      }
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const readFile = async () => {
    if (!file.trim()) return;
    setBusy(true);
    try {
      const r = await api<{ result: string }>('/api/tools/read_file', {
        method: 'POST',
        body: JSON.stringify({ path: file })
      });
      setFileContent(r.result);
      add(`read_file: ${file}`, r.result);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const preview = async () => {
    if (!command.trim()) return;
    setBusy(true);
    try {
      setPending(await api('/api/commands/preview', {
        method: 'POST',
        body: JSON.stringify({ command: command.trim() })
      }));
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const approve = async () => {
    if (!pending) return;
    setBusy(true);
    try {
      const r = await api<{ result: string }>(`/api/commands/${pending.id}/approve`, {
        method: 'POST',
        body: '{}'
      });
      add(`approved: ${pending.command}`, r.result);
      setPending(null);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const ask = async () => {
    setBusy(true);
    setAnswer('');
    try {
      const r = await api<{ answer: string }>('/api/chat', {
        method: 'POST',
        body: JSON.stringify({ message: chat })
      });
      setAnswer(r.answer);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="shell">
      <header>
        <div className="brand">
          <span className="logo"><Terminal /></span>
          <div>
            <b>MCP Terminal</b>
            <small>Local developer control plane</small>
          </div>
        </div>
        <div className="right">
          <span className={online ? 'online' : 'offline'}>
            ● {online ? 'MCP Connected' : 'MCP Offline'}
          </span>
          <button className="icon" onClick={refresh} title="Refresh connection">
            <RefreshCw size={17} />
          </button>
        </div>
      </header>

      <section className="hero">
        <div>
          <small className="eyebrow">MODEL CONTEXT PROTOCOL</small>
          <h1>Terminal workspace</h1>
          <p>Explore & edit files, create folders, inspect MCP tools and run approved terminal commands.</p>
        </div>
        <div className="secure">
          <ShieldCheck />
          <span>
            Approval required<br />
            <b>Workspace confined & safe mode</b>
          </span>
        </div>
      </section>

      {error && (
        <div className="error">
          <AlertCircle size={16} />
          {error}
        </div>
      )}

      <main>
        {/* Left Column: Tools & Workspace Files */}
        <section className="panel">
          <div className="head">
            <Wrench />
            <b>MCP tools</b>
            <small>{tools.length} discovered</small>
          </div>
          {tools.map(t => (
            <div className="tool" key={t.name}>
              <Activity size={15} />
              <div>
                <b>{t.name}</b>
                <small>{t.description}</small>
              </div>
            </div>
          ))}

          <hr />

          <div className="head">
            <Folder />
            <b>Workspace files & directories</b>
          </div>

          <label>Directory</label>
          <div className="inline">
            <input
              value={path}
              onChange={e => setPath(e.target.value)}
              placeholder="Relative path, e.g. ."
            />
            <button onClick={() => call('list_files', { path })} disabled={busy}>
              List
            </button>
          </div>

          <label>Create directory</label>
          <div className="inline">
            <input
              placeholder="e.g. docs, src/utils"
              value={folderName}
              onChange={e => setFolderName(e.target.value)}
            />
            <button
              className="secondary"
              onClick={() => {
                call('create_folder', { path: folderName });
                setFolderName('');
              }}
              disabled={busy || !folderName.trim()}
            >
              + Folder
            </button>
          </div>

          <label>File path</label>
          <div className="inline">
            <input
              placeholder="e.g. README.md, note.txt"
              value={file}
              onChange={e => setFile(e.target.value)}
            />
            <button className="secondary" onClick={readFile} disabled={busy || !file.trim()}>
              Read
            </button>
          </div>

          <label>File content (view, create, edit)</label>
          <textarea
            style={{ height: '85px', fontFamily: "'DM Mono', monospace", fontSize: '11px' }}
            placeholder="Type content to create or edit a file, or click 'Read' to load an existing file..."
            value={fileContent}
            onChange={e => setFileContent(e.target.value)}
          />

          <div className="file-actions">
            <button
              className="primary"
              onClick={() => call('write_file', { path: file, content: fileContent })}
              disabled={busy || !file.trim()}
            >
              Save / Write file
            </button>
            <button
              className="danger"
              onClick={() => {
                if (confirm(`Delete '${file}'?`)) {
                  call('delete_file', { path: file });
                  setFile('');
                  setFileContent('');
                }
              }}
              disabled={busy || !file.trim()}
            >
              Delete file
            </button>
          </div>

          <button className="secondary full" onClick={() => call('system_info', {})} disabled={busy}>
            System information
          </button>
        </section>

        {/* Middle Column: Command Approval & Execution */}
        <section className="panel">
          <div className="head">
            <Terminal />
            <b>Command approval</b>
            <small className="safe">SAFE MODE</small>
          </div>
          <p>Type any command or click a preset. Commands execute within your workspace upon approval.</p>

          <label>Quick presets</label>
          <div className="chips">
            {presets.map(p => (
              <button
                key={p}
                type="button"
                className="chip"
                onClick={() => {
                  setCommand(p);
                  setPending(null);
                }}
              >
                {p}
              </button>
            ))}
          </div>

          <label>Command (editable)</label>
          <div className="cmd-input-wrap">
            <span className="prompt-sym">$</span>
            <input
              value={command}
              onChange={e => {
                setCommand(e.target.value);
                setPending(null);
              }}
              placeholder="e.g. ls -la, mkdir folder, touch file.txt, cat file.txt, rm file.txt"
            />
          </div>

          <div className="preview">
            <small>COMMAND PREVIEW · CONFINED TO WORKSPACE</small>
            <code>$ {command.trim() || '(enter a command)'}</code>
            <p>Runs safely in workspace. Requires explicit preview & approval before execution.</p>
          </div>

          {!pending ? (
            <button
              className="primary full"
              onClick={preview}
              disabled={busy || !command.trim()}
            >
              <Play size={15} /> Request approval
            </button>
          ) : (
            <div className="approval">
              <b>Confirm execution</b>
              <p>Run <code>{pending.command}</code> in your workspace?</p>
              <div className="actions">
                <button className="secondary" onClick={() => setPending(null)}>
                  Cancel
                </button>
                <button className="danger" onClick={approve} disabled={busy}>
                  <CheckCircle2 size={15} /> Approve & run
                </button>
              </div>
            </div>
          )}
        </section>

        {/* Right Column: Ollama AI Assistant */}
        <section className="panel">
          <div className="head">
            <Bot />
            <b>Ollama assistant</b>
            <small className="safe purple">LOCAL AI</small>
          </div>
          <p>Ask about tools or commands. AI suggestions cannot execute anything.</p>
          <textarea
            value={chat}
            onChange={e => setChat(e.target.value)}
            placeholder="e.g. How do I create a directory with mkdir -p?"
          />
          <button className="primary full" onClick={ask} disabled={busy || !chat.trim()}>
            <Bot size={15} /> Ask local model
          </button>
          {answer && (
            <div className="answer">
              <b>Assistant</b>
              <p>{answer}</p>
            </div>
          )}
        </section>
      </main>

      {/* Bottom Section: Activity Log */}
      <section className="panel logs">
        <div className="head">
          <FileText />
          <b>Activity & terminal output</b>
          <button className="clear" onClick={() => setOutput([])}>
            Clear
          </button>
        </div>
        {!output.length ? (
          <div className="empty">
            <Terminal />
            <span>Tool results and command output appear here.</span>
          </div>
        ) : (
          output.map((o, i) => (
            <div className="log" key={i}>
              <b>{o.name}</b>
              <pre>{o.text}</pre>
            </div>
          ))
        )}
      </section>

      <footer>Runs locally · MCP over STDIO · Ollama stays on your machine</footer>
    </div>
  );
}
