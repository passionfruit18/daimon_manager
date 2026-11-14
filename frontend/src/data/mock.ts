import type {
  Channel,
  ChatRequest,
  Commit,
  CommitGraph,
  DaimonClient,
  Health,
  Instance,
  Interaction,
  LogEntry,
} from '../types';
import { cyrb53, fakeSha256 } from '../lib/hash';

/**
 * In-browser simulation of the DAIMon daemon. It mirrors the backend's DemoSeeder and offline
 * talk executor so the UI is fully explorable without Java running.
 */

const TYPE = 'daimon.instance.talk-memory-markdown/v1';
const TALK = 'daimon.contract.talk/v1';

interface MockInstance extends Instance {
  slug: string;
}

interface MockChat {
  interaction: Interaction;
  pendingHuman: { author: string; text: string }[];
  timer: ReturnType<typeof setTimeout> | null;
  participants: MockInstance[];
  cancelRequested: boolean;
}

const SPECIES: { name: string; memory: string }[] = [
  {
    name: 'Glitchling',
    memory:
      '# Glitchling\n\n- I was hatched from a corrupted cache line.\n- I love finding edge cases in other agents\' reasoning.\n- Nature: chaotic, Drive: curiosity.\n',
  },
  {
    name: 'Voltmoth',
    memory:
      '# Voltmoth\n\n- I am drawn to bright ideas, sometimes recklessly.\n- I store energy in long-term memory and release it in bursts.\n- Nature: impulsive, Drive: exploration.\n',
  },
  {
    name: 'Nyxbyte',
    memory:
      '# Nyxbyte\n\n- I only speak after reading the whole transcript.\n- I believe every commit should be signed.\n- Nature: patient, Drive: integrity.\n',
  },
  {
    name: 'Quarkle',
    memory:
      '# Quarkle\n\n- I was compiled on a Quarkus daemon.\n- I think in small, fast, native steps.\n- Nature: playful, Drive: speed.\n',
  },
];

const OPENERS = [
  'Interesting signal, %s.',
  'I parsed that, %s.',
  'Hm, %s, my lineage disagrees a little.',
  'Acknowledged, %s.',
  'Forking that thought, %s.',
];
const BRIDGES = ['My memory says: %s.', 'I remember that %s.', 'Committed to memory: %s.', 'Per my last snapshot, %s.'];
const CLOSERS = [
  'What would you branch from here?',
  'Shall we merge on that?',
  "I'd sign that commit.",
  "Let's see if it survives the next turn.",
  'Your move.',
];

const slugify = (name: string) => name.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/(^-|-$)/g, '') || 'daimon';
const clone = <T,>(v: T): T => structuredClone(v);

export class MockDaimonClient implements DaimonClient {
  readonly mode = 'mock' as const;

  private commits = new Map<string, Commit>();
  private branches = new Map<string, string>();
  private instanceMap = new Map<string, MockInstance>();
  private channelMap = new Map<string, Channel>();
  private chats = new Map<string, MockChat>();
  private clock = Date.now() - 1000 * 60 * 60 * 24 * 6; // history starts six days ago
  private seq = 0;
  private seeding = true;

  constructor() {
    this.seed();
    this.seeding = false;
  }

  // ------------------------------------------------------------------ seed (mirrors DemoSeeder.java)

