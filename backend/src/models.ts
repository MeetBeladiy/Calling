export type FriendStatus = "pending" | "accepted";
export type CallStatus = "ringing" | "connected" | "declined" | "missed" | "ended" | "failed";
export type CallType = "voip" | "customer";

export interface User {
  id: string;
  name: string;
  email: string;
  phone?: string;
  profilePhoto?: string;
  passwordHash: string;
  createdAt: string;
  lastActiveAt: string;
  shareLastActive: boolean;
  suspendedAt?: string;
}

export interface Friendship {
  id: string;
  requesterId: string;
  addresseeId: string;
  status: FriendStatus;
  createdAt: string;
  updatedAt: string;
}

export interface Block {
  blockerId: string;
  blockedId: string;
  createdAt: string;
}

export interface AbuseReport {
  id: string;
  reporterId: string;
  reportedId: string;
  reason: string;
  details?: string;
  createdAt: string;
}

export interface CallRecord {
  id: string;
  callerId: string;
  receiverId?: string;
  type: CallType;
  startedAt: string;
  answeredAt?: string;
  endedAt?: string;
  durationSeconds: number;
  status: CallStatus;
  reconnectionCount: number;
  quality?: "excellent" | "good" | "weak" | "unknown";
  deliveryReference?: string;
}

export interface RefreshSession {
  id: string;
  userId: string;
  tokenHash: string;
  expiresAt: string;
  createdAt: string;
  revokedAt?: string;
  replacedById?: string;
}

export interface DatabaseState {
  users: User[];
  friendships: Friendship[];
  blocks: Block[];
  reports: AbuseReport[];
  calls: CallRecord[];
  refreshSessions: RefreshSession[];
}

export const emptyDatabase = (): DatabaseState => ({
  users: [],
  friendships: [],
  blocks: [],
  reports: [],
  calls: [],
  refreshSessions: []
});

export type AccountUser = Omit<User, "passwordHash" | "suspendedAt">;
export type PublicUser = Pick<User, "id" | "name" | "profilePhoto" | "createdAt" | "lastActiveAt" | "shareLastActive"> & { online?: boolean };

export function toAccountUser(user: User): AccountUser {
  const { passwordHash: _passwordHash, suspendedAt: _suspendedAt, ...safe } = user;
  return safe;
}

export function toPublicUser(user: User, online?: boolean): PublicUser {
  const safe: PublicUser = {
    id: user.id,
    name: user.name,
    ...(user.profilePhoto ? { profilePhoto: user.profilePhoto } : {}),
    createdAt: user.createdAt,
    lastActiveAt: user.lastActiveAt,
    shareLastActive: user.shareLastActive
  };
  return online === undefined ? safe : { ...safe, online };
}
