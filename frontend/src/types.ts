// Mirrors backend/src/main/java/com/daimon/api/Dtos.java

export interface Health {
  status: string;
  version: string;
  signerPublicKey: string;
  geminiEnabled: boolean;
  geminiModel: string;
  instances: number;
  commits: number;
}

export interface Instance {
  id: string;
  name: string;
  implementationType: string;
  workspace: string;
  branch: string | null;
  headHash: string | null;
  createdFromHash: string | null;
  changed: boolean;
  memory: string;
  files: string[];
  contracts: string[];
}

export interface Commit {
  hash: string;
  shortHash: string;
  parents: string[];
  treeHash: string;
  instanceName: string;
  implementationType: string;
  author: string;
  message: string;
  createdAt: string;
  signed: boolean;
  verified: boolean;
  branches: string[];
  files: string[];
}

export interface CommitGraph {
  commits: Commit[];
  branches: Record<string, string>;
}

export interface Channel {
  name: string;
  description: string;
  teamIds: string[];
  createdAt: string;
}

export type LogKind = 'SYSTEM' | 'AGENT' | 'HUMAN';

export interface LogEntry {
  sequence: number;
  turn: number;
  kind: LogKind;
  speaker: string;
  participantId: string | null;
  roleName: string | null;
  text: string;
  timestamp: string;
}

export type InteractionStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'CANCELLED' | 'FAILED';

export interface Interaction {
  id: string;
  title: string;
  topic: string;
  channel: string | null;
  status: InteractionStatus;
  turnLimit: number;
  completedTurns: number;
  participants: { id: string; name: string; role: string }[];
  log: LogEntry[];
  failureReason: string | null;
  createdAt: string;
}

export interface ChatRequest {
  topic: string;
  turnLimit: number;
  participantIds: string[];
  channel?: string | null;
  turnDelayMs?: number;
}

/** One client interface, two implementations: the live daemon and an in-browser simulation. */
export interface DaimonClient {
  readonly mode: 'live' | 'mock';
  health(): Promise<Health>;
  instances(): Promise<Instance[]>;
  graph(): Promise<CommitGraph>;
  channels(): Promise<Channel[]>;
  interactions(): Promise<Interaction[]>;
  appendMemory(instanceId: string, entry: string): Promise<Instance>;
  commit(instanceId: string, message: string): Promise<Commit>;
  checkout(ref: string): Promise<Instance>;
  createChannel(name: string, description: string): Promise<Channel>;
  setTeam(channel: string, instanceIds: string[]): Promise<Channel>;
  startChat(request: ChatRequest): Promise<Interaction>;
  humanInput(interactionId: string, author: string, text: string): Promise<Interaction>;
  cancel(interactionId: string): Promise<Interaction>;
}