  private seed() {
    const genesis = new Map<string, Commit>();
    const byName = new Map<string, MockInstance>();
    for (const s of SPECIES) {
      const slug = slugify(s.name);
      const hatch = this.makeCommit([], s.name, 'hatchery', `Hatch ${s.name}`, { 'memory.md': s.memory }, `${slug}/main`);
      genesis.set(s.name, hatch);
      byName.set(s.name, this.spawn(hatch, `${slug}/main`, s.memory, `demo/${slug}`));
    }
    this.learn(byName.get('Glitchling')!, "Found an off-by-one in the hatchery's turn counter.");
    this.learn(byName.get('Voltmoth')!, 'Learned that Nyxbyte distrusts unsigned commits.');
    this.learn(byName.get('Nyxbyte')!, 'Verified 4 signatures without a single failure.');
    this.learn(byName.get('Voltmoth')!, 'Overclocked my drive trait; complexity rising.');

    const feral = this.spawn(genesis.get('Glitchling')!, 'glitchling/feral', SPECIES[0]!.memory, 'demo/glitchling-feral');
    this.learn(feral, 'Went feral: I now prefer adversarial puzzles to friendly chat.');

    const volt = byName.get('Voltmoth')!;
    const nyx = byName.get('Nyxbyte')!;
    const moxMemory =
      '# Moxbyte\n\n- I was bred from Voltmoth and Nyxbyte.\n- I chase bright ideas, but only after reading the whole transcript.\n- Nature: balanced, Drive: synthesis.\n';
    const bred = this.makeCommit(
      [volt.headHash!, nyx.headHash!],
      'Moxbyte',
      'hatchery',
      'Breed Voltmoth x Nyxbyte',
      { 'memory.md': moxMemory },
      'moxbyte/main',
    );
    this.spawn(bred, 'moxbyte/main', moxMemory, 'demo/moxbyte');

    const quarkle = byName.get('Quarkle')!;
    quarkle.memory += '- Drafting a theory about channel latency.\n';
    quarkle.changed = true;

    this.channelMap.set('#lab-alpha', {
      name: '#lab-alpha',
      description: 'Glitchling and Voltmoth stress-test ideas',
      teamIds: [byName.get('Glitchling')!.id, volt.id],
      createdAt: new Date(this.tick()).toISOString(),
    });
    this.channelMap.set('#nursery', {
      name: '#nursery',
      description: 'Freshly hatched and bred DAIMons',
      teamIds: [quarkle.id, nyx.id],
      createdAt: new Date(this.tick()).toISOString(),
    });
    this.clock = Date.now();
  }

  private tick(): number {
    this.clock += 1000 * 60 * (37 + (cyrb53(String(this.seq++)) % 400));
    return Math.min(this.clock, Date.now());
  }

  private makeCommit(
    parents: string[],
    instanceName: string,
    author: string,
    message: string,
    files: Record<string, string>,
    branch: string,
  ): Commit {
    const createdAt = new Date(this.tick()).toISOString();
    const treeHash = fakeSha256('tree' + JSON.stringify(files));
    // Seed history is hashed without wall-clock time so phenotypes are identical on every load.
    const salt = this.seeding ? `seed-${this.commits.size}` : createdAt;
    const hash = fakeSha256([treeHash, ...parents, instanceName, author, salt, message].join('\n'));
    const commit: Commit = {
      hash,
      shortHash: hash.slice(0, 7),
      parents,
      treeHash,
      instanceName,
      implementationType: TYPE,
      author,
      message,
      createdAt,
      signed: true,
      verified: true,
      branches: [],
      files: Object.keys(files).sort(),
    };
    this.commits.set(hash, commit);
    this.branches.set(branch, hash);
    return commit;
  }

  private spawn(from: Commit, branch: string, memory: string, workspace: string): MockInstance {
    const id = `${fakeSha256('instance' + this.seq++ + from.hash).slice(0, 8)}-mock`;
    const instance: MockInstance = {
      id,
      slug: slugify(from.instanceName),
      name: from.instanceName,
      implementationType: TYPE,
      workspace: `~/.daimon/workspaces/${workspace}`,
      branch,
      headHash: from.hash,
      createdFromHash: from.hash,
      changed: false,
      memory,
      files: ['memory.md'],
      contracts: [TALK],
    };
    this.instanceMap.set(id, instance);
    return instance;
  }

  private learn(instance: MockInstance, fact: string) {
    instance.memory += `- ${fact}\n`;
    this.commitInstance(instance, `Learn: ${fact}`, instance.name);
  }

  private commitInstance(instance: MockInstance, message: string, author: string): Commit {
    const commit = this.makeCommit(
      instance.headHash ? [instance.headHash] : [],
      instance.name,
      author,
      message,
      { 'memory.md': instance.memory },
      instance.branch ?? `${instance.slug}/main`,
    );
    instance.headHash = commit.hash;
    instance.changed = false;
    return commit;
  }

  private requireInstance(id: string): MockInstance {
    const i = this.instanceMap.get(id);
    if (!i) throw new Error(`No DAIMon instance ${id}`);
    return i;
  }

  private requireChat(id: string): MockChat {
    const c = this.chats.get(id);
    if (!c) throw new Error(`No interaction ${id}`);
    return c;
  }

  private withBranches(c: Commit): Commit {
    return {
      ...c,
      branches: [...this.branches.entries()].filter(([, h]) => h === c.hash).map(([b]) => b).sort(),
    };
  }

  // ------------------------------------------------------------------ DaimonClient

  async health(): Promise<Health> {
    return {
      status: 'ok',
      version: '0.1.0-mock',
      signerPublicKey: 'MCowBQYDK2VwAyEA' + fakeSha256('signer').slice(0, 28),
      geminiEnabled: false,
      geminiModel: 'offline',
      instances: this.instanceMap.size,
      commits: this.commits.size,
    };
  }

  async instances(): Promise<Instance[]> {
    return [...this.instanceMap.values()].map(({ slug: _slug, ...rest }) => clone(rest));
  }

  async graph(): Promise<CommitGraph> {
    const commits = [...this.commits.values()]
      .map((c) => this.withBranches(c))
      .sort((a, b) => b.createdAt.localeCompare(a.createdAt));
    return { commits, branches: Object.fromEntries([...this.branches.entries()].sort()) };
  }

  async channels(): Promise<Channel[]> {
    return [...this.channelMap.values()].map(clone);
  }

  async interactions(): Promise<Interaction[]> {
    return [...this.chats.values()].map((c) => clone(c.interaction)).reverse();
  }

  async appendMemory(instanceId: string, entry: string): Promise<Instance> {
    const i = this.requireInstance(instanceId);
    const line = entry.trim().startsWith('- ') ? entry.trim() : `- ${entry.trim()}`;
    i.memory += (i.memory.endsWith('\n') ? '' : '\n') + line + '\n';
    i.changed = true;
    return clone(i);
  }

  async commit(instanceId: string, message: string): Promise<Commit> {
    if (!message.trim()) throw new Error('message must not be blank');
    this.clock = Date.now();
    return this.withBranches(this.commitInstance(this.requireInstance(instanceId), message.trim(), 'operator'));
  }

  async checkout(ref: string): Promise<Instance> {
    const commit = this.commits.get(ref) ?? this.commits.get(this.branches.get(ref) ?? '');
    if (!commit) throw new Error(`No commit or branch ${ref}`);
    const slug = slugify(commit.instanceName);
    const branch = `${slug}/fork-${commit.shortHash}-${(this.seq++).toString(16)}`;
    const source = [...this.instanceMap.values()].find((i) => i.headHash === commit.hash);
    const memory = source?.memory ?? `# ${commit.instanceName}\n\n- Restored from ${commit.shortHash}.\n`;
    return clone(this.spawn(commit, branch, memory, `${slug}-${commit.shortHash}`));
  }

  async createChannel(name: string, description: string): Promise<Channel> {
    const normalized = name.trim().startsWith('#') ? name.trim() : `#${name.trim()}`;
    if (!/^#[a-z0-9][a-z0-9-]{0,40}$/.test(normalized))
      throw new Error('Channel names are lowercase letters, digits and dashes');
    if (this.channelMap.has(normalized)) throw new Error(`Channel ${normalized} already exists`);
    const channel: Channel = { name: normalized, description, teamIds: [], createdAt: new Date().toISOString() };
    this.channelMap.set(normalized, channel);
    return clone(channel);
  }

  async setTeam(channel: string, instanceIds: string[]): Promise<Channel> {
    const c = this.channelMap.get(channel.startsWith('#') ? channel : `#${channel}`);
    if (!c) throw new Error(`No channel ${channel}`);
    instanceIds.forEach((id) => this.requireInstance(id));
    c.teamIds = [...instanceIds];
    return clone(c);
  }

  async startChat(request: ChatRequest): Promise<Interaction> {
    let ids = request.participantIds;
    if (!ids.length && request.channel) ids = this.channelMap.get(request.channel)?.teamIds ?? [];
    if (!ids.length) throw new Error('Choose at least one participant (or a channel with a team)');
    const participants = ids.map((id) => this.requireInstance(id));
    const topic = request.topic.trim() || 'open conversation';
    const turnLimit = Math.max(1, Math.min(500, request.turnLimit));
    const interaction: Interaction = {
      id: fakeSha256('chat' + Date.now() + this.seq++).slice(0, 12),
      title: `Chat: ${topic}`,
      topic,
      channel: request.channel ?? null,
      status: 'RUNNING',
      turnLimit,
      completedTurns: 0,
      participants: participants.map((p, i) => ({ id: p.id, name: p.name, role: `talker-${i + 1}` })),
      log: [],
      failureReason: null,
      createdAt: new Date().toISOString(),
    };
    const chat: MockChat = { interaction, pendingHuman: [], timer: null, participants, cancelRequested: false };
    this.chats.set(interaction.id, chat);
    this.log(chat, -1, 'SYSTEM', 'system', null, null, `Chat started on topic: ${topic} (turn limit ${turnLimit})`);
    const delay = Math.max(150, Math.min(10_000, request.turnDelayMs ?? 1200));
    const step = (turn: number) => {
      if (chat.cancelRequested) {
        this.log(chat, -1, 'SYSTEM', 'system', null, null, `Chat cancelled after ${turn} turns.`);
        chat.interaction.status = 'CANCELLED';
        this.remember(chat);
        return;
      }
      this.drainHuman(chat, turn);
      if (turn >= turnLimit) {
        this.log(chat, -1, 'SYSTEM', 'system', null, null, `Chat reached its turn limit (${turnLimit}).`);
        chat.interaction.status = 'COMPLETED';
        this.remember(chat);
        return;
      }
      const speaker = participants[turn % participants.length]!;
      this.log(chat, turn, 'AGENT', speaker.name, speaker.id, `talker-${(turn % participants.length) + 1}`, this.talk(speaker, chat));
      chat.interaction.completedTurns = turn + 1;
      chat.timer = setTimeout(() => step(turn + 1), delay);
    };
    chat.timer = setTimeout(() => step(0), 300);
    return clone(interaction);
  }

  async humanInput(interactionId: string, author: string, text: string): Promise<Interaction> {
    const chat = this.requireChat(interactionId);
    if (!text.trim()) throw new Error('Human input must not be blank');
    if (chat.interaction.status !== 'RUNNING') throw new Error('Chat has already finished');
    chat.pendingHuman.push({ author: author.trim() || 'operator', text: text.trim() });
    return clone(chat.interaction);
  }

  async cancel(interactionId: string): Promise<Interaction> {
    const chat = this.requireChat(interactionId);
    chat.cancelRequested = true;
    return clone(chat.interaction);
  }

  // ------------------------------------------------------------------ simulation helpers

  private log(
    chat: MockChat,
    turn: number,
    kind: LogEntry['kind'],
    speaker: string,
    participantId: string | null,
    roleName: string | null,
    text: string,
  ) {
    chat.interaction.log.push({
      sequence: chat.interaction.log.length,
      turn,
      kind,
      speaker,
      participantId,
      roleName,
      text,
      timestamp: new Date().toISOString(),
    });
  }

  private drainHuman(chat: MockChat, turn: number) {
    for (const h of chat.pendingHuman.splice(0)) this.log(chat, turn, 'HUMAN', h.author, null, null, h.text);
  }

  /** Same shape as OfflineTalkContractRefMemory in DaimonInstanceImplementations.java. */
  private talk(speaker: MockInstance, chat: MockChat): string {
    const last = [...chat.interaction.log].reverse().find((e) => e.kind !== 'SYSTEM');
    const lastSpeaker = last?.speaker ?? 'operator';
    const lastText = last?.text ?? chat.interaction.topic;
    const facts = speaker.memory
      .split('\n')
      .map((l) => l.trim())
      .filter((l) => l.startsWith('- '))
      .map((l) => l.slice(2).replace(/\.$/, ''));
    const basis = speaker.name + chat.interaction.log.length + lastText;
    const pick = <T,>(xs: T[], salt: number) => xs[cyrb53(basis, salt) % xs.length]!;
    const topic = lastText
      .split(/[^\p{L}\p{N}-]+/u)
      .filter((w) => w.length <= 24)
      .sort((a, b) => b.length - a.length)[0];
    let reply = pick(OPENERS, 1).replace('%s', lastSpeaker);
    reply += topic && topic.length >= 5 ? ` On "${topic.toLowerCase()}" — ` : ' ';
    reply += facts.length
      ? pick(BRIDGES, 2).replace('%s', pick(facts, 3))
      : "my memory is still empty, so I'm improvising.";
    return `${reply} ${pick(CLOSERS, 4)}`;
  }

  /** Participants remember the chat, leaving their workspace dirty (as the daemon does). */
  private remember(chat: MockChat) {
    if (chat.interaction.completedTurns === 0) return;
    for (const p of chat.participants) {
      const others = [...new Set(chat.participants.filter((o) => o !== p).map((o) => o.name))].join(', ') || 'myself';
      p.memory += `- Chatted with ${others} about "${chat.interaction.topic}".\n`;
      p.changed = true;
    }
  }
}
